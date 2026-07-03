package com.vetech.aimall.service.recall;

import com.vetech.aimall.config.AiMallProperties;
import com.vetech.aimall.model.dto.IntentResult;
import com.vetech.aimall.model.dto.ScoredProduct;
import com.vetech.aimall.model.entity.Product;
import com.vetech.aimall.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 关键词召回：品牌名/型号/精确词场景下语义召回可能会漏，本通道兜底。
 * Demo 用 MySQL LIKE；商品量大或需要 BM25 打分时，替换为 Elasticsearch 实现即可（接口不变）。
 */
@Component
@RequiredArgsConstructor
public class KeywordRecallChannel implements RecallChannel {

    private final ProductRepository productRepository;
    private final AiMallProperties props;

    @Override
    public String name() {
        return "keyword";
    }

    @Override
    public List<ScoredProduct> recall(String rawQuery, IntentResult intent) {
        // 命中次数作为关键词分：被多个关键词命中的商品得分更高
        Map<Long, ScoredProduct> merged = new LinkedHashMap<>();
        List<String> kws = new ArrayList<>(intent.getKeywords());
        for (String scene : intent.getScenes()) {
            if (!kws.contains(scene)) kws.add(scene);
        }
        if (intent.getCategory() != null) kws.add(intent.getCategory());

        int limit = props.getRecall().getKeywordTopK();
        for (String kw : kws) {
            if (kw == null || kw.trim().length() < 2) continue;
            for (Product p : productRepository.searchByKeyword(kw.trim())) {
                ScoredProduct sp = merged.computeIfAbsent(p.getId(),
                        id -> new ScoredProduct(p, 0, 0, 0));
                sp.setKeywordScore(sp.getKeywordScore() + 1);
            }
            if (merged.size() >= limit * 2) break;
        }
        // 归一化到 [0,1]
        double max = merged.values().stream().mapToDouble(ScoredProduct::getKeywordScore).max().orElse(1);
        List<ScoredProduct> list = new ArrayList<>(merged.values());
        for (ScoredProduct sp : list) {
            sp.setKeywordScore(sp.getKeywordScore() / max);
        }
        list.sort((a, b) -> Double.compare(b.getKeywordScore(), a.getKeywordScore()));
        return list.size() > limit ? list.subList(0, limit) : list;
    }
}
