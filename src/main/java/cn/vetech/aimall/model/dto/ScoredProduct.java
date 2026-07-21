package cn.vetech.aimall.model.dto;

import cn.vetech.aimall.model.entity.Product;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 召回/重排过程中的中间态：商品 + 各路分数 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ScoredProduct {
    private Product product;
    /** MySQL FULLTEXT 召回分归一化结果 [0,1]。 */
    private double databaseScore;
    /** 业务规则得分（类目/品牌/场景/属性/运营等）。 */
    private double ruleScore;
    /** 精排综合分 */
    private double finalScore;
}
