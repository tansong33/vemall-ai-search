package cn.vetech.aimall.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 商品实体，对应 product 表。
 * 字段名与列名遵循 MyBatis-Plus 默认的下划线-驼峰映射（scene_tags -> sceneTags），无需逐列注解。
 */
@Data
@TableName("product")
public class Product {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;

    private String category;

    private String brand;

    private BigDecimal price;

    /** 核心属性 JSON 字符串，例如 {"容量":"350ml","颜色":"磨砂灰","可开专票":true} */
    private String attrs;

    /** 场景标签，逗号分隔：夏季,员工福利,送礼 */
    private String sceneTags;

    private Integer stock;

    /** 是否可用福利积分购买 */
    private Boolean pointsEligible;

    /** 是否主推商品（重排加权项之一） */
    private Boolean featured;

    private String imageUrl;

    private String description;

    /**
     * 商品的"语义文本表示"：标题+类目+品牌+标签+属性+描述 拼接后用于 embedding。
     * MyBatis-Plus 只映射字段不映射方法，此方法不参与持久化。
     * 最佳实践：离线用 LLM 把 description 扩写（含 Doc2Query），语义召回命中率显著提升。
     */
    public String toEmbeddingText() {
        StringBuilder sb = new StringBuilder();
        sb.append(title).append(' ').append(category).append(' ');
        if (brand != null) sb.append(brand).append(' ');
        if (sceneTags != null) sb.append(sceneTags.replace(',', ' ')).append(' ');
        if (attrs != null) sb.append(attrs).append(' ');
        if (description != null) sb.append(description);
        return sb.toString();
    }
}
