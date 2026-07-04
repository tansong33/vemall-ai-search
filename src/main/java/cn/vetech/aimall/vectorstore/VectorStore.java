package cn.vetech.aimall.vectorstore;

import java.util.List;

/**
 * 向量库统一接口（可插拔扩展点 #3）。
 * Demo 用内存实现（万级 SKU 毫秒级检索完全够用）；
 * 数据量增长后可新增 Milvus/Qdrant/pgvector 实现，上层召回代码不变。
 */
public interface VectorStore {

    void upsert(long id, float[] vector);

    /** 移除单个向量（商品下架/删除时用） */
    void remove(long id);

    /** 返回 topK 个 (id, 相似度) 对，相似度为余弦（向量已归一化则等于点积） */
    List<Hit> search(float[] queryVector, int topK);

    void clear();

    int size();

    class Hit {
        public final long id;
        public final double score;

        public Hit(long id, double score) {
            this.id = id;
            this.score = score;
        }
    }
}
