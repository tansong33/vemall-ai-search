package cn.vetech.aimall.service;

import cn.vetech.aimall.config.AiMallProperties;
import cn.vetech.aimall.embedding.EmbeddingClient;
import cn.vetech.aimall.mapper.ProductMapper;
import cn.vetech.aimall.mapper.ProductVectorMapper;
import cn.vetech.aimall.model.entity.Product;
import cn.vetech.aimall.model.entity.ProductVector;
import cn.vetech.aimall.vectorstore.VectorCodec;
import cn.vetech.aimall.vectorstore.VectorStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 商品向量索引：持久化 + 哈希增量同步。
 *  - 启动：先从 product_vector 表加载已算好的向量进内存（零 API 调用），再增量同步补差；
 *  - 增量：仅 新增 / 内容变化(哈希不同) / 换模型(标签不同) 的商品重新嵌入，其余复用；
 *  - 清理：product 表已删除的商品，其向量从库和内存一并移除（孤儿清理）；
 *  - 全程原地 upsert 不 clear，索引不空窗；embedding 失败(全零)不落库，下次同步自动重试。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorIndexService implements ApplicationRunner {

    private final ProductMapper productMapper;
    private final ProductVectorMapper vectorMapper;
    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final AiMallProperties props;

    @Override
    public void run(ApplicationArguments args) {
        List<ProductVector> persisted = vectorMapper.selectList(null);
        for (ProductVector pv : persisted) {
            vectorStore.upsert(pv.getProductId(), VectorCodec.toFloats(pv.getVector()));
        }
        log.info("启动加载：从数据库载入 {} 条已持久化向量（未调用 embedding API）", persisted.size());
        SyncResult r = syncInternal(persisted, false);
        log.info("启动增量同步：新嵌 {}，复用 {}，清理孤儿 {}，失败 {}，当前索引 {} 条",
                r.embedded, r.skipped, r.removed, r.failed, vectorStore.size());
    }

    /** 供 POST /api/admin/reindex 调用。force=true 忽略哈希全量重嵌（改 toEmbeddingText 拼接逻辑后用）。 */
    public synchronized SyncResult rebuild(boolean force) {
        List<ProductVector> persisted = vectorMapper.selectList(null);
        return syncInternal(persisted, force);
    }

    private SyncResult syncInternal(List<ProductVector> existingList, boolean force) {
        String modelLabel = currentModelLabel();
        Map<Long, ProductVector> existing = new HashMap<>();
        for (ProductVector pv : existingList) existing.put(pv.getProductId(), pv);

        List<Product> products = productMapper.selectList(null);
        Set<Long> liveIds = new HashSet<>();
        int embedded = 0, skipped = 0, failed = 0;

        for (Product p : products) {
            liveIds.add(p.getId());
            String hash = VectorCodec.sha256(p.toEmbeddingText());
            ProductVector pv = existing.get(p.getId());
            boolean needEmbed = force || pv == null
                    || !hash.equals(pv.getContentHash())
                    || !modelLabel.equals(pv.getModel());
            if (!needEmbed) {
                skipped++;
                continue;
            }
            float[] v = embeddingClient.embed(p.toEmbeddingText());
            if (VectorCodec.isBlank(v)) {
                log.warn("商品 {} embedding 返回空/失败，跳过，下次 reindex 再试", p.getId());
                failed++;
                continue;
            }
            vectorStore.upsert(p.getId(), v);                    // 原地替换，索引全程不空窗

            boolean isNew = (pv == null);
            ProductVector row = isNew ? new ProductVector() : pv;
            row.setProductId(p.getId());
            row.setVector(VectorCodec.toBytes(v));
            row.setDim(v.length);
            row.setModel(modelLabel);
            row.setContentHash(hash);
            if (isNew) {
                vectorMapper.insert(row);
            } else {
                vectorMapper.updateById(row);
            }
            embedded++;
        }

        // 孤儿清理：product 里已不存在的向量，从库和内存一并删除
        List<Long> orphans = existing.keySet().stream()
                .filter(id -> !liveIds.contains(id))
                .collect(Collectors.toList());
        if (!orphans.isEmpty()) {
            vectorMapper.deleteBatchIds(orphans);
            for (Long id : orphans) vectorStore.remove(id);
        }
        return new SyncResult(embedded, skipped, orphans.size(), failed);
    }

    /** 模型标签：model:dim。任一变化都会让所有旧向量"过期"并触发重嵌（如 v3->v4、改维度）。 */
    private String currentModelLabel() {
        AiMallProperties.Embedding e = props.getEmbedding();
        return e.getModel() + ":" + e.getDimension();
    }

    /** 同步结果 */
    public static class SyncResult {
        public final int embedded;
        public final int skipped;
        public final int removed;
        public final int failed;

        public SyncResult(int embedded, int skipped, int removed, int failed) {
            this.embedded = embedded;
            this.skipped = skipped;
            this.removed = removed;
            this.failed = failed;
        }
    }
}
