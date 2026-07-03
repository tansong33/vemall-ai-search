package cn.vetech.aimall.service.recall;

import cn.vetech.aimall.model.dto.IntentResult;
import cn.vetech.aimall.model.dto.ScoredProduct;
import cn.vetech.aimall.model.entity.Product;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合召回编排：并联所有 RecallChannel -> 按商品 id 合并各路分数 -> 结构化硬过滤。
 * Spring 自动注入 List<RecallChannel>：新增召回通道零改动接入。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HybridRecallService {

    private final List<RecallChannel> channels;

    public List<ScoredProduct> recall(String rawQuery, IntentResult intent) {
        Map<Long, ScoredProduct> merged = new LinkedHashMap<>();

        for (RecallChannel channel : channels) {
            List<ScoredProduct> part = channel.recall(rawQuery, intent);
            log.info("召回通道[{}] 返回 {} 条", channel.name(), part.size());
            for (ScoredProduct sp : part) {
                ScoredProduct exist = merged.get(sp.getProduct().getId());
                if (exist == null) {
                    merged.put(sp.getProduct().getId(), sp);
                } else {
                    // 同一商品被多通道召回：各路分数取 max 合并
                    exist.setSemanticScore(Math.max(exist.getSemanticScore(), sp.getSemanticScore()));
                    exist.setKeywordScore(Math.max(exist.getKeywordScore(), sp.getKeywordScore()));
                }
            }
        }

        // ============ 结构化硬过滤（不满足硬条件的直接排除，这是杜绝"推超预算/无货商品"的保险丝） ============
        List<ScoredProduct> filtered = new ArrayList<>();
        for (ScoredProduct sp : merged.values()) {
            Product p = sp.getProduct();
            if (p.getStock() == null || p.getStock() <= 0) continue;                      // 无库存
            if (intent.getBudgetMax() != null && p.getPrice() != null
                    && p.getPrice().compareTo(intent.getBudgetMax()) > 0) continue;        // 超预算
            if (intent.getCategory() != null && !intent.getCategory().isEmpty()
                    && !intent.getCategory().equals(p.getCategory())) continue;            // 类目不符
            filtered.add(sp);
        }
        log.info("混合召回合并 {} 条，硬过滤后剩 {} 条（预算={}，类目={}）",
                merged.size(), filtered.size(), intent.getBudgetMax(), intent.getCategory());
        return filtered;
    }

    /** 类目过滤过严导致空结果时的放宽重试：去掉类目约束再来一次 */
    public List<ScoredProduct> recallWithRelax(String rawQuery, IntentResult intent) {
        List<ScoredProduct> result = recall(rawQuery, intent);
        if (result.isEmpty() && intent.getCategory() != null) {
            log.info("按类目[{}]召回为空，放宽类目约束重试", intent.getCategory());
            String backup = intent.getCategory();
            intent.setCategory(null);
            result = recall(rawQuery, intent);
            intent.setCategory(backup);
        }
        return result;
    }
}
