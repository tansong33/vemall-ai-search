package cn.vetech.ai.search.server.dao.impl;

import cn.vetech.ai.search.server.config.NerFieldMapping;
import cn.vetech.ai.search.server.config.SearchExclusionConfig;
import cn.vetech.ai.search.server.config.SearchProperties;
import cn.vetech.ai.search.server.dao.ProductSearchDao;
import cn.vetech.ai.search.server.dao.SearchDataAccessException;
import cn.vetech.ai.search.server.service.dto.NerEntityDto;
import cn.vetech.ai.search.server.service.dto.SearchQueryDto;
import cn.vetech.ai.search.server.service.vo.FacetsVo;
import cn.vetech.ai.search.server.service.vo.SearchItemVo;
import cn.vetech.ai.search.server.service.vo.SearchResultVo;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.lucene.search.function.CombineFunction;
import org.elasticsearch.common.lucene.search.function.FunctionScoreQuery;
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
import org.elasticsearch.search.fetch.subphase.highlight.HighlightField;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 使用 Elasticsearch 完成商品检索与聚合。
 */
@Component
public class EsProductSearchDao implements ProductSearchDao {

    private static final Logger log = LoggerFactory.getLogger(EsProductSearchDao.class);

    private static final String LABEL_BRAND = "BRAND";
    private static final String LABEL_CATEGORY = "CATEGORY";
    private static final String LABEL_MODIFIER = "MODIFIER";
    private static final String LABEL_ATTRIBUTE = "ATTRIBUTE";
    private static final String LABEL_MODEL = "MODEL";

    private static final float BRAND_FIELD_BOOST = 50.0f;
    private static final float BRAND_TITLE_BOOST = 40.0f;
    private static final float CATEGORY_FIELD_BOOST = 50.0f;
    private static final float CATEGORY_TITLE_BOOST = 40.0f;
    private static final float MODEL_FIELD_BOOST = 30.0f;
    private static final float MODEL_TITLE_BOOST = 20.0f;
    private static final float MODIFIER_FIELD_BOOST = 10.0f;
    private static final float MODIFIER_TITLE_BOOST = 5.0f;
    private static final float ATTRIBUTE_FIELD_BOOST = 5.0f;
    private static final float ATTRIBUTE_TITLE_BOOST = 5.0f;
    private static final float SYNONYM_BOOST = 0.5f;
    private static final float NORMALIZED_ID_BOOST = 60.0f;
    private static final float SALES_FACTOR = 0.3f;
    private static final float RATING_FACTOR = 0.2f;

    private final RestHighLevelClient client;
    private final SearchProperties properties;
    private final NerFieldMapping nerFieldMapping;
    private final SearchExclusionConfig exclusionConfig;

    public EsProductSearchDao(RestHighLevelClient client,
                              SearchProperties properties,
                              NerFieldMapping nerFieldMapping,
                              SearchExclusionConfig exclusionConfig) {
        this.client = client;
        this.properties = properties;
        this.nerFieldMapping = nerFieldMapping;
        this.exclusionConfig = exclusionConfig;
    }

    @Override
    public SearchResultVo search(SearchQueryDto query) {
        if (query == null) {
            throw new IllegalArgumentException("search query must not be null");
        }

        long started = System.nanoTime();
        SearchSourceBuilder source = buildSource(query, true);
        try {
            SearchResponse response = executeSearch(request(source));
            if (hasRecognizedEntities(query.getEntities()) && totalHits(response) == 0L) {
                log.info("NER-aware search returned no hits; falling back to title search for query={}",
                        query.getQuery());
                source = buildSource(query, false);
                response = executeSearch(request(source));
            }

            SearchResultVo result = parseResponse(response);
            result.setCostMs(elapsedMs(started));
            if (query.isIncludeEsDsl()) {
                result.setEsDsl(source.toString());
            }
            return result;
        } catch (Exception e) {
            log.error("Elasticsearch search failed for query={}", query.getQuery(), e);
            throw new SearchDataAccessException("Elasticsearch search failed", e);
        }
    }

    protected SearchResponse executeSearch(SearchRequest request) throws IOException {
        return client.search(request, RequestOptions.DEFAULT);
    }

    SearchSourceBuilder buildSource(SearchQueryDto query, boolean nerAware) {
        String effectiveQuery = StringUtils.hasText(query.getRewrittenQuery())
                ? query.getRewrittenQuery() : safeText(query.getQuery());
        BoolQueryBuilder boolQuery = buildBoolQuery(query, effectiveQuery, nerAware);
        applyFilters(boolQuery, query);

        QueryBuilder finalQuery = isRelevanceSort(query)
                ? buildFunctionScoreQuery(boolQuery) : boolQuery;
        SearchSourceBuilder source = new SearchSourceBuilder()
                .query(finalQuery)
                .from((query.getPage() - 1) * query.getPageSize())
                .size(query.getPageSize())
                .minScore(properties.getSearch().getMinScore())
                .highlighter(buildHighlight())
                .aggregation(AggregationBuilders.terms("brands")
                        .field("brand_name.keyword").size(20))
                .aggregation(AggregationBuilders.terms("categories")
                        .field("category_name.keyword").size(20));
        applySort(source, query);
        return source;
    }

    private SearchRequest request(SearchSourceBuilder source) {
        SearchRequest request = new SearchRequest(properties.getSearch().getIndexName());
        request.source(source);
        return request;
    }

    private BoolQueryBuilder buildBoolQuery(SearchQueryDto query,
                                            String effectiveQuery,
                                            boolean nerAware) {
        BoolQueryBuilder boolQuery = QueryBuilders.boolQuery();
        if (!nerAware || !hasRecognizedEntities(query.getEntities())) {
            boolQuery.must(QueryBuilders.matchQuery("title", effectiveQuery));
            applySynonyms(boolQuery, query.getSynonyms());
            return boolQuery;
        }

        List<String> brandWords = new ArrayList<String>();
        List<String> categoryWords = new ArrayList<String>();
        List<String> modifierWords = new ArrayList<String>();
        List<String> attributeWords = new ArrayList<String>();
        List<String> modelWords = new ArrayList<String>();
        List<NerEntityDto> genericEntities = new ArrayList<NerEntityDto>();
        collectEntities(query.getEntities(), brandWords, categoryWords, modifierWords,
                attributeWords, modelWords, genericEntities);

        String remaining = removeRecognizedParts(safeText(query.getQuery()), query.getEntities());
        if (StringUtils.hasText(remaining)) {
            boolQuery.must(QueryBuilders.matchQuery("title", remaining));
        }

        for (String brand : brandWords) {
            BoolQueryBuilder brandQuery = QueryBuilders.boolQuery()
                    .should(QueryBuilders.matchQuery("brand_name", brand).boost(BRAND_FIELD_BOOST))
                    .should(QueryBuilders.matchPhraseQuery("title", brand).boost(BRAND_TITLE_BOOST))
                    .minimumShouldMatch(1);
            boolQuery.must(brandQuery);
        }

        int nerShouldClauses = 0;
        for (String category : categoryWords) {
            nerShouldClauses += applyCategoryConstraint(boolQuery, category);
        }
        for (String modifier : modifierWords) {
            boolQuery.should(QueryBuilders.matchQuery("tags", modifier).boost(MODIFIER_FIELD_BOOST));
            boolQuery.should(QueryBuilders.matchQuery("title", modifier).boost(MODIFIER_TITLE_BOOST));
            nerShouldClauses += 2;
        }
        for (String attribute : attributeWords) {
            boolQuery.should(QueryBuilders.matchQuery("attributes", attribute).boost(ATTRIBUTE_FIELD_BOOST));
            boolQuery.should(QueryBuilders.matchQuery("title", attribute).boost(ATTRIBUTE_TITLE_BOOST));
            nerShouldClauses += 2;
        }
        for (String model : modelWords) {
            BoolQueryBuilder modelQuery = QueryBuilders.boolQuery()
                    .should(QueryBuilders.matchQuery("model_no", model).boost(MODEL_FIELD_BOOST))
                    .should(QueryBuilders.matchPhraseQuery("title", model).boost(MODEL_TITLE_BOOST))
                    .minimumShouldMatch(1);
            boolQuery.must(modelQuery);
        }

        for (NerEntityDto entity : genericEntities) {
            NerFieldMapping.FieldWeight mapping = nerFieldMapping.getMapping(entity.getLabel());
            if (mapping != null && StringUtils.hasText(mapping.getField())) {
                float boost = mapping.getBoost() == null ? 1.0f : mapping.getBoost();
                boolQuery.should(QueryBuilders.matchQuery(mapping.getField(), entity.searchText())
                        .boost(boost));
                nerShouldClauses++;
            }
        }

        applyNormalizedIds(boolQuery, query.getEntities());
        applySynonyms(boolQuery, query.getSynonyms());
        if (!StringUtils.hasText(remaining) && nerShouldClauses > 0) {
            boolQuery.minimumShouldMatch(1);
        }
        return boolQuery;
    }

    private void collectEntities(List<NerEntityDto> entities,
                                 List<String> brandWords,
                                 List<String> categoryWords,
                                 List<String> modifierWords,
                                 List<String> attributeWords,
                                 List<String> modelWords,
                                 List<NerEntityDto> genericEntities) {
        if (entities == null) {
            return;
        }
        for (NerEntityDto entity : entities) {
            if (entity == null || !StringUtils.hasText(entity.searchText())
                    || !StringUtils.hasText(entity.getLabel())) {
                continue;
            }
            String label = entity.getLabel();
            if (LABEL_BRAND.equals(label)) {
                brandWords.add(entity.searchText());
            } else if (LABEL_CATEGORY.equals(label)) {
                categoryWords.add(entity.searchText());
            } else if (LABEL_MODIFIER.equals(label)) {
                modifierWords.add(entity.searchText());
            } else if (LABEL_ATTRIBUTE.equals(label)) {
                attributeWords.add(entity.searchText());
            } else if (LABEL_MODEL.equals(label)) {
                modelWords.add(entity.searchText());
            } else if (nerFieldMapping.getMapping(label) != null) {
                genericEntities.add(entity);
            }
        }
    }

    private int applyCategoryConstraint(BoolQueryBuilder boolQuery, String categoryWord) {
        boolQuery.should(QueryBuilders.matchQuery("category_name", categoryWord)
                .boost(CATEGORY_FIELD_BOOST));
        boolQuery.should(QueryBuilders.matchPhraseQuery("title", categoryWord)
                .boost(CATEGORY_TITLE_BOOST));
        for (String exclusion : exclusionConfig.buildExclusionTerms(categoryWord)) {
            if (StringUtils.hasText(exclusion)) {
                boolQuery.mustNot(QueryBuilders.matchPhraseQuery("title", exclusion));
            }
        }
        return 2;
    }

    private int applyNormalizedIds(BoolQueryBuilder boolQuery, List<NerEntityDto> entities) {
        if (entities == null) {
            return 0;
        }
        int count = 0;
        for (NerEntityDto entity : entities) {
            if (entity == null || !StringUtils.hasText(entity.getNormalizedId())
                    || !StringUtils.hasText(entity.getNormalizedType())) {
                continue;
            }
            String field = nerFieldMapping.getNormalizationField(entity.getNormalizedType());
            if (StringUtils.hasText(field)) {
                boolQuery.should(QueryBuilders.termQuery(field, entity.getNormalizedId())
                        .boost(NORMALIZED_ID_BOOST));
                count++;
            }
        }
        return count;
    }

    private int applySynonyms(BoolQueryBuilder boolQuery, List<String> synonyms) {
        if (synonyms == null) {
            return 0;
        }
        int count = 0;
        for (String synonym : synonyms) {
            if (StringUtils.hasText(synonym)) {
                boolQuery.should(QueryBuilders.matchQuery("title", synonym).boost(SYNONYM_BOOST));
                count++;
            }
        }
        return count;
    }

    private QueryBuilder buildFunctionScoreQuery(BoolQueryBuilder boolQuery) {
        FunctionScoreQueryBuilder.FilterFunctionBuilder[] functions = {
                new FunctionScoreQueryBuilder.FilterFunctionBuilder(
                        ScoreFunctionBuilders.fieldValueFactorFunction("sales_count")
                                .factor(SALES_FACTOR).missing(0.0f)),
                new FunctionScoreQueryBuilder.FilterFunctionBuilder(
                        ScoreFunctionBuilders.fieldValueFactorFunction("rating")
                                .factor(RATING_FACTOR).missing(0.0f))
        };
        return QueryBuilders.functionScoreQuery(boolQuery, functions)
                .scoreMode(FunctionScoreQuery.ScoreMode.SUM)
                .boostMode(CombineFunction.SUM);
    }

    private HighlightBuilder buildHighlight() {
        return new HighlightBuilder()
                .field("title")
                .preTags("<em>")
                .postTags("</em>");
    }

    private void applyFilters(BoolQueryBuilder boolQuery, SearchQueryDto query) {
        if (query.getFilters() == null) {
            return;
        }
        if (query.getFilters().getBrands() != null
                && !query.getFilters().getBrands().isEmpty()) {
            boolQuery.filter(QueryBuilders.termsQuery(
                    "brand_name.keyword", query.getFilters().getBrands()));
        }
        if (query.getFilters().getCategories() != null
                && !query.getFilters().getCategories().isEmpty()) {
            boolQuery.filter(QueryBuilders.termsQuery(
                    "category_name.keyword", query.getFilters().getCategories()));
        }

        Long minPriceFen = query.getFilters().getMinPriceFen();
        Long maxPriceFen = query.getFilters().getMaxPriceFen();
        if (minPriceFen != null || maxPriceFen != null) {
            RangeQueryBuilder priceFilter = QueryBuilders.rangeQuery("price");
            if (minPriceFen != null) {
                priceFilter.gte(minPriceFen / 100.0);
            }
            if (maxPriceFen != null) {
                priceFilter.lte(maxPriceFen / 100.0);
            }
            boolQuery.filter(priceFilter);
        }
        if (Boolean.TRUE.equals(query.getFilters().getInStock())) {
            boolQuery.filter(QueryBuilders.termQuery("in_stock", true));
        }
    }

    private void applySort(SearchSourceBuilder source, SearchQueryDto query) {
        if (query.getSort() == null) {
            return;
        }
        String sort = query.getSort().name();
        if ("PRICE_ASC".equals(sort)) {
            source.sort(SortBuilders.fieldSort("price").order(SortOrder.ASC));
        } else if ("PRICE_DESC".equals(sort)) {
            source.sort(SortBuilders.fieldSort("price").order(SortOrder.DESC));
        } else if ("SALES".equals(sort)) {
            source.sort(SortBuilders.fieldSort("sales_count").order(SortOrder.DESC));
        } else if ("RATING".equals(sort)) {
            source.sort(SortBuilders.fieldSort("rating").order(SortOrder.DESC));
        }
    }

    private SearchResultVo parseResponse(SearchResponse response) {
        SearchResultVo result = new SearchResultVo();
        long total = totalHits(response);
        result.setTotal(total);
        result.setRawTotal(total);
        result.setItems(parseHits(response));
        result.setFacets(parseAggregations(response));
        return result;
    }

    private List<SearchItemVo> parseHits(SearchResponse response) {
        List<SearchItemVo> items = new ArrayList<SearchItemVo>();
        if (response == null || response.getHits() == null) {
            return items;
        }
        for (SearchHit hit : response.getHits().getHits()) {
            if (hit == null) {
                continue;
            }
            Map<String, Object> source = hit.getSourceAsMap();
            if (source == null) {
                source = Collections.emptyMap();
            }
            SearchItemVo item = new SearchItemVo();
            String skuId = string(source.get("sku_id"));
            item.setSkuId(StringUtils.hasText(skuId) ? skuId : hit.getId());
            item.setSpuId(string(source.get("spu_id")));
            item.setTitle(string(source.get("title")));
            item.setBrandName(string(source.get("brand_name")));
            item.setCategoryName(string(source.get("category_name")));
            item.setImageUrl(string(source.get("main_pic")));

            Object price = source.get("price");
            item.setPriceFen(price instanceof Number
                    ? Math.round(((Number) price).doubleValue() * 100.0) : 0L);
            Object inStock = source.get("in_stock");
            if (inStock instanceof Boolean) {
                item.setInStock((Boolean) inStock);
            } else if (inStock != null) {
                item.setInStock(Boolean.parseBoolean(String.valueOf(inStock)));
            }
            item.setScore(hit.getScore());

            HighlightField titleHighlight = hit.getHighlightFields().get("title");
            if (titleHighlight != null && titleHighlight.fragments() != null
                    && titleHighlight.fragments().length > 0) {
                item.setHighlightTitle(titleHighlight.fragments()[0].string());
            }
            items.add(item);
        }
        return items;
    }

    private FacetsVo parseAggregations(SearchResponse response) {
        FacetsVo facets = new FacetsVo();
        if (response == null || response.getAggregations() == null) {
            return facets;
        }
        Terms brands = response.getAggregations().get("brands");
        if (brands != null) {
            List<FacetsVo.Bucket> buckets = new ArrayList<FacetsVo.Bucket>();
            for (Terms.Bucket bucket : brands.getBuckets()) {
                buckets.add(new FacetsVo.Bucket(bucket.getKeyAsString(), bucket.getDocCount()));
            }
            facets.setBrands(buckets);
        }
        Terms categories = response.getAggregations().get("categories");
        if (categories != null) {
            List<FacetsVo.Bucket> buckets = new ArrayList<FacetsVo.Bucket>();
            for (Terms.Bucket bucket : categories.getBuckets()) {
                buckets.add(new FacetsVo.Bucket(bucket.getKeyAsString(), bucket.getDocCount()));
            }
            facets.setCategories(buckets);
        }
        return facets;
    }

    private boolean hasRecognizedEntities(List<NerEntityDto> entities) {
        if (entities == null) {
            return false;
        }
        for (NerEntityDto entity : entities) {
            if (entity == null || !StringUtils.hasText(entity.searchText())
                    || !StringUtils.hasText(entity.getLabel())) {
                continue;
            }
            String label = entity.getLabel();
            if (LABEL_BRAND.equals(label) || LABEL_CATEGORY.equals(label)
                    || LABEL_MODIFIER.equals(label) || LABEL_ATTRIBUTE.equals(label)
                    || LABEL_MODEL.equals(label) || nerFieldMapping.getMapping(label) != null) {
                return true;
            }
        }
        return false;
    }

    static String removeRecognizedParts(String query, List<NerEntityDto> entities) {
        if (!StringUtils.hasText(query) || entities == null || entities.isEmpty()) {
            return query == null ? "" : query.trim();
        }
        List<NerEntityDto> sorted = new ArrayList<NerEntityDto>();
        for (NerEntityDto entity : entities) {
            if (entity != null) {
                sorted.add(entity);
            }
        }
        sorted.sort(Comparator.comparingInt(NerEntityDto::getStart));

        StringBuilder remaining = new StringBuilder();
        int position = 0;
        for (NerEntityDto entity : sorted) {
            int start = Math.max(0, Math.min(entity.getStart(), query.length()));
            int end = Math.max(start, Math.min(entity.getEnd(), query.length()));
            if (end <= position) {
                continue;
            }
            if (start > position) {
                remaining.append(query, position, start);
            }
            position = end;
        }
        if (position < query.length()) {
            remaining.append(query, position, query.length());
        }
        return remaining.toString().trim();
    }

    private static long totalHits(SearchResponse response) {
        if (response == null || response.getHits() == null
                || response.getHits().getTotalHits() == null) {
            return 0L;
        }
        return response.getHits().getTotalHits().value;
    }

    private static boolean isRelevanceSort(SearchQueryDto query) {
        return query.getSort() == null || "RELEVANCE".equals(query.getSort().name());
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
