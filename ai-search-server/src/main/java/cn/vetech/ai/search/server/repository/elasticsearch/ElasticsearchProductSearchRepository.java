package cn.vetech.ai.search.server.repository.elasticsearch;

import cn.vetech.ai.search.server.config.AiSearchProperties;
import cn.vetech.ai.search.server.model.dto.ModelResult;
import cn.vetech.ai.search.server.model.dto.NerEntity;
import cn.vetech.ai.search.server.model.dto.SearchResult;
import cn.vetech.ai.search.server.repository.ProductSearchRepository;
import org.elasticsearch.common.lucene.search.function.CombineFunction;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.RangeQueryBuilder;
import org.elasticsearch.index.query.functionscore.FunctionScoreQueryBuilder;
import org.elasticsearch.index.query.functionscore.ScoreFunctionBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Repository
public class ElasticsearchProductSearchRepository implements ProductSearchRepository {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchProductSearchRepository.class);
    private static final float BRAND_FIELD_BOOST = 50f;
    private static final float BRAND_TITLE_BOOST = 40f;
    private static final float CATEGORY_FIELD_BOOST = 50f;
    private static final float CATEGORY_TITLE_BOOST = 40f;
    private static final float MODIFIER_FIELD_BOOST = 10f;
    private static final float MODIFIER_TITLE_BOOST = 5f;
    private static final float ATTRIBUTE_FIELD_BOOST = 5f;
    private static final float ATTRIBUTE_TITLE_BOOST = 5f;

    private final RestHighLevelClient client;
    private final AiSearchProperties properties;

    public ElasticsearchProductSearchRepository(RestHighLevelClient client,
                                                AiSearchProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public SearchResult search(String query, ModelResult modelResult, List<NerEntity> nerEntities, String sort,
                               Map<String, Object> filters) {
        long started = System.nanoTime();
        SearchResult result = new SearchResult();
        try {
            SearchRequest request = new SearchRequest(properties.getSearch().getIndexName());
            request.source(source(query, modelResult, nerEntities, sort, filters, true));
            SearchResponse response = client.search(request, RequestOptions.DEFAULT);

            if (hasRecognizedEntities(nerEntities) && response.getHits().getTotalHits().value == 0) {
                log.info("NER-aware search returned no hits; falling back to title search for query={}", query);
                SearchRequest fallback = new SearchRequest(properties.getSearch().getIndexName());
                fallback.source(source(query, modelResult, nerEntities, sort, filters, false));
                response = client.search(fallback, RequestOptions.DEFAULT);
            }

            result.setTotal(response.getHits().getTotalHits().value);
            result.setProducts(products(response));
            result.setAggregations(aggregations(response));
        } catch (Exception e) {
            log.error("Elasticsearch search failed for query={}", query, e);
            result.setTotal(0);
            result.setProducts(new ArrayList<SearchResult.ProductItem>());
            result.setAggregations(new SearchResult.AggregationResult());
        }
        result.setCostMs(elapsedMs(started));
        return result;
    }

    SearchSourceBuilder source(String query, ModelResult modelResult, List<NerEntity> nerEntities, String sort,
                               Map<String, Object> filters, boolean nerAware) {
        String rewritten = modelResult != null && StringUtils.hasText(modelResult.getRewrittenQuery())
                ? modelResult.getRewrittenQuery() : query;
        BoolQueryBuilder bool = query(rewritten, query, modelResult, nerEntities, nerAware);
        applyFilters(bool, filters);

        QueryBuilder finalQuery = defaultSort(sort) ? functionScore(bool) : bool;
        SearchSourceBuilder source = new SearchSourceBuilder()
                .query(finalQuery)
                .from(0)
                .size(properties.getSearch().getResultSize())
                .highlighter(new HighlightBuilder().field("title")
                        .preTags("<em>").postTags("</em>"))
                .aggregation(AggregationBuilders.terms("brands")
                        .field("brand_name.keyword").size(20))
                .aggregation(AggregationBuilders.terms("categories")
                        .field("category_name.keyword").size(20));
        applySort(source, sort);
        return source;
    }

    private BoolQueryBuilder query(String rewritten, String original, ModelResult modelResult,
                                   List<NerEntity> nerEntities, boolean nerAware) {
        BoolQueryBuilder bool = QueryBuilders.boolQuery();
        if (!nerAware || !hasRecognizedEntities(nerEntities)) {
            bool.must(QueryBuilders.matchQuery("title", rewritten));
            applySynonyms(bool, modelResult);
            return bool;
        }

        List<String> brands = new ArrayList<>();
        List<String> categories = new ArrayList<>();
        List<String> modifiers = new ArrayList<>();
        List<String> attributes = new ArrayList<>();
        for (NerEntity entity : nerEntities) {
            if (entity == null || !StringUtils.hasText(entity.getText())) continue;
            String label = entity.getLabel();
            if ("BRAND".equals(label)) {
                brands.add(entity.getText());
            } else if ("CATEGORY".equals(label) || "PRODUCT_TYPE".equals(label)) {
                categories.add(entity.getText());
            } else if ("MODIFIER".equals(label) || "AUDIENCE".equals(label)
                    || "MATERIAL".equals(label)) {
                modifiers.add(entity.getText());
            } else if ("ATTRIBUTE".equals(label) || "ATTRIBUTE_VALUE".equals(label)
                    || "MODEL".equals(label) || "SPEC".equals(label) || "COLOR".equals(label)) {
                attributes.add(entity.getText());
            }
        }

        String remaining = removeRecognizedParts(original, nerEntities);
        boolean hasRequiredClause = false;
        if (StringUtils.hasText(remaining)) {
            bool.must(QueryBuilders.matchQuery("title", remaining));
            hasRequiredClause = true;
        }

        for (String brand : brands) {
            BoolQueryBuilder brandQuery = QueryBuilders.boolQuery()
                    .should(QueryBuilders.matchQuery("brand_name", brand).boost(BRAND_FIELD_BOOST))
                    .should(QueryBuilders.matchQuery("title", brand).boost(BRAND_TITLE_BOOST))
                    .minimumShouldMatch(1);
            bool.must(brandQuery);
            hasRequiredClause = true;
        }
        int optionalClauses = 0;
        for (String category : categories) {
            bool.should(QueryBuilders.matchQuery("category_name", category).boost(CATEGORY_FIELD_BOOST));
            bool.should(QueryBuilders.matchQuery("title", category).boost(CATEGORY_TITLE_BOOST));
            optionalClauses += 2;
        }
        for (String modifier : modifiers) {
            bool.should(QueryBuilders.matchQuery("tags", modifier).boost(MODIFIER_FIELD_BOOST));
            bool.should(QueryBuilders.matchQuery("title", modifier).boost(MODIFIER_TITLE_BOOST));
            optionalClauses += 2;
        }
        for (String attribute : attributes) {
            bool.should(QueryBuilders.matchQuery("attributes", attribute).boost(ATTRIBUTE_FIELD_BOOST));
            bool.should(QueryBuilders.matchQuery("title", attribute).boost(ATTRIBUTE_TITLE_BOOST));
            optionalClauses += 2;
        }
        if (!hasRequiredClause && optionalClauses > 0) {
            bool.minimumShouldMatch(1);
        }
        applySynonyms(bool, modelResult);
        return bool;
    }

    private void applySynonyms(BoolQueryBuilder query, ModelResult modelResult) {
        if (modelResult == null || modelResult.getSynonyms() == null) return;
        for (String synonym : modelResult.getSynonyms()) {
            if (StringUtils.hasText(synonym)) {
                query.should(QueryBuilders.matchQuery("title", synonym).boost(0.5f));
            }
        }
    }

    private QueryBuilder functionScore(BoolQueryBuilder query) {
        FunctionScoreQueryBuilder.FilterFunctionBuilder[] functions = {
                new FunctionScoreQueryBuilder.FilterFunctionBuilder(
                        ScoreFunctionBuilders.fieldValueFactorFunction("sales_count")
                                .factor(0.3f).missing(0f)),
                new FunctionScoreQueryBuilder.FilterFunctionBuilder(
                        ScoreFunctionBuilders.fieldValueFactorFunction("rating")
                                .factor(0.2f).missing(0f))
        };
        return QueryBuilders.functionScoreQuery(query, functions).boostMode(CombineFunction.SUM);
    }

    private void applyFilters(BoolQueryBuilder query, Map<String, Object> filters) {
        if (filters == null) return;
        Collection<?> brands = collection(filters.get("brands"));
        if (!brands.isEmpty()) query.filter(QueryBuilders.termsQuery("brand_name.keyword", brands));
        Collection<?> categories = collection(filters.get("categories"));
        if (!categories.isEmpty()) {
            query.filter(QueryBuilders.termsQuery("category_name.keyword", categories));
        }
        if (Boolean.TRUE.equals(filters.get("inStock"))) {
            query.filter(QueryBuilders.termQuery("in_stock", true));
        }
        Object rawPrice = filters.get("priceRange");
        String priceRange = rawPrice == null ? "all" : String.valueOf(rawPrice);
        if ("all".equals(priceRange)) return;
        RangeQueryBuilder price = QueryBuilders.rangeQuery("price");
        if ("0-50".equals(priceRange)) price.lte(50);
        else if ("50-100".equals(priceRange)) price.gte(50).lte(100);
        else if ("100-500".equals(priceRange)) price.gte(100).lte(500);
        else if ("500-2000".equals(priceRange)) price.gte(500).lte(2000);
        else if ("2000+".equals(priceRange)) price.gte(2000);
        else return;
        query.filter(price);
    }

    private void applySort(SearchSourceBuilder source, String sort) {
        if ("price_asc".equals(sort)) {
            source.sort(SortBuilders.fieldSort("price").order(SortOrder.ASC));
        } else if ("price_desc".equals(sort)) {
            source.sort(SortBuilders.fieldSort("price").order(SortOrder.DESC));
        } else if ("sales".equals(sort)) {
            source.sort(SortBuilders.fieldSort("sales_count").order(SortOrder.DESC));
        } else if ("rating".equals(sort)) {
            source.sort(SortBuilders.fieldSort("rating").order(SortOrder.DESC));
        }
    }

    private List<SearchResult.ProductItem> products(SearchResponse response) {
        List<SearchResult.ProductItem> result = new ArrayList<>();
        for (SearchHit hit : response.getHits().getHits()) {
            Map<String, Object> source = hit.getSourceAsMap();
            SearchResult.ProductItem item = new SearchResult.ProductItem();
            item.setId(hit.getId());
            item.setTitle(string(source.get("title")));
            item.setBrand(string(source.get("brand_name")));
            item.setCategory(string(source.get("category_name")));
            item.setImage(string(source.get("main_pic")));
            Object price = source.get("price");
            item.setPrice(price instanceof Number ? ((Number) price).doubleValue() : 0);
            item.setScore(hit.getScore());
            if (hit.getHighlightFields().containsKey("title")
                    && hit.getHighlightFields().get("title").fragments().length > 0) {
                item.setHighlightTitle(hit.getHighlightFields().get("title").fragments()[0].string());
            }
            result.add(item);
        }
        return result;
    }

    private SearchResult.AggregationResult aggregations(SearchResponse response) {
        SearchResult.AggregationResult result = new SearchResult.AggregationResult();
        if (response.getAggregations() == null) return result;
        Terms brands = response.getAggregations().get("brands");
        if (brands != null) {
            List<SearchResult.AggBucket> buckets = new ArrayList<>();
            for (Terms.Bucket bucket : brands.getBuckets()) {
                buckets.add(new SearchResult.AggBucket(bucket.getKeyAsString(), bucket.getDocCount()));
            }
            result.setBrands(buckets);
        }
        Terms categories = response.getAggregations().get("categories");
        if (categories != null) {
            List<SearchResult.AggBucket> buckets = new ArrayList<>();
            for (Terms.Bucket bucket : categories.getBuckets()) {
                buckets.add(new SearchResult.AggBucket(bucket.getKeyAsString(), bucket.getDocCount()));
            }
            result.setCategories(buckets);
        }
        return result;
    }

    private static Collection<?> collection(Object value) {
        return value instanceof Collection ? (Collection<?>) value : Collections.emptyList();
    }

    private static boolean defaultSort(String sort) {
        return !StringUtils.hasText(sort) || "default".equals(sort);
    }

    private static boolean hasRecognizedEntities(List<NerEntity> entities) {
        if (entities == null) return false;
        for (NerEntity entity : entities) {
            if (entity == null || !StringUtils.hasText(entity.getText())) continue;
            String label = entity.getLabel();
            if ("BRAND".equals(label) || "CATEGORY".equals(label) || "PRODUCT_TYPE".equals(label)
                    || "MODIFIER".equals(label) || "AUDIENCE".equals(label)
                    || "MATERIAL".equals(label) || "ATTRIBUTE".equals(label)
                    || "ATTRIBUTE_VALUE".equals(label) || "MODEL".equals(label)
                    || "SPEC".equals(label) || "COLOR".equals(label)) {
                return true;
            }
        }
        return false;
    }

    static String removeRecognizedParts(String query, List<NerEntity> entities) {
        if (!StringUtils.hasText(query) || entities == null || entities.isEmpty()) {
            return query == null ? "" : query.trim();
        }
        List<NerEntity> sorted = new ArrayList<>(entities);
        sorted.sort((left, right) -> Integer.compare(left.getStart(), right.getStart()));
        StringBuilder remaining = new StringBuilder();
        int position = 0;
        for (NerEntity entity : sorted) {
            if (entity == null) continue;
            int start = Math.max(0, Math.min(entity.getStart(), query.length()));
            int end = Math.max(start, Math.min(entity.getEnd(), query.length()));
            if (end <= position) continue;
            if (start > position) remaining.append(query, position, start);
            position = end;
        }
        if (position < query.length()) remaining.append(query, position, query.length());
        return remaining.toString().trim();
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
