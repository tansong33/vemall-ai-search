package cn.vetech.aimall.model.search;

import lombok.Data;

import java.math.BigDecimal;

/** 参数化数据库召回条件；任何用户输入都通过 MyBatis 占位符绑定。 */
@Data
public class SearchCriteria {
    private String productCode;
    private String afterId;
    private String category;
    private String categoryId;
    private String brand;
    private String brandId;
    private String tenantCode;
    private String channelCode;
    private BigDecimal priceMin;
    private BigDecimal priceMax;
    private BigDecimal requestedQuantity;
    private String searchText;
    /** SPU 全文候选上限；先缩小集合再关联 682 万 SKU。 */
    private int spuCandidateLimit;
    private int limit;

    /** 状态枚举尚可调整，所有值从配置复制到查询条件，SQL 不写死。 */
    private String notDeletedValue;
    private String spuOnState;
    private String spuAuditState;
    private String skuOnState;
    private String categoryDisplayedValue;
    private String brandEnabledValue;
}
