package cn.vetech.aimall.service.recall;

import cn.vetech.aimall.config.AiMallProperties;
import cn.vetech.aimall.model.dto.IntentResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ElasticsearchQueryFactoryTest {

    @Test
    void buildsTenantScopedNestedSkuQuery() {
        IntentResult intent = new IntentResult();
        intent.setSearchText("316不锈钢保温杯");
        intent.setCategoryId("0250020003");
        intent.setTenantCode("T001");
        intent.setBudgetMax(new BigDecimal("100"));
        intent.getAttributes().put("采购数量", "50");

        ObjectNode query = new ElasticsearchQueryFactory(
                new ObjectMapper(), new AiMallProperties()).build(intent);
        String json = query.toString();

        assertThat(json).contains("function_score", "multi_match", "category.id", "tenant_code")
                .contains("nested", "skus.available_stock", "skus.sales_price", "matched_sku")
                .contains("min_purchase_num")
                .contains("field_value_factor", "search_weight", "sales_count")
                .contains("316不锈钢保温杯")
                .doesNotContain("track_total_hits\":true");
    }
}
