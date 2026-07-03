package com.vetech.aimall.service.recall;

import com.vetech.aimall.config.AiMallProperties;
import com.vetech.aimall.embedding.EmbeddingClient;
import com.vetech.aimall.model.dto.IntentResult;
import com.vetech.aimall.model.dto.ScoredProduct;
import com.vetech.aimall.model.entity.Product;
import com.vetech.aimall.repository.ProductRepository;
import com.vetech.aimall.vectorstore.VectorStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 语义召回：query 向量化后在向量库中做近邻检索 */
@Component
@RequiredArgsConstructor
public class SemanticRecallChannel implements RecallChannel {

    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final ProductRepository productRepository;
    private final AiMallProperties props;

    @Override
    public String name() {
        return "semantic";
    }

    @Override
    public List<ScoredProduct> recall(String rawQuery, IntentResult intent) {
        String semanticQuery = intent.toSemanticQuery(rawQuery);
        float[] qv = embeddingClient.embed(semanticQuery);
        List<VectorStore.Hit> hits = vectorStore.search(qv, props.getRecall().getSemanticTopK());

        List<ScoredProduct> result = new ArrayList<>();
        for (VectorStore.Hit hit : hits) {
            Optional<Product> p = productRepository.findById(hit.id);
            if (!p.isPresent()) continue;
            // 余弦值域 [-1,1] 线性映射到 [0,1] 便于与其他信号加权
            double score = (hit.score + 1) / 2.0;
            result.add(new ScoredProduct(p.get(), score, 0, 0));
        }
        return result;
    }
}
