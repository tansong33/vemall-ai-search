package cn.vetech.aimall.llm;

import cn.vetech.aimall.config.AiMallProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

/** 装配 LlmClient。启动即校验 Key，缺失时快速失败并给出明确指引（而不是运行时才发现全在降级）。 */
@Slf4j
@Configuration
public class LlmClientFactory {

    @Bean
    public LlmClient llmClient(AiMallProperties props, RestTemplate llmRestTemplate) {
        AiMallProperties.Llm cfg = props.getLlm();
        if (!StringUtils.hasText(cfg.getApiKey())) {
            throw new IllegalStateException(
                    "缺少大模型 API Key：请在项目配置文件（application-local.yml）文件并写入 AIMALL_LLM_API_KEY=sk-xxx（参考README 第 3 步）");
        }
        log.info("LLM 接入: baseUrl={}, chatModel={}, visionModel={}",
                cfg.getBaseUrl(), cfg.getChatModel(), cfg.getVisionModel());
        return new OpenAiCompatibleLlmClient(cfg, llmRestTemplate);
    }
}
