package cn.vetech.aimall.config;

import cn.vetech.aimall.service.ner.NerModelClient;
import cn.vetech.aimall.service.ner.StubNerModelClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 未安装任何模型适配器时注册安全占位，保证规则主链路仍可启动。 */
@Configuration
public class NerModelConfiguration {

    @Bean
    @ConditionalOnMissingBean(NerModelClient.class)
    public NerModelClient stubNerModelClient(AiMallProperties properties) {
        return new StubNerModelClient(properties);
    }
}
