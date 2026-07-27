package cn.vetech.ai.search.server.service.vo;

import cn.vetech.ai.search.server.service.dto.NerEntityDto;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 搜索链路可视化结果。字段与 fccapi 的 SearchDebugResponse 一一对应，转换在 rest 层做。 */
@Data
public class SearchDebugVo {

    private SearchResultVo result = new SearchResultVo();
    private NerStage ner = new NerStage();
    private QueryStage query = new QueryStage();
    private EsStage es = new EsStage();
    private CacheStage cache = new CacheStage();
    private TimingStage timing = new TimingStage();
    private boolean degraded;
    private List<String> degradeReasons = new ArrayList<>();

    @Data
    public static class NerStage {
        private List<NerEntityDto> entities = new ArrayList<>();
        private String provider;
        private String mode;
        private String modelVersion;
        private boolean modelReady;
        private long costMs;
    }

    @Data
    public static class QueryStage {
        private String rewrittenQuery;
        private List<String> synonyms = new ArrayList<>();
        private List<String> analyzedTokens = new ArrayList<>();
        private long costMs;
    }

    @Data
    public static class EsStage {
        private List<String> mustClauses = new ArrayList<>();
        private List<String> shouldClauses = new ArrayList<>();
        private List<String> mustNotClauses = new ArrayList<>();
        private String dsl;
        private long totalHits;
        private long costMs;
    }

    @Data
    public static class CacheStage {
        private String status;
        private String key;
        private long lookupMs;
    }

    @Data
    public static class TimingStage {
        private long totalMs;
        private long cacheMs;
        private long nerMs;
        private long queryMs;
        private long esMs;
    }
}
