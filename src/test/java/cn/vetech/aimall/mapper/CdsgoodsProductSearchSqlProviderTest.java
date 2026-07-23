package cn.vetech.aimall.mapper;

import cn.vetech.aimall.model.search.SearchCriteria;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class CdsgoodsProductSearchSqlProviderTest {

    @Test
    void fulltextRecallIsBoundedBeforeJoiningSkuAndStock() {
        SearchCriteria criteria = criteria();
        criteria.setSearchText("保温杯 商务");
        criteria.setCategoryId("0250020003");
        criteria.setPriceMax(new BigDecimal("100"));
        criteria.setRequestedQuantity(new BigDecimal("50"));

        String sql = new CdsgoodsProductSearchSqlProvider().search(criteria);

        assertThat(sql)
                .contains("with candidate_spu as")
                .contains("MATCH(p.pro_name, p.pro_suggestion, p.query_keywords, p.brand_name)")
                .contains("p.class_id = #{categoryId}")
                .contains("limit #{spuCandidateLimit}")
                .contains("join pro_sku sk on sk.spu_id = cs.spu_id")
                .contains("st.total_stock_num")
                .contains("st.locked_stock_num")
                .contains("row_number() over(partition by cs.spu_id")
                .contains("sk.sales_price <= #{priceMax}")
                .contains("p.min_purchase_num <= #{requestedQuantity}")
                .contains(">= #{requestedQuantity}")
                .contains("limit #{limit}")
                .doesNotContain("pro_supplier_sku_desc")
                .doesNotContain(" like ")
                .doesNotContain("保温杯 商务");
    }

    @Test
    void exactRouteAcceptsSpuSkuBarcodeAndSupplierSkuId() {
        SearchCriteria criteria = criteria();
        criteria.setProductCode("SKU-A01");

        String sql = new CdsgoodsProductSearchSqlProvider().findExact(criteria);

        assertThat(sql).contains("p.id = #{productCode}")
                .contains("exact_sku.id = #{productCode}")
                .contains("exact_sku.bar_code = #{productCode}")
                .contains("exact_sku.supplier_sku_id = #{productCode}");
    }

    private SearchCriteria criteria() {
        SearchCriteria criteria = new SearchCriteria();
        criteria.setLimit(200);
        criteria.setSpuCandidateLimit(1000);
        criteria.setNotDeletedValue("0");
        criteria.setSpuOnState("1");
        criteria.setSpuAuditState("1");
        criteria.setSkuOnState("1");
        criteria.setCategoryDisplayedValue("1");
        criteria.setBrandEnabledValue("1");
        return criteria;
    }
}
