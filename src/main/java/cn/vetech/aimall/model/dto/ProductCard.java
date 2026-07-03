package cn.vetech.aimall.model.dto;

import lombok.Data;

import java.math.BigDecimal;

/** 返回给前端渲染的商品卡片 */
@Data
public class ProductCard {
    private Long id;
    private String title;
    private String category;
    private BigDecimal price;
    private String imageUrl;
    private String sceneTags;
    private Boolean pointsEligible;
    private Integer stock;
    /** 综合得分（调试用，前端可不展示） */
    private double score;
    /** 单品推荐理由（生成层填充） */
    private String reason;
}
