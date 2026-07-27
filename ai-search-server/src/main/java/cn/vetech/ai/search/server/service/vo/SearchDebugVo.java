package cn.vetech.ai.search.server.service.vo;

import cn.vetech.ai.search.server.service.dto.NerEntityDto;

import java.util.ArrayList;
import java.util.List;

/** 搜索链路可视化结果。字段与 fccapi 的 SearchDebugResponse 一一对应，转换在 rest 层做。 */
public class SearchDebugVo {

    private SearchResultVo result = new SearchResultVo();
    private NerStage ner = new NerStage();
    private QueryStage query = new QueryStage();
    private EsStage es = new EsStage();
    private CacheStage cache = new CacheStage();
    private TimingStage timing = new TimingStage();
    private boolean degraded;
    private List<String> degradeReasons = new ArrayList<String>();

    public static class NerStage {
        private List<NerEntityDto> entities = new ArrayList<NerEntityDto>();
        private String provider;
        private String mode;
        private String modelVersion;
        private boolean modelReady;
        private long costMs;

        public List<NerEntityDto> getEntities() { return entities; }
        public void setEntities(List<NerEntityDto> entities) { this.entities = entities; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public String getModelVersion() { return modelVersion; }
        public void setModelVersion(String modelVersion) { this.modelVersion = modelVersion; }
        public boolean isModelReady() { return modelReady; }
        public void setModelReady(boolean modelReady) { this.modelReady = modelReady; }
        public long getCostMs() { return costMs; }
        public void setCostMs(long costMs) { this.costMs = costMs; }
    }

    public static class QueryStage {
        private String rewrittenQuery;
        private List<String> synonyms = new ArrayList<String>();
        private List<String> analyzedTokens = new ArrayList<String>();
        private long costMs;

        public String getRewrittenQuery() { return rewrittenQuery; }
        public void setRewrittenQuery(String rewrittenQuery) { this.rewrittenQuery = rewrittenQuery; }
        public List<String> getSynonyms() { return synonyms; }
        public void setSynonyms(List<String> synonyms) { this.synonyms = synonyms; }
        public List<String> getAnalyzedTokens() { return analyzedTokens; }
        public void setAnalyzedTokens(List<String> analyzedTokens) { this.analyzedTokens = analyzedTokens; }
        public long getCostMs() { return costMs; }
        public void setCostMs(long costMs) { this.costMs = costMs; }
    }

    public static class EsStage {
        private List<String> mustClauses = new ArrayList<String>();
        private List<String> shouldClauses = new ArrayList<String>();
        private List<String> mustNotClauses = new ArrayList<String>();
        private String dsl;
        private long totalHits;
        private long costMs;

        public List<String> getMustClauses() { return mustClauses; }
        public void setMustClauses(List<String> mustClauses) { this.mustClauses = mustClauses; }
        public List<String> getShouldClauses() { return shouldClauses; }
        public void setShouldClauses(List<String> shouldClauses) { this.shouldClauses = shouldClauses; }
        public List<String> getMustNotClauses() { return mustNotClauses; }
        public void setMustNotClauses(List<String> mustNotClauses) { this.mustNotClauses = mustNotClauses; }
        public String getDsl() { return dsl; }
        public void setDsl(String dsl) { this.dsl = dsl; }
        public long getTotalHits() { return totalHits; }
        public void setTotalHits(long totalHits) { this.totalHits = totalHits; }
        public long getCostMs() { return costMs; }
        public void setCostMs(long costMs) { this.costMs = costMs; }
    }

    public static class CacheStage {
        private String status;
        private String key;
        private long lookupMs;

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public long getLookupMs() { return lookupMs; }
        public void setLookupMs(long lookupMs) { this.lookupMs = lookupMs; }
    }

    public static class TimingStage {
        private long totalMs;
        private long cacheMs;
        private long nerMs;
        private long queryMs;
        private long esMs;

        public long getTotalMs() { return totalMs; }
        public void setTotalMs(long totalMs) { this.totalMs = totalMs; }
        public long getCacheMs() { return cacheMs; }
        public void setCacheMs(long cacheMs) { this.cacheMs = cacheMs; }
        public long getNerMs() { return nerMs; }
        public void setNerMs(long nerMs) { this.nerMs = nerMs; }
        public long getQueryMs() { return queryMs; }
        public void setQueryMs(long queryMs) { this.queryMs = queryMs; }
        public long getEsMs() { return esMs; }
        public void setEsMs(long esMs) { this.esMs = esMs; }
    }

    public SearchResultVo getResult() { return result; }
    public void setResult(SearchResultVo result) { this.result = result; }
    public NerStage getNer() { return ner; }
    public void setNer(NerStage ner) { this.ner = ner; }
    public QueryStage getQuery() { return query; }
    public void setQuery(QueryStage query) { this.query = query; }
    public EsStage getEs() { return es; }
    public void setEs(EsStage es) { this.es = es; }
    public CacheStage getCache() { return cache; }
    public void setCache(CacheStage cache) { this.cache = cache; }
    public TimingStage getTiming() { return timing; }
    public void setTiming(TimingStage timing) { this.timing = timing; }
    public boolean isDegraded() { return degraded; }
    public void setDegraded(boolean degraded) { this.degraded = degraded; }
    public List<String> getDegradeReasons() { return degradeReasons; }
    public void setDegradeReasons(List<String> degradeReasons) { this.degradeReasons = degradeReasons; }
}
