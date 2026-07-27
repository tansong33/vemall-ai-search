package cn.vetech.ai.search.server.service.ner;

/** ONNX 等外部模型的推理客户端。 */
public interface NerModelClient {

    NerModelOutput predict(String query);

    boolean isReady();

    String provider();

    String modelVersion();

    /** 模型不可用的原因；可用时返回 null。健康检查与调试链路展示用。 */
    String unavailableReason();
}
