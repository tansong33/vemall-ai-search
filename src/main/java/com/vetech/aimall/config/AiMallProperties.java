package com.vetech.aimall.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * aimall.* 配置总入口。改 yml 即可切换模型提供方、召回参数、重排权重与缓存策略，无需改代码。
 */
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
        /** mock | openai */
        private String provider = "mock";
        private String baseUrl;
        private String apiKey;
        private String chatModel;
        private String visionModel;
        private int timeoutMs = 30000;
    }

    @Data
    public static class Embedding {
        /** mock | openai */
        private String provider = "mock";
        private String baseUrl;
        private String apiKey;
        private String model;
        private int dimension = 512;
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
