package com.tsong.aisearch.repository.elasticsearch;

import com.tsong.aisearch.config.AiSearchProperties;
import com.tsong.aisearch.model.dto.ModelResult;
import com.tsong.aisearch.model.dto.SearchResult;
import com.tsong.aisearch.repository.ProductSearchRepository;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.MultiMatchQueryBuilder;
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

    private final RestHighLevelClient client;
    private final AiSearchProperties properties;

    public ElasticsearchProductSearchRepository(RestHighLevelClient client,
                                                AiSearchProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public SearchResult search(String query, ModelResult modelResult, String sort,
                               Map<String, Object> filters) {
        long started = System.nanoTime();
        SearchResult result = new SearchResult();
        try {
            SearchRequest request = new SearchRequest(properties.getSearch().getIndexName());
            request.source(source(query, modelResult, sort, filters));
            SearchResponse response = client.search(request, RequestOptions.DEFAULT);
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

    private SearchSourceBuilder source(String query, ModelResult modelResult, String sort,
                                       Map<String, Object> filters) {
        String rewritten = modelResult != null && StringUtils.hasText(modelResult.getRewrittenQuery())
                ? modelResult.getRewrittenQuery() : query;
        Map<String, Float> boosts = modelResult == null
                ? Collections.<String, Float>emptyMap() : modelResult.getFieldBoosts();

        BoolQueryBuilder bool = QueryBuilders.boolQuery();
        MultiMatchQueryBuilder multiMatch = QueryBuilders.multiMatchQuery(rewritten)
                .field("title", boosts.getOrDefault("brand_name", 1.0f) * 3)
                .field("brand_name", boosts.getOrDefault("brand_name", 1.0f) * 3)
                .field("category_name", boosts.getOrDefault("category_name", 1.0f) * 2)
                .type(MultiMatchQueryBuilder.Type.BEST_FIELDS);
        bool.must(multiMatch);
        if (modelResult != null && modelResult.getSynonyms() != null) {
            for (String synonym : modelResult.getSynonyms()) {
                bool.should(QueryBuilders.matchQuery("title", synonym).boost(0.5f));
            }
        }
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

    private QueryBuilder functionScore(BoolQueryBuilder query) {
        FunctionScoreQueryBuilder.FilterFunctionBuilder[] functions = {
                new FunctionScoreQueryBuilder.FilterFunctionBuilder(
                        ScoreFunctionBuilders.fieldValueFactorFunction("sales_count")
                                .factor(0.3f).missing(0f)),
                new FunctionScoreQueryBuilder.FilterFunctionBuilder(
                        ScoreFunctionBuilders.fieldValueFactorFunction("rating")
                                .factor(0.2f).missing(0f))
        };
        return QueryBuilders.functionScoreQuery(query, functions);
    }

    private void applyFilters(BoolQueryBuilder query, Map<String, Object> filters) {
        if (filters == null) return;
        Collection<?> brands = collection(filters.get("brands"));
        if (!brands.isEmpty()) query.filter(QueryBuilders.termsQuery("brand_name.keyword", brands));
        Collection<?> categories = collection(filters.get("categories"));
        if (!categories.isEmpty()) {
            query.filter(QueryBuilders.termsQuery("category_name.keyword", categories));
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

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
