package cn.vetech.aimall.service;

import cn.vetech.aimall.config.AiMallProperties;
import cn.vetech.aimall.embedding.EmbeddingClient;
import cn.vetech.aimall.model.entity.Product;
import cn.vetech.aimall.model.entity.ProductVector;
import cn.vetech.aimall.repository.ProductRepository;
import cn.vetech.aimall.repository.ProductVectorRepository;
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
 * 商品向量索引：持久化 + 增量同步。
 *
 * 变化(相比每次全量重嵌)：
 *  - 启动时先从 product_vector 表【加载】已算好的向量进内存，不调用 embedding API；
 *  - 再做一次【增量同步】：只对 新增 / 内容变化(哈希不同) / 换过模型(标签不同) 的商品重新嵌入，
 *    其余复用；商品已删除的向量顺带清理(孤儿删除)。
 *  - 全程【原地 upsert】，不 clear，索引不空窗——所以不需要双缓冲也不会搜出空结果。
 *
 * 换 embedding 模型(如 mock->openai、v3->v4、改维度)时，模型标签变化会自动触发一次全量重嵌，无需手动干预。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorIndexService implements ApplicationRunner {

    private final ProductRepository productRepository;
    private final ProductVectorRepository vectorRepository;
    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final AiMallProperties props;

    @Override
    public void run(ApplicationArguments args) {
        List<ProductVector> persisted = vectorRepository.findAll();
        for (ProductVector pv : persisted) {
            vectorStore.upsert(pv.getProductId(), VectorCodec.toFloats(pv.getVector()));
        }
        log.info("启动加载：从数据库载入 {} 条已持久化向量（未调用 embedding API）", persisted.size());
        SyncResult r = syncInternal(persisted, false);
        log.info("启动增量同步：新嵌 {}，复用 {}，清理孤儿 {}，失败 {}，当前索引 {} 条",
                r.embedded, r.skipped, r.removed, r.failed, vectorStore.size());
    }

    /** 供 POST /api/admin/reindex 调用。force=true 忽略哈希全量重嵌（改了 toEmbeddingText 逻辑时用）。 */
    public synchronized SyncResult rebuild(boolean force) {
        List<ProductVector> persisted = vectorRepository.findAll();
        return syncInternal(persisted, force);
    }

    private SyncResult syncInternal(List<ProductVector> existingList, boolean force) {
        String modelLabel = currentModelLabel();
        Map<Long, ProductVector> existing = new HashMap<>();
        for (ProductVector pv : existingList) existing.put(pv.getProductId(), pv);

        List<Product> products = productRepository.findAll();
        Set<Long> liveIds = new HashSet<>();
        List<ProductVector> toSave = new ArrayList<>();
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
                // embedding 失败(如 API 超时/欠费)：不落库，下次 reindex 再试，避免把零向量“缓存”成正确结果
                log.warn("商品 {} embedding 返回空/失败，跳过，下次 reindex 再试", p.getId());
                failed++;
                continue;
            }
            vectorStore.upsert(p.getId(), v);   // 原地替换，索引全程不空窗
            ProductVector row = (pv == null) ? new ProductVector() : pv;
            row.setProductId(p.getId());
            row.setVector(VectorCodec.toBytes(v));
            row.setDim(v.length);
            row.setModel(modelLabel);
            row.setContentHash(hash);
            toSave.add(row);
            embedded++;
        }
        if (!toSave.isEmpty()) vectorRepository.saveAll(toSave);

        // 孤儿清理：product 里已不存在的向量，从库和内存一并删除
        List<Long> orphans = existing.keySet().stream()
                .filter(id -> !liveIds.contains(id))
                .collect(Collectors.toList());
        if (!orphans.isEmpty()) {
            vectorRepository.deleteAllById(orphans);
            for (Long id : orphans) vectorStore.remove(id);
        }
        return new SyncResult(embedded, skipped, orphans.size(), failed);
    }

    /** 模型标签：provider:model:dim。任一变化都会让所有旧向量“过期”并触发重嵌。 */
    private String currentModelLabel() {
        AiMallProperties.Embedding e = props.getEmbedding();
        if ("openai".equalsIgnoreCase(e.getProvider())) {
            return "openai:" + e.getModel() + ":" + e.getDimension();
        }
        return "mock:bigram:" + e.getDimension();
    }

    /** 同步结果 */
    public static class SyncResult {
        public final int embedded;   // 新嵌入/更新
        public final int skipped;    // 命中缓存复用
        public final int removed;    // 孤儿清理
        public final int failed;     // 嵌入失败(待下次重试)

        public SyncResult(int embedded, int skipped, int removed, int failed) {
            this.embedded = embedded;
            this.skipped = skipped;
            this.removed = removed;
            this.failed = failed;
        }
    }
}
