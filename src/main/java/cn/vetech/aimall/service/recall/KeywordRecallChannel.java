package cn.vetech.aimall.service.recall;

import cn.vetech.aimall.config.AiMallProperties;
import cn.vetech.aimall.mapper.ProductMapper;
import cn.vetech.aimall.model.dto.IntentResult;
import cn.vetech.aimall.model.dto.ScoredProduct;
import cn.vetech.aimall.model.entity.Product;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 关键词召回：品牌名/型号/精确词场景下语义召回可能会漏，本通道兜底。
 * 当前用 MySQL LIKE；商品量大或需要 BM25 打分时，替换为 Elasticsearch 实现即可（RecallChannel 接口不变）。
 */
@Component
@RequiredArgsConstructor
public class KeywordRecallChannel implements RecallChannel {

    private final ProductMapper productMapper;
    private final AiMallProperties props;

    @Override
    public String name() {
        return "keyword";
    }

    @Override
    public List<ScoredProduct> recall(String rawQuery, IntentResult intent) {
        Map<Long, ScoredProduct> merged = new LinkedHashMap<>();
        List<String> kws = new ArrayList<>(intent.getKeywords());
        for (String scene : intent.getScenes()) {
            if (!kws.contains(scene)) kws.add(scene);
        }
        if (intent.getCategory() != null) kws.add(intent.getCategory());

        int limit = props.getRecall().getKeywordTopK();
        for (String kw : kws) {
            if (kw == null || kw.trim().length() < 2) continue;
            for (Product p : productMapper.searchByKeyword(kw.trim())) {
                ScoredProduct sp = merged.computeIfAbsent(p.getId(),
                        id -> new ScoredProduct(p, 0, 0, 0));
                sp.setKeywordScore(sp.getKeywordScore() + 1);   // 命中次数作分：多词命中的商品更高
            }
            if (merged.size() >= limit * 2) break;
        }
        double max = merged.values().stream().mapToDouble(ScoredProduct::getKeywordScore).max().orElse(1);
        List<ScoredProduct> list = new ArrayList<>(merged.values());
        for (ScoredProduct sp : list) {
            sp.setKeywordScore(sp.getKeywordScore() / max);     // 归一化到 [0,1]
        }
        list.sort((a, b) -> Double.compare(b.getKeywordScore(), a.getKeywordScore()));
        return list.size() > limit ? list.subList(0, limit) : list;
    }
}
