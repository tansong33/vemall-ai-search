package cn.vetech.aimall.embedding;

/**
 * Mock 向量化：字符 bigram 哈希词袋（hashing trick）+ L2 归一化。
 *
 * 原理：把文本切成相邻双字（对中文友好），每个 bigram 哈希到固定维度上累加，
 * 共享字词多的文本向量夹角小。它没有真正的"语义"，但足以让 Demo 的
 * 向量检索链路端到端跑通，并保证架构与真实 embedding 完全同构 ——
 * 切换到 BGE/商用 embedding 时，只是向量质量变好，代码不变。
 */
public class MockEmbeddingClient implements EmbeddingClient {

    private final int dim;

    public MockEmbeddingClient(int dim) {
        this.dim = dim;
    }

    @Override
    public float[] embed(String text) {
        float[] v = new float[dim];
        if (text == null || text.isEmpty()) return v;
        String t = text.replaceAll("\\s+", " ").toLowerCase();
        // 单字 + 双字 bigram 两路特征
        for (int i = 0; i < t.length(); i++) {
            addFeature(v, t.substring(i, i + 1), 1.0f);
            if (i + 2 <= t.length()) {
                addFeature(v, t.substring(i, i + 2), 2.0f); // bigram 权重更高
            }
        }
        normalize(v);
        return v;
    }

    private void addFeature(float[] v, String feature, float weight) {
        int h = feature.hashCode();
        int idx = Math.floorMod(h, dim);
        // 用另一个哈希决定正负号，减少哈希冲突带来的系统性偏差
        float sign = (Math.floorMod(h * 31 + 7, 2) == 0) ? 1f : -1f;
        v[idx] += sign * weight;
    }

    private void normalize(float[] v) {
        double norm = 0;
        for (float x : v) norm += x * x;
        norm = Math.sqrt(norm);
        if (norm < 1e-9) return;
        for (int i = 0; i < v.length; i++) v[i] /= norm;
    }

    @Override
    public int dimension() {
        return dim;
    }
}
