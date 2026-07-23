package cn.vetech.aimall.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
@ConditionalOnProperty(name = "aimall.search.elasticsearch-provider", havingValue = "rest")
public class ElasticsearchConfiguration {

    @Bean("mallElasticsearchRestTemplate")
    public RestTemplate mallElasticsearchRestTemplate(RestTemplateBuilder builder, AiMallProperties properties) {
        AiMallProperties.Search.Elasticsearch es = properties.getSearch().getElasticsearch();
        RestTemplateBuilder configured = builder
                .setConnectTimeout(Duration.ofMillis(Math.max(50, es.getConnectTimeoutMs())))
                .setReadTimeout(Duration.ofMillis(Math.max(100, es.getReadTimeoutMs())));
        if (StringUtils.hasText(es.getUsername())) {
            configured = configured.basicAuthentication(es.getUsername(), es.getPassword() == null ? "" : es.getPassword());
        }
        return configured.build();
    }
}
