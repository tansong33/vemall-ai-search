package cn.vetech.aimall.service;

import cn.vetech.aimall.config.AiMallProperties;
import cn.vetech.aimall.model.dto.IntentResult;
import cn.vetech.aimall.model.dto.ScoredProduct;
import cn.vetech.aimall.model.entity.Product;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 精排层：综合分 = w1×语义 + w2×关键词 + w3×主推 + w4×预算贴合。
 * 权重全部来自 yml，业务方可随时调参对齐"什么该排前面"。
 *
 * 扩展点 #5：接入 BGE-reranker(cross-encoder) 或 LLM 打分时，
 * 在此把 cross-encoder 分数作为新信号加权即可；接入个性化时同理追加用户偏好分。
 */
@Service
@RequiredArgsConstructor
public class RerankService {

    private final AiMallProperties props;

    public List<ScoredProduct> rerank(List<ScoredProduct> candidates, IntentResult intent) {
        AiMallProperties.Rerank w = props.getRerank();
        for (ScoredProduct sp : candidates) {
            Product p = sp.getProduct();
            double featured = Boolean.TRUE.equals(p.getFeatured()) ? 1.0 : 0.0;
            double budgetFit = budgetFit(p.getPrice(), intent.getBudgetMax());
            double score = w.getWeightSemantic() * sp.getSemanticScore()
                         + w.getWeightKeyword() * sp.getKeywordScore()
                         + w.getWeightFeatured() * featured
                         + w.getWeightBudgetFit() * budgetFit;
            sp.setFinalScore(score);
        }
        candidates.sort((a, b) -> Double.compare(b.getFinalScore(), a.getFinalScore()));
        int n = Math.min(w.getTopN(), candidates.size());
        return candidates.subList(0, n);
    }

    /**
     * 预算贴合度：无预算恒为 0.5(中性)；有预算时，价格在预算的 50%~100% 区间视为"贴合"得高分，
     * 过于便宜(可能档次不符)或逼近超限得分递减。
     */
    private double budgetFit(BigDecimal price, BigDecimal budget) {
        if (budget == null || price == null || budget.doubleValue() <= 0) return 0.5;
        double ratio = price.doubleValue() / budget.doubleValue();
        if (ratio > 1.0) return 0.0;          // 理论上已被硬过滤，兜底
        if (ratio >= 0.5) return 1.0;         // 预算的 50%~100%：贴合
        return 0.4 + ratio;                   // 便宜过多：0.4~0.9 递增
    }
}
