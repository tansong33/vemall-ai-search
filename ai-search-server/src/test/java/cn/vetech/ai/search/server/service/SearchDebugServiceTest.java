package cn.vetech.ai.search.server.service;

import cn.vetech.ai.search.server.config.NerProperties;
import cn.vetech.ai.search.server.dao.SearchCacheDao;
import cn.vetech.ai.search.server.dao.TextAnalyzeDao;
import cn.vetech.ai.search.server.service.dto.SearchQueryDto;
import cn.vetech.ai.search.server.service.ner.NerModelClient;
import cn.vetech.ai.search.server.service.ner.NerRecognizer;
import cn.vetech.ai.search.server.service.vo.EsAnalyzeVo;
import cn.vetech.ai.search.server.service.vo.SearchDebugVo;
import cn.vetech.ai.search.server.service.vo.SearchResultVo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

class SearchDebugServiceTest {

    /** function_score 包裹的真实结构：品牌 must、品类 should、配件排除 must_not。 */
    private static final String DSL = "{\"query\":{\"function_score\":{\"query\":{\"bool\":{"
            + "\"must\":[{\"match\":{\"brand_name\":{\"query\":\"华为\"}}}],"
            + "\"should\":[{\"match_phrase\":{\"title\":{\"query\":\"手机\"}}}],"
            + "\"must_not\":[{\"match_phrase\":{\"title\":{\"query\":\"手机壳\"}}},"
            + "{\"match_phrase\":{\"title\":{\"query\":\"手机膜\"}}}]}}}}}";

    private SearchService searchService;
    private SearchCacheDao cacheDao;
    private SearchDebugService service;

    @BeforeEach
    void setUp() {
        searchService = Mockito.mock(SearchService.class);
        cacheDao = Mockito.mock(SearchCacheDao.class);
        NerRecognizer recognizer = Mockito.mock(NerRecognizer.class);
        NerModelClient modelClient = Mockito.mock(NerModelClient.class);
        TextAnalyzeDao analyzeDao = Mockito.mock(TextAnalyzeDao.class);

        Mockito.when(recognizer.provider()).thenReturn("dictionary");
        Mockito.when(recognizer.modelVersion()).thenReturn("none");
        Mockito.when(modelClient.isReady()).thenReturn(false);
        Mockito.when(analyzeDao.analyze(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(new EsAnalyzeVo());
        Mockito.when(cacheDao.isAvailable()).thenReturn(true);

        SearchResultVo result = new SearchResultVo();
        result.setEsDsl(DSL);
        result.setRawTotal(42);
        Mockito.when(searchService.search(Mockito.any(SearchQueryDto.class),
                Mockito.any(DegradeContext.class))).thenReturn(result);
        Mockito.when(searchService.buildCacheKey(Mockito.any(SearchQueryDto.class)))
                .thenReturn("search:result:key");

        service = new SearchDebugService(searchService, recognizer, modelClient,
                analyzeDao, cacheDao, new NerProperties(), new EsDslExplainer(new ObjectMapper()));
    }

    @Test
    void clauseDescriptionsComeFromTheDslThatWasActuallyExecuted() {
        SearchDebugVo vo = service.pipeline(query("华为手机"));

        assertThat(vo.getEs().getMustNotClauses())
                .containsExactly("match_phrase(title=手机壳)", "match_phrase(title=手机膜)");
        // 每条排除词都必须能在真实 DSL 里找到，杜绝调试页与实际执行漂移
        for (String clause : vo.getEs().getMustNotClauses()) {
            String term = clause.substring(clause.indexOf('=') + 1, clause.length() - 1);
            assertThat(vo.getEs().getDsl()).contains(term);
        }
        assertThat(vo.getEs().getMustClauses()).containsExactly("match(brand_name=华为)");
        assertThat(vo.getEs().getShouldClauses()).containsExactly("match_phrase(title=手机)");
    }

    @Test
    void allSixStagesArePopulated() {
        SearchDebugVo vo = service.pipeline(query("华为手机"));

        assertThat(vo.getResult()).isNotNull();
        assertThat(vo.getNer()).isNotNull();
        assertThat(vo.getQuery()).isNotNull();
        assertThat(vo.getEs().getTotalHits()).isEqualTo(42);
        assertThat(vo.getCache().getKey()).isEqualTo("search:result:key");
        assertThat(vo.getTiming()).isNotNull();
    }

    @Test
    void cacheStatusIsUnavailableRatherThanMissWhenRedisIsDown() {
        Mockito.when(cacheDao.isAvailable()).thenReturn(false);

        SearchDebugVo vo = service.pipeline(query("华为手机"));

        assertThat(vo.getCache().getStatus()).isEqualTo("UNAVAILABLE");
        assertThat(vo.getDegradeReasons()).contains(DegradeContext.REDIS_UNAVAILABLE);
    }

    @Test
    void debugAlwaysRequestsTheRealDsl() {
        SearchQueryDto query = query("华为手机");
        service.pipeline(query);
        assertThat(query.isIncludeEsDsl()).isTrue();
    }

    private static SearchQueryDto query(String text) {
        SearchQueryDto query = new SearchQueryDto();
        query.setQuery(text);
        return query;
    }
}
