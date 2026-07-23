package cn.vetech.aimall.model.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 意图理解层输出的结构化需求（槽位）。
 * 在线由规则/词典 NER 直接抽取，不调用大模型。后续数据库召回与规则精排都只消费此对象。
 */
@Data
public class IntentResult {

    /** 显式 SPU/SKU/条码/供应商货号；公司库主键为 varchar，不能用 Long。 */
    private String productId;

    /** 目标类目，可为空表示不限 */
    private String category;

    /** 词典命中后回填的分类编码；未命中词典时可为空。 */
    private String categoryId;

    /** 品牌实体，可为空。品牌词典由商品库离线/定时加载。 */
    private String brand;

    /** 词典命中后回填的品牌 ID；未命中词典时可为空。 */
    private String brandId;

    /** 多租户/多渠道检索边界，由请求上下文回填，不由 NER 预测。 */
    private String tenantCode;
    private String channelCode;

    /** 预算下限（元），null 表示未提及。 */
    private BigDecimal budgetMin;

    /** 预算上限（元），null 表示未提及 */
    private BigDecimal budgetMax;

    /** 去掉口语停用词和价格表达后的数据库全文检索文本。 */
    private String searchText;

    /** 路由提示：EXACT_ID / SEARCH。 */
    private String route = "SEARCH";

    /** 从 query 中抽出的结构化关键词，用于规则打分。 */
    private List<String> keywords = new ArrayList<>();

    /** 使用场景：夏季 / 员工福利 / 送礼 / 办公 / 劳保 ... */
    private List<String> scenes = new ArrayList<>();

    /** 其他属性约束：如 {"容量":"350ml"} */
    private Map<String, String> attributes = new LinkedHashMap<>();

    /** 缺失但影响推荐的关键信息，用于向用户反问澄清 */
    private List<String> clarifications = new ArrayList<>();

    /** 实际采用的识别来源：RULE / MODEL / HYBRID / RULE_FALLBACK。 */
    private String nerSource = "RULE";

    /** 参与本次识别的模型版本；纯规则链路为 none。 */
    private String modelVersion = "none";

    /** 通过置信度策略并参与合并的模型实体，便于 shadow 对比和离线回流。 */
    private List<NerEntity> modelEntities = new ArrayList<>();

}
