package cn.vetech.aimall.llm;

import cn.vetech.aimall.config.AiMallProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/** 按配置装配 LlmClient 实现 */
@Slf4j
@Configuration
public class LlmClientFactory {

    @Bean
    public LlmClient llmClient(AiMallProperties props, RestTemplate llmRestTemplate) {
        String provider = props.getLlm().getProvider();
        if ("openai".equalsIgnoreCase(provider)) {
            log.info("LLM provider = openai-compatible, baseUrl={}, chatModel={}, visionModel={}",
                    props.getLlm().getBaseUrl(), props.getLlm().getChatModel(), props.getLlm().getVisionModel());
            return new OpenAiCompatibleLlmClient(props.getLlm(), llmRestTemplate);
        }
        log.info("LLM provider = mock（零外部依赖模式，意图理解走规则词典，话术走模板）");
        return new MockLlmClient();
    }
}
