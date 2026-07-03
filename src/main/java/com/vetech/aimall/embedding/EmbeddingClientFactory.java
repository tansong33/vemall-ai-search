package com.vetech.aimall.embedding;

import com.vetech.aimall.config.AiMallProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Configuration
public class EmbeddingClientFactory {

    @Bean
    public EmbeddingClient embeddingClient(AiMallProperties props, RestTemplate llmRestTemplate) {
        AiMallProperties.Embedding cfg = props.getEmbedding();
        if ("openai".equalsIgnoreCase(cfg.getProvider())) {
            log.info("Embedding provider = openai-compatible, model={}", cfg.getModel());
            return new OpenAiCompatibleEmbeddingClient(cfg, llmRestTemplate);
        }
        log.info("Embedding provider = mock（bigram 哈希向量，dim={}）", cfg.getDimension());
        return new MockEmbeddingClient(cfg.getDimension());
    }
}
