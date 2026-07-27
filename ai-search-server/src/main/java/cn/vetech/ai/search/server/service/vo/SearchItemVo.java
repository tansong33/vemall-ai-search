package cn.vetech.ai.search.server.service.vo;

import lombok.Data;

/**
 * Elasticsearch 商品命中的服务层表示。
 */
@Data
public class SearchItemVo {

    private String skuId;
    private String spuId;
    private String title;
    private String highlightTitle;
    private String brandName;
    private String categoryName;
    private String imageUrl;
    private Long priceFen;
    private Boolean inStock;
    private Float score;
}
