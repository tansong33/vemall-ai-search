package cn.vetech.aimall.service.ner;

/** 小模型推理端口；ONNX Runtime、远程推理或测试 fixture 都只需实现这个接口。 */
public interface NerModelClient {
    NerModelOutput predict(String normalizedQuery);

    boolean isReady();

    String provider();

    String modelVersion();
}
