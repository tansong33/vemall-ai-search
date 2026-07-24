package com.tsong.aisearch;

import com.tsong.aisearch.config.AiSearchProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AiSearchProperties.class)
public class AiSearchApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiSearchApplication.class, args);
    }
}
