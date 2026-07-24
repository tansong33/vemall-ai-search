package com.tsong.aisearch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai-search")
public class AiSearchProperties {

    private final Elasticsearch elasticsearch = new Elasticsearch();
    private final Search search = new Search();
    private final Ner ner = new Ner();

    public Elasticsearch getElasticsearch() {
        return elasticsearch;
    }

    public Search getSearch() {
        return search;
    }

    public Ner getNer() {
        return ner;
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

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getScheme() { return scheme; }
        public void setScheme(String scheme) { this.scheme = scheme; }
        public int getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(int value) { this.connectTimeoutMs = value; }
        public int getSocketTimeoutMs() { return socketTimeoutMs; }
        public void setSocketTimeoutMs(int value) { this.socketTimeoutMs = value; }
        public int getConnectionRequestTimeoutMs() { return connectionRequestTimeoutMs; }
        public void setConnectionRequestTimeoutMs(int value) { this.connectionRequestTimeoutMs = value; }
        public int getMaxConnections() { return maxConnections; }
        public void setMaxConnections(int value) { this.maxConnections = value; }
        public int getMaxConnectionsPerRoute() { return maxConnectionsPerRoute; }
        public void setMaxConnectionsPerRoute(int value) { this.maxConnectionsPerRoute = value; }
    }

    public static class Search {
        private String indexName = "products_v2";
        private int resultSize = 20;

        public String getIndexName() { return indexName; }
        public void setIndexName(String indexName) { this.indexName = indexName; }
        public int getResultSize() { return resultSize; }
        public void setResultSize(int resultSize) { this.resultSize = resultSize; }
    }

    public static class Ner {
        private String mode = "hybrid";
        private String dictionaryPath = "classpath:ner_dict.txt";
        private final Model model = new Model();

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public String getDictionaryPath() { return dictionaryPath; }
        public void setDictionaryPath(String dictionaryPath) { this.dictionaryPath = dictionaryPath; }
        public Model getModel() { return model; }
    }

    public static class Model {
        private boolean enabled;
        private String modelPath = "/app/models/ner/model.onnx";
        private String vocabularyPath = "/app/models/ner/vocab.txt";
        private String labelsPath = "/app/models/ner/labels.json";
        private int maxLength = 64;
        private double confidenceThreshold = 0.75;
        private String version = "none";
        private String inputIdsName = "input_ids";
        private String attentionMaskName = "attention_mask";
        private String tokenTypeIdsName = "token_type_ids";
        private String outputName = "logits";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getModelPath() { return modelPath; }
        public void setModelPath(String modelPath) { this.modelPath = modelPath; }
        public String getVocabularyPath() { return vocabularyPath; }
        public void setVocabularyPath(String vocabularyPath) { this.vocabularyPath = vocabularyPath; }
        public String getLabelsPath() { return labelsPath; }
        public void setLabelsPath(String labelsPath) { this.labelsPath = labelsPath; }
        public int getMaxLength() { return maxLength; }
        public void setMaxLength(int maxLength) { this.maxLength = maxLength; }
        public double getConfidenceThreshold() { return confidenceThreshold; }
        public void setConfidenceThreshold(double value) { this.confidenceThreshold = value; }
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
        public String getInputIdsName() { return inputIdsName; }
        public void setInputIdsName(String value) { this.inputIdsName = value; }
        public String getAttentionMaskName() { return attentionMaskName; }
        public void setAttentionMaskName(String value) { this.attentionMaskName = value; }
        public String getTokenTypeIdsName() { return tokenTypeIdsName; }
        public void setTokenTypeIdsName(String value) { this.tokenTypeIdsName = value; }
        public String getOutputName() { return outputName; }
        public void setOutputName(String value) { this.outputName = value; }
    }
}
