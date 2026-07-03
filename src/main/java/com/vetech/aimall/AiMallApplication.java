package com.vetech.aimall;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * AI 智能商城 Demo 启动类。
 *
 * 核心链路：用户输入(文字/图片) -> 意图理解(LLM/VLM) -> 混合召回(语义+关键词+属性过滤)
 *          -> 规则重排 -> 推荐生成 -> (Redis 缓存 / 反馈埋点)
 *
 * 所有 AI 能力(LLM/Embedding/向量库)均为接口 + 多实现，通过 application.yml 切换，
 * 默认 mock 模式零外部依赖即可完整跑通，便于开发调试与成本控制。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AiMallApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiMallApplication.class, args);
    }
}
