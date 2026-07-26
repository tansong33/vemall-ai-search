package cn.vetech.ai.search.fccapi.api.debug;

import cn.vetech.ai.search.fccapi.api.search.SearchResponse;
import java.io.Serializable;
import java.util.List;

/**
 * 搜索链路各实际执行阶段的调试响应。
 */
public class SearchDebugResponse implements Serializable {

    private SearchResponse result;
    private NerStage ner;
    private QueryStage query;
    private EsStage es;
    private CacheStage cache;
    private TimingStage timing;

    /**
     * 实体识别阶段。
     */
    public static class NerStage implements Serializable {

        private List<Entity> entities;
        private String provider;
        private String mode;
        private String modelVersion;
        private boolean modelReady;
        private long costMs;

        /**
         * 实体识别结果中的单个实体。
         */
        public static class Entity implements Serializable {

            private String text;
            private String label;
            private int start;
            private int end;
            private Float confidence;
            private String normalizedText;
            private String normalizedId;

            public String getText() {
                return text;
            }

            public void setText(String text) {
                this.text = text;
            }

            public String getLabel() {
                return label;
            }

            public void setLabel(String label) {
                this.label = label;
            }

            public int getStart() {
                return start;
            }

            public void setStart(int start) {
                this.start = start;
            }

            public int getEnd() {
                return end;
            }

            public void setEnd(int end) {
                this.end = end;
            }

            public Float getConfidence() {
                return confidence;
            }

            public void setConfidence(Float confidence) {
                this.confidence = confidence;
            }

            public String getNormalizedText() {
                return normalizedText;
            }

            public void setNormalizedText(String normalizedText) {
                this.normalizedText = normalizedText;
            }

            public String getNormalizedId() {
                return normalizedId;
            }

            public void setNormalizedId(String normalizedId) {
                this.normalizedId = normalizedId;
            }
        }

        public List<Entity> getEntities() {
            return entities;
        }

        public void setEntities(List<Entity> entities) {
            this.entities = entities;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public String getModelVersion() {
            return modelVersion;
        }

        public void setModelVersion(String modelVersion) {
            this.modelVersion = modelVersion;
        }

        public boolean isModelReady() {
            return modelReady;
        }

        public void setModelReady(boolean modelReady) {
            this.modelReady = modelReady;
        }

        public long getCostMs() {
            return costMs;
        }

        public void setCostMs(long costMs) {
            this.costMs = costMs;
        }
    }

    /**
     * 查询理解阶段。
     */
    public static class QueryStage implements Serializable {

        private String rewrittenQuery;
        private List<String> synonyms;
        private List<String> analyzedTokens;
        private long costMs;

        public String getRewrittenQuery() {
            return rewrittenQuery;
        }

        public void setRewrittenQuery(String rewrittenQuery) {
            this.rewrittenQuery = rewrittenQuery;
        }

        public List<String> getSynonyms() {
            return synonyms;
        }

        public void setSynonyms(List<String> synonyms) {
            this.synonyms = synonyms;
        }

        public List<String> getAnalyzedTokens() {
            return analyzedTokens;
        }

        public void setAnalyzedTokens(List<String> analyzedTokens) {
            this.analyzedTokens = analyzedTokens;
        }

        public long getCostMs() {
            return costMs;
        }

        public void setCostMs(long costMs) {
            this.costMs = costMs;
        }
    }

    /**
     * Elasticsearch 查询阶段。
     */
    public static class EsStage implements Serializable {

        private List<String> mustClauses;
        private List<String> shouldClauses;
        private List<String> mustNotClauses;
        private String dsl;
        private long totalHits;
        private long costMs;

        public List<String> getMustClauses() {
            return mustClauses;
        }

        public void setMustClauses(List<String> mustClauses) {
            this.mustClauses = mustClauses;
        }

        public List<String> getShouldClauses() {
            return shouldClauses;
        }

        public void setShouldClauses(List<String> shouldClauses) {
            this.shouldClauses = shouldClauses;
        }

        public List<String> getMustNotClauses() {
            return mustNotClauses;
        }

        public void setMustNotClauses(List<String> mustNotClauses) {
            this.mustNotClauses = mustNotClauses;
        }

        public String getDsl() {
            return dsl;
        }

        public void setDsl(String dsl) {
            this.dsl = dsl;
        }

        public long getTotalHits() {
            return totalHits;
        }

        public void setTotalHits(long totalHits) {
            this.totalHits = totalHits;
        }

        public long getCostMs() {
            return costMs;
        }

        public void setCostMs(long costMs) {
            this.costMs = costMs;
        }
    }

    /**
     * 缓存查询阶段。
     */
    public static class CacheStage implements Serializable {

        private String status;
        private String key;
        private long lookupMs;

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public long getLookupMs() {
            return lookupMs;
        }

        public void setLookupMs(long lookupMs) {
            this.lookupMs = lookupMs;
        }
    }

    /**
     * 搜索链路耗时。
     */
    public static class TimingStage implements Serializable {

        private long totalMs;
        private long cacheMs;
        private long nerMs;
        private long queryMs;
        private long esMs;

        public long getTotalMs() {
            return totalMs;
        }

        public void setTotalMs(long totalMs) {
            this.totalMs = totalMs;
        }

        public long getCacheMs() {
            return cacheMs;
        }

        public void setCacheMs(long cacheMs) {
            this.cacheMs = cacheMs;
        }

        public long getNerMs() {
            return nerMs;
        }

        public void setNerMs(long nerMs) {
            this.nerMs = nerMs;
        }

        public long getQueryMs() {
            return queryMs;
        }

        public void setQueryMs(long queryMs) {
            this.queryMs = queryMs;
        }

        public long getEsMs() {
            return esMs;
        }

        public void setEsMs(long esMs) {
            this.esMs = esMs;
        }
    }

    public SearchResponse getResult() {
        return result;
    }

    public void setResult(SearchResponse result) {
        this.result = result;
    }

    public NerStage getNer() {
        return ner;
    }

    public void setNer(NerStage ner) {
        this.ner = ner;
    }

    public QueryStage getQuery() {
        return query;
    }

    public void setQuery(QueryStage query) {
        this.query = query;
    }

    public EsStage getEs() {
        return es;
    }

    public void setEs(EsStage es) {
        this.es = es;
    }

    public CacheStage getCache() {
        return cache;
    }

    public void setCache(CacheStage cache) {
        this.cache = cache;
    }

    public TimingStage getTiming() {
        return timing;
    }

    public void setTiming(TimingStage timing) {
        this.timing = timing;
    }
}
