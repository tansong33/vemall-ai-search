package com.vetech.aimall.model.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 意图理解层输出的结构化需求（槽位）。
 * 真实模式下由 LLM 按严格 JSON 输出并解析得到；mock 模式下由规则词典抽取。
 * 后续召回/过滤/生成全部围绕本对象展开——这是"让模型输出可被程序消费"的关键设计。
 */
@Data
public class IntentResult {

    /** 目标类目，可为空表示不限 */
    private String category;

    /** 预算上限（元），null 表示未提及 */
    private BigDecimal budgetMax;

    /** 从 query 中抽出的关键词（用于关键词召回与语义 query 构造） */
    private List<String> keywords = new ArrayList<>();

    /** 使用场景：夏季 / 员工福利 / 送礼 / 办公 / 劳保 ... */
    private List<String> scenes = new ArrayList<>();

    /** 其他属性约束：如 {"容量":"350ml"} */
    private Map<String, String> attributes = new LinkedHashMap<>();

    /** 缺失但影响推荐的关键信息，用于向用户反问澄清 */
    private List<String> clarifications = new ArrayList<>();

    /** 图片理解结果的文字描述（有图时填充） */
    private String imageDescription;

    /** 供检索使用的语义查询文本 */
    public String toSemanticQuery(String rawQuery) {
        StringBuilder sb = new StringBuilder(rawQuery == null ? "" : rawQuery);
        if (imageDescription != null) sb.append(' ').append(imageDescription);
        for (String s : scenes) sb.append(' ').append(s);
        if (category != null) sb.append(' ').append(category);
        return sb.toString();
    }
}
