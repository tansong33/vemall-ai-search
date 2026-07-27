package cn.vetech.ai.search.fccapi.api.debug;

import cn.vetech.ai.search.fccapi.api.search.SearchResponse;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 搜索链路各实际执行阶段的调试响应。
 */
@Data
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
    @Data
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
        @Data
        public static class Entity implements Serializable {

            private String text;
            private String label;
            private int start;
            private int end;
            private Float confidence;
            private String normalizedText;
            private String normalizedId;
        }
    }

    /**
     * 查询理解阶段。
     */
    @Data
    public static class QueryStage implements Serializable {

        private String rewrittenQuery;
        private List<String> synonyms;
        private List<String> analyzedTokens;
        private long costMs;
    }

    /**
     * Elasticsearch 查询阶段。
     */
    @Data
    public static class EsStage implements Serializable {

        private List<String> mustClauses;
        private List<String> shouldClauses;
        private List<String> mustNotClauses;
        private String dsl;
        private long totalHits;
        private long costMs;
    }

    /**
     * 缓存查询阶段。
     */
    @Data
    public static class CacheStage implements Serializable {

        private String status;
        private String key;
        private long lookupMs;
    }

    /**
     * 搜索链路耗时。
     */
    @Data
    public static class TimingStage implements Serializable {

        private long totalMs;
        private long cacheMs;
        private long nerMs;
        private long queryMs;
        private long esMs;
    }
}
