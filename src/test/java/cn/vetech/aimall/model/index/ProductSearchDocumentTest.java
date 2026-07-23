package cn.vetech.aimall.model.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ProductSearchDocumentTest {
    @Test
    void serializesWithFrozenElasticsearchFieldNames() throws Exception {
        ProductSearchDocument document = new ProductSearchDocument();
        document.setSpuId("SPU-1");
        document.setMinPrice(new BigDecimal("79"));
        ProductSearchDocument.Sku sku = new ProductSearchDocument.Sku();
        sku.setSkuId("SKU-1");
        sku.setAvailableStock(new BigDecimal("10"));
        document.getSkus().add(sku);

        String json = new ObjectMapper().writeValueAsString(document);

        assertThat(json).contains("\"schema_version\":\"mall-spu-v1\"")
                .contains("\"spu_id\":\"SPU-1\"")
                .contains("\"min_price\":79")
                .contains("\"sku_id\":\"SKU-1\"")
                .contains("\"available_stock\":10");
    }
}
