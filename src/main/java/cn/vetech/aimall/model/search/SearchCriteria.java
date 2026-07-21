package cn.vetech.aimall.model.search;

import lombok.Data;

import java.math.BigDecimal;

/** 参数化数据库召回条件；任何用户输入都通过 MyBatis 占位符绑定。 */
@Data
public class SearchCriteria {
    private String category;
    private String brand;
    private BigDecimal priceMin;
    private BigDecimal priceMax;
    private String searchText;
    private int limit;
}
