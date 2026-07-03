package com.vetech.aimall.model.dto;

import com.vetech.aimall.model.entity.Product;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 召回/重排过程中的中间态：商品 + 各路分数 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ScoredProduct {
    private Product product;
    /** 语义召回相似度 [0,1] */
    private double semanticScore;
    /** 关键词命中分 [0,1] */
    private double keywordScore;
    /** 精排综合分 */
    private double finalScore;
}
