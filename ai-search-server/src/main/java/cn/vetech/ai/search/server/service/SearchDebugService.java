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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索链路可视化：取数与组装，不重新推导任何查询条件。
 *
 * <p>ES 条件描述直接从真实执行的 DSL 解析而来，而不是另写一套构造逻辑 ——
 * 两套代码一旦漂移，调试页展示的就是假象。</p>
 */
@Service
public class SearchDebugService {

    private static final Logger log = LoggerFactory.getLogger(SearchDebugService.class);

    private final SearchService searchService;
    private final NerRecognizer nerRecognizer;
    private final NerModelClient nerModelClient;
    private final TextAnalyzeDao textAnalyzeDao;
    private final SearchCacheDao cacheDao;
    private final NerProperties nerProperties;
    private final EsDslExplainer dslExplainer;

    public SearchDebugService(SearchService searchService, NerRecognizer nerRecognizer,
                              NerModelClient nerModelClient, TextAnalyzeDao textAnalyzeDao,
                              SearchCacheDao cacheDao, NerProperties nerProperties,
                              EsDslExplainer dslExplainer) {
        this.searchService = searchService;
        this.nerRecognizer = nerRecognizer;
        this.nerModelClient = nerModelClient;
        this.textAnalyzeDao = textAnalyzeDao;
        this.cacheDao = cacheDao;
        this.nerProperties = nerProperties;
        this.dslExplainer = dslExplainer;
    }

    public SearchDebugVo pipeline(SearchQueryDto query) {
        long started = System.currentTimeMillis();
        SearchDebugVo vo = new SearchDebugVo();
        query.setIncludeEsDsl(true);

        fillAnalyzedTokens(vo, query.getQuery());

        // 走真正的 SearchService，这样缓存命中与降级行为和生产完全一致
        DegradeContext degrade = new DegradeContext();
        SearchResultVo result = searchService.search(query, degrade);
        vo.setResult(result);

        fillNerStage(vo, query);
        fillQueryStage(vo, query);
        fillEsStage(vo, result);
        fillCacheStage(vo, query, degrade);

        vo.setDegraded(degrade.isDegraded());
        vo.setDegradeReasons(new ArrayList<String>(degrade.getReasons()));

        SearchDebugVo.TimingStage timing = vo.getTiming();
        timing.setTotalMs(System.currentTimeMillis() - started);
        timing.setCacheMs(vo.getCache().getLookupMs());
        timing.setNerMs(vo.getNer().getCostMs());
        timing.setQueryMs(vo.getQuery().getCostMs());
        timing.setEsMs(vo.getEs().getCostMs());
        return vo;
    }

    private void fillAnalyzedTokens(SearchDebugVo vo, String text) {
        try {
            EsAnalyzeVo analyze = textAnalyzeDao.analyze(text, "ik_max_word");
            List<String> tokens = new ArrayList<String>();
            if (analyze != null && analyze.getTokens() != null) {
                for (EsAnalyzeVo.TokenInfo token : analyze.getTokens()) {
                    tokens.add(token.getTerm());
                }
            }
            vo.getQuery().setAnalyzedTokens(tokens);
        } catch (RuntimeException e) {
            log.warn("ES 分词失败，调试页跳过该段: {}", e.getMessage());
        }
    }

    private void fillNerStage(SearchDebugVo vo, SearchQueryDto query) {
        SearchDebugVo.NerStage ner = vo.getNer();
        ner.setEntities(query.getEntities());
        ner.setProvider(nerRecognizer.provider());
        ner.setModelVersion(nerRecognizer.modelVersion());
        ner.setMode(nerProperties.getMode());
        ner.setModelReady(nerModelClient.isReady());
        ner.setCostMs(0);
    }

    private void fillQueryStage(SearchDebugVo vo, SearchQueryDto query) {
        SearchDebugVo.QueryStage stage = vo.getQuery();
        stage.setRewrittenQuery(query.getRewrittenQuery());
        stage.setSynonyms(query.getSynonyms());
    }

    private void fillEsStage(SearchDebugVo vo, SearchResultVo result) {
        SearchDebugVo.EsStage es = vo.getEs();
        es.setDsl(result.getEsDsl());
        es.setTotalHits(result.getRawTotal());
        es.setCostMs(result.getCostMs());
        EsDslExplainer.Clauses clauses = dslExplainer.explain(result.getEsDsl());
        es.setMustClauses(clauses.getMust());
        es.setShouldClauses(clauses.getShould());
        es.setMustNotClauses(clauses.getMustNot());
    }

    private void fillCacheStage(SearchDebugVo vo, SearchQueryDto query, DegradeContext degrade) {
        SearchDebugVo.CacheStage cache = vo.getCache();
        boolean available = cacheDao.isAvailable();
        // UNAVAILABLE 与 MISS 含义不同：前者是 Redis 挂了，后者是正常未命中
        cache.setStatus(available ? degrade.getCacheStatus() : "UNAVAILABLE");
        cache.setLookupMs(degrade.getCacheLookupMs());
        try {
            cache.setKey(searchService.buildCacheKey(query));
        } catch (RuntimeException e) {
            log.warn("构造缓存 key 失败: {}", e.getMessage());
        }
        if (!available) {
            degrade.add(DegradeContext.REDIS_UNAVAILABLE);
        }
    }
}
