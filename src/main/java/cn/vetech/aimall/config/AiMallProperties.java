package cn.vetech.aimall.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** aimall.* 配置总入口。改 yml 即可换模型端点/模型名、调召回参数与重排权重，无需改代码。 */
@Data
@ConfigurationProperties(prefix = "aimall")
public class AiMallProperties {

    private Llm llm = new Llm();
    private Embedding embedding = new Embedding();
    private Recall recall = new Recall();
    private Rerank rerank = new Rerank();
    private Cache cache = new Cache();

    @Data
    public static class Llm {
        private String baseUrl;
        private String apiKey;
        private String chatModel;
        private String visionModel;
        private int timeoutMs = 30000;
    }

    @Data
    public static class Embedding {
        private String baseUrl;
        private String apiKey;
        private String model;
        /** 仅作 embedding 调用失败时兜底零向量的维度；实际维度以接口返回为准 */
        private int dimension = 1024;
    }

    @Data
    public static class Recall {
        private int semanticTopK = 20;
        private int keywordTopK = 20;
    }

    @Data
    public static class Rerank {
        private double weightSemantic = 0.5;
        private double weightKeyword = 0.2;
        private double weightFeatured = 0.15;
        private double weightBudgetFit = 0.15;
        private int topN = 5;
    }

    @Data
    public static class Cache {
        private boolean enabled = true;
        private long ttlSeconds = 600;
    }
}
