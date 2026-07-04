package cn.vetech.aimall.vectorstore;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存向量库：暴力余弦 + 小顶堆取 topK。
 * 2 万 SKU × 512 维 ≈ 40MB、单次检索 < 10ms，Demo/POC 阶段绰绰有余。
 */
@Component
public class InMemoryVectorStore implements VectorStore {

    private final Map<Long, float[]> store = new ConcurrentHashMap<>();

    @Override
    public void upsert(long id, float[] vector) {
        store.put(id, vector);
    }

    @Override
    public void remove(long id) {
        store.remove(id);
    }

    @Override
    public List<Hit> search(float[] q, int topK) {
        PriorityQueue<Hit> heap = new PriorityQueue<>(Comparator.comparingDouble(h -> h.score));
        for (Map.Entry<Long, float[]> e : store.entrySet()) {
            double s = dot(q, e.getValue());
            if (heap.size() < topK) {
                heap.offer(new Hit(e.getKey(), s));
            } else if (heap.peek() != null && s > heap.peek().score) {
                heap.poll();
                heap.offer(new Hit(e.getKey(), s));
            }
        }
        List<Hit> result = new ArrayList<>(heap);
        result.sort((a, b) -> Double.compare(b.score, a.score));
        return result;
    }

    private double dot(float[] a, float[] b) {
        int n = Math.min(a.length, b.length);
        double s = 0;
        for (int i = 0; i < n; i++) s += a[i] * b[i];
        return s;
    }

    @Override
    public void clear() {
        store.clear();
    }

    @Override
    public int size() {
        return store.size();
    }
}
