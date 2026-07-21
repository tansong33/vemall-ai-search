package cn.vetech.aimall.service;

import cn.vetech.aimall.config.AiMallProperties;
import cn.vetech.aimall.model.dto.IntentResult;
import cn.vetech.aimall.model.dto.ScoredProduct;
import cn.vetech.aimall.model.entity.Product;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 候选集规则引擎。硬规则先过滤，软规则再打分；复杂度只与 candidate-limit 有关，与总 SKU 数无关。
 */
@Service
@RequiredArgsConstructor
public class ProductRuleEngine {

    private final AiMallProperties properties;
    private final ObjectMapper objectMapper;

    public List<ScoredProduct> rank(List<Product> candidates, IntentResult intent) {
        List<Product> valid = new ArrayList<>();
        for (Product product : candidates) {
            if (matchesHardRules(product, intent)) valid.add(product);
        }

        double maxDatabaseScore = 0;
        for (Product product : valid) {
            maxDatabaseScore = Math.max(maxDatabaseScore,
                    product.getSearchScore() == null ? 0 : product.getSearchScore());
        }

        AiMallProperties.Rerank weights = properties.getRerank();
        List<ScoredProduct> scored = new ArrayList<>();
        for (Product product : valid) {
            double databaseScore = maxDatabaseScore <= 0 ? 0.5
                    : safeScore(product.getSearchScore()) / maxDatabaseScore;
            double ruleScore = softRuleScore(product, intent);
            double featured = Boolean.TRUE.equals(product.getFeatured()) ? 1.0 : 0.0;
            double budgetFit = budgetFit(product.getPrice(), intent.getBudgetMin(), intent.getBudgetMax());
            double finalScore = weights.getWeightDatabase() * databaseScore
                    + weights.getWeightRule() * ruleScore
                    + weights.getWeightFeatured() * featured
                    + weights.getWeightBudgetFit() * budgetFit;
            scored.add(new ScoredProduct(product, databaseScore, ruleScore, finalScore));
        }
        scored.sort((a, b) -> Double.compare(b.getFinalScore(), a.getFinalScore()));
        int topN = Math.min(Math.max(1, weights.getTopN()), scored.size());
        return new ArrayList<>(scored.subList(0, topN));
    }

    private boolean matchesHardRules(Product p, IntentResult intent) {
        if (p.getStock() == null || p.getStock() <= 0) return false;
        if (intent.getBudgetMin() != null && p.getPrice() != null
                && p.getPrice().compareTo(intent.getBudgetMin()) < 0) return false;
        if (intent.getBudgetMax() != null && p.getPrice() != null
                && p.getPrice().compareTo(intent.getBudgetMax()) > 0) return false;
        if ("true".equals(intent.getAttributes().get("积分购买"))
                && !Boolean.TRUE.equals(p.getPointsEligible())) return false;
        if ("true".equals(intent.getAttributes().get("可开专票"))
                && !jsonBoolean(p.getAttrs(), "可开专票")) return false;
        if ("true".equals(intent.getAttributes().get("可定制Logo"))
                && !jsonBoolean(p.getAttrs(), "可定制Logo")) return false;
        if ("true".equals(intent.getAttributes().get("现货"))
                && (p.getStock() == null || p.getStock() <= 0)) return false;
        return true;
    }

    private double softRuleScore(Product p, IntentResult intent) {
        double score = 0;
        if (equalsIgnoreCase(intent.getCategory(), p.getCategory())) score += 0.25;
        if (equalsIgnoreCase(intent.getBrand(), p.getBrand())) score += 0.20;

        if (!intent.getScenes().isEmpty()) {
            int matches = 0;
            for (String scene : intent.getScenes()) {
                if (containsIgnoreCase(p.getSceneTags(), scene)) matches++;
            }
            score += 0.25 * matches / intent.getScenes().size();
        }

        if (!intent.getAttributes().isEmpty()) {
            int matches = 0;
            String searchable = safe(p.getTitle()) + " " + safe(p.getAttrs()) + " " + safe(p.getDescription());
            for (Map.Entry<String, String> entry : intent.getAttributes().entrySet()) {
                if ("true".equals(entry.getValue())) {
                    if (matchesBooleanAttribute(p, entry.getKey())) matches++;
                } else if (containsIgnoreCase(searchable, entry.getValue())) {
                    matches++;
                }
            }
            score += 0.20 * matches / intent.getAttributes().size();
        }

        if (!intent.getKeywords().isEmpty()) {
            String text = safe(p.getTitle()) + " " + safe(p.getDescription());
            int matches = 0;
            for (String keyword : intent.getKeywords()) {
                if (containsIgnoreCase(text, keyword)) matches++;
            }
            score += 0.10 * matches / intent.getKeywords().size();
        }
        return Math.min(1.0, score);
    }

    private boolean matchesBooleanAttribute(Product p, String key) {
        if ("积分购买".equals(key)) return Boolean.TRUE.equals(p.getPointsEligible());
        if ("现货".equals(key)) return p.getStock() != null && p.getStock() > 0;
        return jsonBoolean(p.getAttrs(), key);
    }

    private boolean jsonBoolean(String json, String field) {
        if (json == null || json.isEmpty()) return false;
        try {
            JsonNode value = objectMapper.readTree(json).get(field);
            return value != null && (value.asBoolean(false) || "true".equalsIgnoreCase(value.asText()));
        } catch (Exception ignored) {
            String compact = json.replace(" ", "");
            return compact.contains("\"" + field + "\":true")
                    || compact.contains("\"" + field + "\":\"true\"");
        }
    }

    private double budgetFit(BigDecimal price, BigDecimal min, BigDecimal max) {
        if (price == null) return 0;
        if (max != null && max.signum() > 0) {
            double ratio = price.doubleValue() / max.doubleValue();
            if (ratio > 1) return 0;
            return ratio >= 0.5 ? 1.0 : 0.4 + ratio;
        }
        if (min != null && min.signum() > 0) {
            double ratio = min.doubleValue() / price.doubleValue();
            return Math.max(0.4, Math.min(1.0, ratio));
        }
        return 0.5;
    }

    private double safeScore(Double score) {
        return score == null || score < 0 || score.isNaN() || score.isInfinite() ? 0 : score;
    }

    private boolean equalsIgnoreCase(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    private boolean containsIgnoreCase(String source, String target) {
        return source != null && target != null
                && source.toLowerCase(Locale.ROOT).contains(target.toLowerCase(Locale.ROOT));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
