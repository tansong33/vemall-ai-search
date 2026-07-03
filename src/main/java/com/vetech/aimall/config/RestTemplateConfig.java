package com.vetech.aimall.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class RestTemplateConfig {

    /** 调用外部大模型 API 使用的 HTTP 客户端，超时时间取自配置 */
    @Bean
    public RestTemplate llmRestTemplate(RestTemplateBuilder builder, AiMallProperties props) {
        return builder
                .setConnectTimeout(Duration.ofMillis(props.getLlm().getTimeoutMs()))
                .setReadTimeout(Duration.ofMillis(props.getLlm().getTimeoutMs()))
                .build();
    }
}
