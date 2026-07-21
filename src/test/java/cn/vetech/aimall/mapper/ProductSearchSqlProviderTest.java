package cn.vetech.aimall.mapper;

import cn.vetech.aimall.model.search.SearchCriteria;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ProductSearchSqlProviderTest {

    @Test
    void buildsBoundedParameterizedFulltextSql() {
        SearchCriteria criteria = new SearchCriteria();
        criteria.setCategory("小家电");
        criteria.setPriceMax(new BigDecimal("50"));
        criteria.setSearchText("夏季 降暑");
        criteria.setLimit(200);

        String sql = new ProductSearchSqlProvider().search(criteria);

        assertThat(sql).contains("MATCH(p.title, p.brand, p.scene_tags, p.description)")
                .contains("p.category = #{category}")
                .contains("p.price <= #{priceMax}")
                .contains("limit #{limit}")
                .doesNotContain(" like ")
                .doesNotContain("夏季 降暑");
    }
}
