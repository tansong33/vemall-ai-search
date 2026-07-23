package cn.vetech.aimall.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 搜索链路统一商品对象。
 *
 * <p>一个对象代表一个 SPU 商品卡，并携带当前查询下最合适、可售的 SKU。demo 数据源直接映射
 * product 表；cdsgoods 数据源通过 SQL alias 组装该对象。这样规则引擎不依赖任何一套物理表结构。</p>
 */
@Data
@TableName("product")
public class Product {

    @TableId
    private String id;

    /** 最佳匹配 SKU；demo 数据源为空。 */
    @TableField(exist = false)
    private String skuId;

    private String title;

    private String category;

    @TableField(exist = false)
    private String categoryId;

    private String brand;

    @TableField(exist = false)
    private String brandId;

    private BigDecimal price;

    @TableField(exist = false)
    private BigDecimal minPrice;

    @TableField(exist = false)
    private BigDecimal maxPrice;

    /** SPU 最小起购量；为空表示沿用商城默认规则。 */
    @TableField(exist = false)
    private BigDecimal minPurchaseNum;

    /** 核心属性 JSON 字符串，例如 {"容量":"350ml","颜色":"磨砂灰","可开专票":true} */
    private String attrs;

    /** SKU 规格 JSON，例如 [{"ggmc":"颜色","ggVal":"黑色"}]。 */
    @TableField(exist = false)
    private String specs;

    /** 场景标签，逗号分隔：夏季,员工福利,送礼 */
    private String sceneTags;

    private BigDecimal stock;

    /** 是否可用福利积分购买 */
    private Boolean pointsEligible;

    /** 是否主推商品（重排加权项之一） */
    private Boolean featured;

    /** 商城已有的人工搜索权重。 */
    @TableField(exist = false)
    private BigDecimal searchWeight;

    @TableField(exist = false)
    private Long salesCount;

    @TableField(exist = false)
    private BigDecimal rating;

    @TableField(exist = false)
    private String barCode;

    @TableField(exist = false)
    private String tenantCode;

    @TableField(exist = false)
    private String channelCode;

    private String imageUrl;

    private String description;

    /** 数据库全文检索相关度，只用于一次查询的候选集，不落库。 */
    @TableField(exist = false)
    private Double searchScore;

}
