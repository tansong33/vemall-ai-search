package cn.vetech.aimall.embedding;

import cn.vetech.aimall.config.AiMallProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Configuration
public class EmbeddingClientFactory {

    @Bean
    public EmbeddingClient embeddingClient(AiMallProperties props, RestTemplate llmRestTemplate) {
        AiMallProperties.Embedding cfg = props.getEmbedding();
        if (!StringUtils.hasText(cfg.getApiKey())) {
            throw new IllegalStateException(
                    "缺少 Embedding API Key：请在项目根目录创建 .env 文件并写入 AIMALL_EMBEDDING_API_KEY=sk-xxx（参考 .env.example 与 README 第 3 步）");
        }
        log.info("Embedding 接入: model={}", cfg.getModel());
        return new OpenAiCompatibleEmbeddingClient(cfg, llmRestTemplate);
    }
}
