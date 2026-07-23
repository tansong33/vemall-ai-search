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

        Product valid = product("1", "办公保温杯", "80", 10, "{\"可开专票\":true}", "办公", 2.0);
        Product noInvoice = product("2", "普通保温杯", "70", 10, "{\"可开专票\":false}", "办公", 3.0);
        Product overBudget = product("3", "高端保温杯", "120", 10, "{\"可开专票\":true}", "办公", 4.0);

        List<ScoredProduct> result = engine.rank(Arrays.asList(noInvoice, overBudget, valid), intent);

        assertThat(result).extracting(it -> it.getProduct().getId()).containsExactly("1");
        assertThat(result.get(0).getFinalScore()).isGreaterThan(0);
    }

    @Test
    void purchaseQuantityRequiresEnoughStockAndCompatibleMinimumOrder() {
        IntentResult intent = new IntentResult();
        intent.getAttributes().put("采购数量", "100");
        Product valid = product("1", "团购水杯", "20", 120, "{}", "员工福利", 1.0);
        valid.setMinPurchaseNum(new BigDecimal("50"));
        Product insufficientStock = product("2", "库存不足", "20", 80, "{}", "员工福利", 1.0);
        Product minimumTooHigh = product("3", "起购过高", "20", 200, "{}", "员工福利", 1.0);
        minimumTooHigh.setMinPurchaseNum(new BigDecimal("200"));

        List<ScoredProduct> result = engine.rank(
                Arrays.asList(insufficientStock, minimumTooHigh, valid), intent);

        assertThat(result).extracting(it -> it.getProduct().getId()).containsExactly("1");
    }

    private Product product(String id, String title, String price, int stock,
                            String attrs, String scenes, double searchScore) {
        Product p = new Product();
        p.setId(id);
        p.setTitle(title);
        p.setCategory("水杯");
        p.setPrice(new BigDecimal(price));
        p.setStock(BigDecimal.valueOf(stock));
        p.setAttrs(attrs);
        p.setSceneTags(scenes);
        p.setSearchScore(searchScore);
        p.setFeatured(false);
        return p;
    }
}
