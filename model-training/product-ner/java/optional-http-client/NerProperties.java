package com.aisearch.ner.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 绑定 application.yml 中的 ner.* 配置。全部可热更新（配合 @RefreshScope 或重启）。 */
@ConfigurationProperties(prefix = "ner")
public class NerProperties {

    /** model | dictionary | hybrid —— 决定线上真正用什么。默认 dictionary，保持现状。 */
    private Mode mode = Mode.DICTIONARY;

    private final Client client = new Client();
    private final Shadow shadow = new Shadow();
    private final Gray gray = new Gray();

    public enum Mode { MODEL, DICTIONARY, HYBRID }

    public static class Client {
        private String baseUrl = "http://ai-search-ner:8000";
        /** 搜索链路预算很紧：模型 CPU 单条 p99 约 10-20ms，80ms 已经很宽松。 */
        private Duration timeout = Duration.ofMillis(80);
        private int maxRetries = 1;
        private Duration retryBackoff = Duration.ofMillis(20);
        private int maxQueryChars = 128;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String v) { this.baseUrl = v; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration v) { this.timeout = v; }
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int v) { this.maxRetries = v; }
        public Duration getRetryBackoff() { return retryBackoff; }
        public void setRetryBackoff(Duration v) { this.retryBackoff = v; }
        public int getMaxQueryChars() { return maxQueryChars; }
        public void setMaxQueryChars(int v) { this.maxQueryChars = v; }
    }

    /** 影子模式：线上结果仍用词典，模型结果只记录差异。 */
    public static class Shadow {
        private boolean enabled = false;
        private double sampleRate = 1.0;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public double getSampleRate() { return sampleRate; }
        public void setSampleRate(double v) { this.sampleRate = v; }
    }

    /** 灰度：按 query 哈希稳定分流，避免同一个词一会儿走模型一会儿走词典。 */
    public static class Gray {
        private int percentage = 0;
        public int getPercentage() { return percentage; }
        public void setPercentage(int v) { this.percentage = v; }
    }

    public Mode getMode() { return mode; }
    public void setMode(Mode v) { this.mode = v; }
    public Client getClient() { return client; }
    public Shadow getShadow() { return shadow; }
    public Gray getGray() { return gray; }
}
