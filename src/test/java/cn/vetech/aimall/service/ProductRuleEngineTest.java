package cn.vetech.aimall.service;

import cn.vetech.aimall.config.AiMallProperties;
import cn.vetech.aimall.model.dto.IntentResult;
import cn.vetech.aimall.model.dto.ScoredProduct;
import cn.vetech.aimall.model.entity.Product;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProductRuleEngineTest {

    private final ProductRuleEngine engine = new ProductRuleEngine(new AiMallProperties(), new ObjectMapper());

    @Test
    void appliesHardRulesBeforeRanking() {
        IntentResult intent = new IntentResult();
        intent.setCategory("水杯");
        intent.setBudgetMax(new BigDecimal("100"));
        intent.getScenes().add("办公");
        intent.getAttributes().put("可开专票", "true");

        Product valid = product(1L, "办公保温杯", "80", 10, "{\"可开专票\":true}", "办公", 2.0);
        Product noInvoice = product(2L, "普通保温杯", "70", 10, "{\"可开专票\":false}", "办公", 3.0);
        Product overBudget = product(3L, "高端保温杯", "120", 10, "{\"可开专票\":true}", "办公", 4.0);

        List<ScoredProduct> result = engine.rank(Arrays.asList(noInvoice, overBudget, valid), intent);

        assertThat(result).extracting(it -> it.getProduct().getId()).containsExactly(1L);
        assertThat(result.get(0).getFinalScore()).isGreaterThan(0);
    }

    private Product product(Long id, String title, String price, int stock,
                            String attrs, String scenes, double searchScore) {
        Product p = new Product();
        p.setId(id);
        p.setTitle(title);
        p.setCategory("水杯");
        p.setPrice(new BigDecimal(price));
        p.setStock(stock);
        p.setAttrs(attrs);
        p.setSceneTags(scenes);
        p.setSearchScore(searchScore);
        p.setFeatured(false);
        return p;
    }
}
