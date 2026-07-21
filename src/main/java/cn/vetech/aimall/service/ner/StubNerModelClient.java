package cn.vetech.aimall.service.ner;

import cn.vetech.aimall.config.AiMallProperties;

import java.util.Collections;

/** 默认占位实现。它明确报告未就绪，不会用随机的预训练分类头污染线上结果。 */
public class StubNerModelClient implements NerModelClient {

    private final AiMallProperties properties;

    public StubNerModelClient(AiMallProperties properties) {
        this.properties = properties;
    }

    @Override
    public NerModelOutput predict(String normalizedQuery) {
        return new NerModelOutput(modelVersion(), 0, Collections.emptyList());
    }

    @Override
    public boolean isReady() {
        return false;
    }

    @Override
    public String provider() {
        return "stub";
    }

    @Override
    public String modelVersion() {
        return properties.getNer().getModelVersion();
    }
}
