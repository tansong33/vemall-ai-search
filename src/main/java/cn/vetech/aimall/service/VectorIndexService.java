package cn.vetech.aimall.service;

import cn.vetech.aimall.embedding.EmbeddingClient;
import cn.vetech.aimall.repository.ProductRepository;
import cn.vetech.aimall.vectorstore.VectorStore;
import cn.vetech.aimall.model.entity.Product;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 商品向量索引：应用启动时全量构建（对应方案里的"离线建库"环节）。
 * 万级 SKU 秒级完成；商品上下架后可调 POST /api/admin/reindex 重建。
 * 扩展点：商品量大后改为消费商品变更消息做增量 upsert。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorIndexService implements ApplicationRunner {

    private final ProductRepository productRepository;
    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;

    @Override
    public void run(ApplicationArguments args) {
        rebuild();
    }

    public synchronized int rebuild() {
        long start = System.currentTimeMillis();
        vectorStore.clear();
        List<Product> all = productRepository.findAll();
        for (Product p : all) {
            float[] v = embeddingClient.embed(p.toEmbeddingText());
            vectorStore.upsert(p.getId(), v);
        }
        log.info("商品向量索引构建完成：{} 条，耗时 {}ms", all.size(), System.currentTimeMillis() - start);
        return all.size();
    }
}
