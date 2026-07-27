package cn.vetech.ai.search.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 聚合 Elasticsearch 与搜索链路配置。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ai-search")
public class SearchProperties {

    private Elasticsearch elasticsearch = new Elasticsearch();
    private Search search = new Search();

    @Data
    public static class Elasticsearch {
        private String host = "localhost";
        private int port = 9200;
        private String scheme = "http";
        private int connectTimeoutMs = 5000;
        private int socketTimeoutMs = 30000;
        private int connectionRequestTimeoutMs = 5000;
        private int maxConnections = 100;
        private int maxConnectionsPerRoute = 50;
    }

    @Data
    public static class Search {
        private String indexName = "products_v2";
        private int defaultPageSize = 20;
        private int maxPageSize = 100;
        private float minScore = 1.0f;
        private String suffixExclusionPath = "classpath:category-exclusion-suffixes.txt";
        private String categoryExclusionPath = "classpath:category-specific-exclusions.txt";
        private String synonymPath = "classpath:exclusion-synonyms.txt";
        /** 缓存 key 的版本维度，任一变更都会让旧缓存自然失效。 */
        private Version version = new Version();

        @Data
        public static class Version {
            private String index = "unknown";
            private String dict = "unknown";
            private String nerModel = "unknown";
            private String rule = "unknown";
        }
    }
}
