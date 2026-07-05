package cn.vetech.aimall.service.recall;

import cn.vetech.aimall.config.AiMallProperties;
import cn.vetech.aimall.embedding.EmbeddingClient;
import cn.vetech.aimall.mapper.ProductMapper;
import cn.vetech.aimall.model.dto.IntentResult;
import cn.vetech.aimall.model.dto.ScoredProduct;
import cn.vetech.aimall.model.entity.Product;
import cn.vetech.aimall.vectorstore.VectorCodec;
import cn.vetech.aimall.vectorstore.VectorStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 语义召回：query 向量化后在向量库中做近邻检索；命中后回库取真实商品（惰性删除的落点之一） */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemanticRecallChannel implements RecallChannel {

    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final ProductMapper productMapper;
    private final AiMallProperties props;

    @Override
    public String name() {
        return "semantic";
    }

    @Override
    public List<ScoredProduct> recall(String rawQuery, IntentResult intent) {
        String semanticQuery = intent.toSemanticQuery(rawQuery);
        float[] qv = embeddingClient.embed(semanticQuery);
        if (VectorCodec.isBlank(qv)) {
            // embedding 偶发失败：本次跳过语义路，关键词召回仍可工作，链路不断
            log.warn("query embedding 失败，本次跳过语义召回");
            return Collections.emptyList();
        }
        List<VectorStore.Hit> hits = vectorStore.search(qv, props.getRecall().getSemanticTopK());

        List<ScoredProduct> result = new ArrayList<>();
        for (VectorStore.Hit hit : hits) {
            Product p = productMapper.selectById(hit.id);
            if (p == null) continue;                       // 商品已删除：向量是孤儿，跳过（惰性删除）
            double score = (hit.score + 1) / 2.0;          // 余弦[-1,1] -> [0,1]
            result.add(new ScoredProduct(p, score, 0, 0));
        }
        return result;
    }
}
