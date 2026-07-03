package cn.vetech.aimall.embedding;

/**
 * 向量化统一接口（可插拔扩展点 #2）。
 * mock 实现零依赖可跑；生产可切 BGE(自托管服务)/百炼/OpenAI 等，只需新增实现类。
 */
public interface EmbeddingClient {

    /** 文本转向量（已 L2 归一化，便于用点积即余弦相似度） */
    float[] embed(String text);

    int dimension();
}
