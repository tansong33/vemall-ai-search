package com.aisearch.ner.client;

import com.aisearch.ner.config.NerProperties;
import com.aisearch.ner.dto.PyNerResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

/**
 * Python NER 服务的 HTTP 客户端。
 *
 * 这里只负责"把请求发出去并在失败时快速放弃"，降级策略在 HybridNerService。
 * 熔断器打开期间 fallback 直接返回 null，不产生任何网络调用。
 */
@Component
public class PythonNerClient {

    private static final Logger log = LoggerFactory.getLogger(PythonNerClient.class);

    private final WebClient webClient;
    private final NerProperties props;

    public PythonNerClient(WebClient.Builder builder, NerProperties props) {
        this.props = props;
        this.webClient = builder.baseUrl(props.getClient().getBaseUrl()).build();
    }

    @CircuitBreaker(name = "nerService", fallbackMethod = "fallback")
    public PyNerResponse recognize(String query, String requestId) {
        if (query == null || query.isBlank()
                || query.length() > props.getClient().getMaxQueryChars()) {
            return null;
        }
        return webClient.post()
                .uri("/ner")
                .bodyValue(Map.of("query", query, "requestId", requestId == null ? "" : requestId))
                .retrieve()
                .bodyToMono(PyNerResponse.class)
                .timeout(props.getClient().getTimeout())
                .retryWhen(Retry.fixedDelay(
                        props.getClient().getMaxRetries(), props.getClient().getRetryBackoff()))
                .block();
    }

    /** 熔断/超时/异常统一走这里，返回 null 让上层用词典兜底。 */
    @SuppressWarnings("unused")
    private PyNerResponse fallback(String query, String requestId, Throwable t) {
        log.warn("NER service unavailable, falling back to dictionary. query={} cause={}",
                query, t.toString());
        return null;
    }
}
