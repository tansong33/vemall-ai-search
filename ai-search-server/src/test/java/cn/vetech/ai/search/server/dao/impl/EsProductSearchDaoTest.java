package cn.vetech.ai.search.server.dao.impl;

import cn.vetech.ai.search.fccapi.api.search.SearchRequest;
import cn.vetech.ai.search.server.config.NerFieldMapping;
import cn.vetech.ai.search.server.config.SearchExclusionConfig;
import cn.vetech.ai.search.server.config.SearchProperties;
import cn.vetech.ai.search.server.dao.SearchDataAccessException;
import cn.vetech.ai.search.server.service.dto.NerEntityDto;
import cn.vetech.ai.search.server.service.dto.SearchQueryDto;
import cn.vetech.ai.search.server.service.vo.SearchItemVo;
import cn.vetech.ai.search.server.service.vo.SearchResultVo;
import org.apache.lucene.search.TotalHits;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchResponseSections;
import org.elasticsearch.action.search.ShardSearchFailure;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.text.Text;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightField;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EsProductSearchDaoTest {

    private SearchProperties properties;
    private NerFieldMapping fieldMapping;
    private SearchExclusionConfig exclusionConfig;

    @BeforeEach
    void setUp() {
        properties = new SearchProperties();
        properties.getSearch().setMinScore(2.5f);
        fieldMapping = new NerFieldMapping();
        fieldMapping.initDefaults();
        exclusionConfig = mock(SearchExclusionConfig.class);
        when(exclusionConfig.buildExclusionTerms("手机"))
                .thenReturn(Arrays.asList("手机壳", "手机保护壳"));
    }

    @Test
    void buildsCompleteDslForEntitiesFiltersSortAndPaging() {
        SearchQueryDto query = new SearchQueryDto();
        query.setQuery("华为手机Mate60轻薄红色新品");
        query.setRewrittenQuery("华为手机 Mate60 轻薄 红色 新品");
        query.setSynonyms(Collections.singletonList("智能手机"));
        query.setEntities(Arrays.asList(
                normalizedEntity("华为", "BRAND", 0, 2, "brand-42", "brand_id"),
                entity("手机", "CATEGORY", 2, 4),
                entity("Mate60", "MODEL", 4, 10),
                entity("轻薄", "MODIFIER", 10, 12),
                entity("红色", "ATTRIBUTE", 12, 14),
                entity("新品", "STYLE", 14, 16)
        ));
        query.setPage(3);
        query.setPageSize(7);
        query.setSort(SearchRequest.Sort.PRICE_ASC);

        SearchRequest.Filters filters = new SearchRequest.Filters();
        filters.setBrands(Collections.singletonList("华为"));
        filters.setCategories(Collections.singletonList("手机"));
        filters.setMinPriceFen(12345L);
        filters.setMaxPriceFen(67890L);
        filters.setInStock(Boolean.TRUE);
        query.setFilters(filters);

        SearchSourceBuilder source = dao().buildSource(query, true);
        String dsl = source.toString();

        assertThat(source.from()).isEqualTo(14);
        assertThat(source.size()).isEqualTo(7);
        assertThat(source.minScore()).isEqualTo(2.5f);
        assertThat(dsl)
                .contains("\"brand_name\"", "\"category_name\"", "\"model_no\"",
                        "\"tags\"", "\"attributes\"")
                .contains("\"boost\":50.0", "\"boost\":40.0", "\"boost\":30.0",
                        "\"boost\":20.0", "\"boost\":10.0", "\"boost\":5.0",
                        "\"boost\":6.0")
                .contains("\"brand_id\"", "\"brand-42\"", "\"boost\":60.0")
                .contains("\"智能手机\"", "\"boost\":0.5")
                .contains("\"must_not\"", "手机壳", "手机保护壳")
                .contains("\"brand_name.keyword\"", "\"category_name.keyword\"")
                .contains("\"from\":123.45", "\"to\":678.9", "\"in_stock\"")
                .contains("\"price\"", "\"order\":\"asc\"")
                .contains("\"pre_tags\":[\"<em>\"]", "\"post_tags\":[\"</em>\"]")
                .contains("\"brands\"", "\"categories\"");
    }

    @Test
    void buildsRelevanceFunctionScoreForQueryWithoutEntities() {
        SearchQueryDto query = new SearchQueryDto();
        query.setQuery("无线耳机");
        query.setEntities(Collections.<NerEntityDto>emptyList());

        String dsl = dao().buildSource(query, true).toString();

        assertThat(dsl)
                .contains("\"function_score\"", "\"sales_count\"", "\"factor\":0.3",
                        "\"rating\"", "\"factor\":0.2", "\"score_mode\":\"sum\"",
                        "\"boost_mode\":\"sum\"")
                .contains("\"title\"", "无线耳机");
    }

    @Test
    void requiresNerShouldMatchForPureBrandAndCategoryQuery() {
        SearchQueryDto query = new SearchQueryDto();
        query.setQuery("华为手机");
        query.setEntities(Arrays.asList(
                entity("华为", "BRAND", 0, 2),
                entity("手机", "CATEGORY", 2, 4)
        ));
        query.setSort(SearchRequest.Sort.PRICE_ASC);

        BoolQueryBuilder boolQuery = (BoolQueryBuilder) dao().buildSource(query, true).query();

        assertThat(boolQuery.minimumShouldMatch()).isEqualTo("1");
    }

    @Test
    void doesNotMakeNormalizedIdMandatoryForPureBrandQuery() {
        SearchQueryDto query = new SearchQueryDto();
        query.setQuery("华为");
        query.setEntities(Collections.singletonList(
                normalizedEntity("华为", "BRAND", 0, 2, "brand-42", "brand_id")));
        query.setSort(SearchRequest.Sort.PRICE_ASC);

        BoolQueryBuilder boolQuery = (BoolQueryBuilder) dao().buildSource(query, true).query();

        assertThat(boolQuery.minimumShouldMatch()).isNull();
        assertThat(boolQuery.should()).hasSize(1);
    }

    @Test
    void retriesTitleOnlyAndReturnsFallbackDslAndMappedHit() {
        SearchHit hit = hit();
        StubDao dao = new StubDao(properties, fieldMapping, exclusionConfig,
                response(), response(hit));
        SearchQueryDto query = new SearchQueryDto();
        query.setQuery("华为手机");
        query.setEntities(Collections.singletonList(entity("华为", "BRAND", 0, 2)));
        query.setIncludeEsDsl(true);

        SearchResultVo result = dao.search(query);

        assertThat(dao.requests).hasSize(2);
        String firstQuery = dao.requests.get(0).source().query().toString();
        String fallbackQuery = dao.requests.get(1).source().query().toString();
        assertThat(firstQuery).contains("\"brand_name\"", "华为");
        assertThat(fallbackQuery).contains("\"title\"", "华为手机")
                .doesNotContain("\"brand_name\"");
        assertThat(result.getEsDsl()).isEqualTo(dao.requests.get(1).source().toString());
        assertThat(result.getTotal()).isEqualTo(1L);
        assertThat(result.getRawTotal()).isEqualTo(1L);
        assertThat(result.getItems()).hasSize(1);

        SearchItemVo item = result.getItems().get(0);
        assertThat(item.getSkuId()).isEqualTo("sku-1");
        assertThat(item.getSpuId()).isEqualTo("spu-9");
        assertThat(item.getTitle()).isEqualTo("Huawei Phone");
        assertThat(item.getHighlightTitle()).isEqualTo("<em>Huawei</em> Phone");
        assertThat(item.getBrandName()).isEqualTo("Huawei");
        assertThat(item.getCategoryName()).isEqualTo("Phone");
        assertThat(item.getImageUrl()).isEqualTo("https://img/p.jpg");
        assertThat(item.getPriceFen()).isEqualTo(1235L);
        assertThat(item.getInStock()).isTrue();
        assertThat(item.getScore()).isEqualTo(7.25f);
    }

    @Test
    void wrapsIoFailureAsSearchDataAccessException() {
        StubDao dao = new StubDao(properties, fieldMapping, exclusionConfig);
        dao.failure = new IOException("ES unavailable");
        SearchQueryDto query = new SearchQueryDto();
        query.setQuery("手机");

        assertThatThrownBy(() -> dao.search(query))
                .isInstanceOf(SearchDataAccessException.class)
                .hasCauseInstanceOf(IOException.class)
                .hasMessage("Elasticsearch search failed");
    }

    private EsProductSearchDao dao() {
        return new EsProductSearchDao(null, properties, fieldMapping, exclusionConfig);
    }

    private static NerEntityDto entity(String text, String label, int start, int end) {
        NerEntityDto entity = new NerEntityDto();
        entity.setText(text);
        entity.setLabel(label);
        entity.setStart(start);
        entity.setEnd(end);
        return entity;
    }

    private static NerEntityDto normalizedEntity(String text, String label, int start, int end,
                                                  String normalizedId, String normalizedType) {
        NerEntityDto entity = entity(text, label, start, end);
        entity.setNormalizedText(text);
        entity.setNormalizedId(normalizedId);
        entity.setNormalizedType(normalizedType);
        return entity;
    }

    private static SearchHit hit() {
        SearchHit hit = new SearchHit(0, "hit-id", new Text("_doc"),
                Collections.emptyMap(), Collections.emptyMap());
        hit.sourceRef(new BytesArray("{"
                + "\"sku_id\":\"sku-1\","
                + "\"spu_id\":\"spu-9\","
                + "\"title\":\"Huawei Phone\","
                + "\"brand_name\":\"Huawei\","
                + "\"category_name\":\"Phone\","
                + "\"main_pic\":\"https://img/p.jpg\","
                + "\"price\":12.345,"
                + "\"in_stock\":true"
                + "}"));
        hit.score(7.25f);
        Map<String, HighlightField> highlights = new HashMap<String, HighlightField>();
        highlights.put("title", new HighlightField("title",
                new Text[]{new Text("<em>Huawei</em> Phone")}));
        hit.highlightFields(highlights);
        return hit;
    }

    private static SearchResponse response(SearchHit... hits) {
        float maxScore = hits.length == 0 ? Float.NaN : hits[0].getScore();
        SearchHits searchHits = new SearchHits(hits,
                new TotalHits(hits.length, TotalHits.Relation.EQUAL_TO), maxScore);
        SearchResponseSections sections = new SearchResponseSections(
                searchHits, null, null, false, null, null, 1);
        return new SearchResponse(sections, null, 1, 1, 0, 5L,
                ShardSearchFailure.EMPTY_ARRAY, SearchResponse.Clusters.EMPTY);
    }

    private static final class StubDao extends EsProductSearchDao {

        private final Deque<SearchResponse> responses = new ArrayDeque<SearchResponse>();
        private final List<org.elasticsearch.action.search.SearchRequest> requests =
                new ArrayList<org.elasticsearch.action.search.SearchRequest>();
        private IOException failure;

        private StubDao(SearchProperties properties,
                        NerFieldMapping fieldMapping,
                        SearchExclusionConfig exclusionConfig,
                        SearchResponse... responses) {
            super(null, properties, fieldMapping, exclusionConfig);
            Collections.addAll(this.responses, responses);
        }

        @Override
        protected SearchResponse executeSearch(
                org.elasticsearch.action.search.SearchRequest request) throws IOException {
            requests.add(request);
            if (failure != null) {
                throw failure;
            }
            return responses.removeFirst();
        }
    }
}
