package com.vetech.aimall.llm;

import lombok.extern.slf4j.Slf4j;

/**
 * Mock 实现：不调用任何外部 API。
 * 意图理解走 MockIntentExtractor 的规则词典；话术生成走模板。
 * 作用：让整条链路在没有 API Key、没有外网的环境下也能完整跑通，
 * 便于新人理解链路、跑通评测脚手架，再平滑切换真实模型。
 */
@Slf4j
public class MockLlmClient implements LlmClient {

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        // mock 模式下意图理解与生成均不经过本方法（由上层服务内置规则处理），
        // 这里仅兜底返回空串，避免误用。
        log.debug("MockLlmClient.chat called, returning empty");
        return "";
    }

    @Override
    public String chatWithImage(String systemPrompt, String userPrompt, String imageBase64, String mimeType) {
        return "";
    }

    @Override
    public boolean isReal() {
        return false;
    }
}
