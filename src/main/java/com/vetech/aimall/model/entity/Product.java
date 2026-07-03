package com.vetech.aimall.model.entity;

import lombok.Data;

import javax.persistence.*;
import java.math.BigDecimal;

/** 商品实体，对应 sql/init.sql 中的 product 表 */
@Data
@Entity
@Table(name = "product")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;

    private String category;

    private String brand;

    private BigDecimal price;

    /** 核心属性 JSON 字符串，例如 {"容量":"350ml","颜色":"磨砂灰"} */
    private String attrs;

    /** 场景标签，逗号分隔：夏季,员工福利,送礼 */
    @Column(name = "scene_tags")
    private String sceneTags;

    private Integer stock;

    /** 是否可用福利积分购买 */
    @Column(name = "points_eligible")
    private Boolean pointsEligible;

    /** 是否主推商品（重排加权项之一） */
    private Boolean featured;

    @Column(name = "image_url")
    private String imageUrl;

    private String description;

    /**
     * 商品的"语义文本表示"：标题+类目+标签+属性+描述 拼接后用于 embedding。
     * 最佳实践：离线用 LLM 把 description 扩写得更丰富，语义召回命中率会显著提升。
     */
    @Transient
    public String toEmbeddingText() {
        StringBuilder sb = new StringBuilder();
        sb.append(title).append(' ')
          .append(category).append(' ');
        if (brand != null) sb.append(brand).append(' ');
        if (sceneTags != null) sb.append(sceneTags.replace(',', ' ')).append(' ');
        if (attrs != null) sb.append(attrs).append(' ');
        if (description != null) sb.append(description);
        return sb.toString();
    }
}
