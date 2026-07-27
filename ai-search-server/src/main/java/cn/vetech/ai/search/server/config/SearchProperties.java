package cn.vetech.ai.search.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 聚合 Elasticsearch 与搜索链路配置。
 */
@Configuration
@ConfigurationProperties(prefix = "ai-search")
public class SearchProperties {

    private Elasticsearch elasticsearch = new Elasticsearch();
    private Search search = new Search();

    public Elasticsearch getElasticsearch() {
        return elasticsearch;
    }

    public void setElasticsearch(Elasticsearch elasticsearch) {
        this.elasticsearch = elasticsearch;
    }

    public Search getSearch() {
        return search;
    }

    public void setSearch(Search search) {
        this.search = search;
    }

    public static class Elasticsearch {
        private String host = "localhost";
        private int port = 9200;
        private String scheme = "http";
        private int connectTimeoutMs = 5000;
        private int socketTimeoutMs = 30000;
        private int connectionRequestTimeoutMs = 5000;
        private int maxConnections = 100;
        private int maxConnectionsPerRoute = 50;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getScheme() {
            return scheme;
        }

        public void setScheme(String scheme) {
            this.scheme = scheme;
        }

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public int getSocketTimeoutMs() {
            return socketTimeoutMs;
        }

        public void setSocketTimeoutMs(int socketTimeoutMs) {
            this.socketTimeoutMs = socketTimeoutMs;
        }

        public int getConnectionRequestTimeoutMs() {
            return connectionRequestTimeoutMs;
        }

        public void setConnectionRequestTimeoutMs(int connectionRequestTimeoutMs) {
            this.connectionRequestTimeoutMs = connectionRequestTimeoutMs;
        }

        public int getMaxConnections() {
            return maxConnections;
        }

        public void setMaxConnections(int maxConnections) {
            this.maxConnections = maxConnections;
        }

        public int getMaxConnectionsPerRoute() {
            return maxConnectionsPerRoute;
        }

        public void setMaxConnectionsPerRoute(int maxConnectionsPerRoute) {
            this.maxConnectionsPerRoute = maxConnectionsPerRoute;
        }
    }

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

        public String getIndexName() {
            return indexName;
        }

        public void setIndexName(String indexName) {
            this.indexName = indexName;
        }

        public int getDefaultPageSize() {
            return defaultPageSize;
        }

        public void setDefaultPageSize(int defaultPageSize) {
            this.defaultPageSize = defaultPageSize;
        }

        public int getMaxPageSize() {
            return maxPageSize;
        }

        public void setMaxPageSize(int maxPageSize) {
            this.maxPageSize = maxPageSize;
        }

        public float getMinScore() {
            return minScore;
        }

        public void setMinScore(float minScore) {
            this.minScore = minScore;
        }

        public String getSuffixExclusionPath() {
            return suffixExclusionPath;
        }

        public void setSuffixExclusionPath(String suffixExclusionPath) {
            this.suffixExclusionPath = suffixExclusionPath;
        }

        public String getCategoryExclusionPath() {
            return categoryExclusionPath;
        }

        public void setCategoryExclusionPath(String categoryExclusionPath) {
            this.categoryExclusionPath = categoryExclusionPath;
        }

        public String getSynonymPath() {
            return synonymPath;
        }

        public void setSynonymPath(String synonymPath) {
            this.synonymPath = synonymPath;
        }

        public Version getVersion() {
            return version;
        }

        public void setVersion(Version version) {
            this.version = version;
        }

        public static class Version {
            private String index = "unknown";
            private String dict = "unknown";
            private String nerModel = "unknown";
            private String rule = "unknown";

            public String getIndex() {
                return index;
            }

            public void setIndex(String index) {
                this.index = index;
            }

            public String getDict() {
                return dict;
            }

            public void setDict(String dict) {
                this.dict = dict;
            }

            public String getNerModel() {
                return nerModel;
            }

            public void setNerModel(String nerModel) {
                this.nerModel = nerModel;
            }

            public String getRule() {
                return rule;
            }

            public void setRule(String rule) {
                this.rule = rule;
            }
        }
    }
}
