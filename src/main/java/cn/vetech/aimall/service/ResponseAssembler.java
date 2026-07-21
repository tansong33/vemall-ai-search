package cn.vetech.aimall.service;

import cn.vetech.aimall.model.dto.IntentResult;
import cn.vetech.aimall.model.dto.ProductCard;
import cn.vetech.aimall.model.dto.ScoredProduct;
import cn.vetech.aimall.model.entity.Product;
import org.springframework.stereotype.Service;

/** 纯模板组装，不调用生成式模型，不阻塞商品返回。 */
@Service
public class ResponseAssembler {

    public String reply(int resultCount, IntentResult intent) {
        if (resultCount == 0) {
            if (!intent.getClarifications().isEmpty()) {
                return "需要补充条件：" + String.join("；", intent.getClarifications());
            }
            return "没有找到符合当前条件的商品，可尝试放宽价格、品牌或属性条件。";
        }
        return "找到 " + resultCount + " 件匹配商品，已按相关度和业务规则排序。";
    }

    public ProductCard toCard(ScoredProduct scored, IntentResult intent) {
        Product p = scored.getProduct();
        ProductCard card = new ProductCard();
        card.setId(p.getId());
        card.setTitle(p.getTitle());
        card.setCategory(p.getCategory());
        card.setPrice(p.getPrice());
        card.setImageUrl(p.getImageUrl());
        card.setSceneTags(p.getSceneTags());
        card.setPointsEligible(p.getPointsEligible());
        card.setStock(p.getStock());
        card.setScore(Math.round(scored.getFinalScore() * 1000) / 1000.0);
        card.setReason(reason(p, intent));
        return card;
    }

    private String reason(Product p, IntentResult intent) {
        for (String scene : intent.getScenes()) {
            if (p.getSceneTags() != null && p.getSceneTags().contains(scene)) {
                return "匹配“" + scene + "”场景";
            }
        }
        if (intent.getBrand() != null && intent.getBrand().equalsIgnoreCase(p.getBrand())) return "品牌精确匹配";
        if (intent.getCategory() != null && intent.getCategory().equals(p.getCategory())) return "类目精确匹配";
        return "数据库相关度与业务规则综合排序";
    }
}
