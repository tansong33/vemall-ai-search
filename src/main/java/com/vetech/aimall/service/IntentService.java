package com.vetech.aimall.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vetech.aimall.llm.LlmClient;
import com.vetech.aimall.model.dto.IntentResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 意图理解层：文字(+图片) -> 结构化意图 IntentResult。
 *
 * 真实模式：调用 LLM/VLM，要求严格 JSON 输出后解析；解析失败自动降级到规则抽取。
 * mock 模式：直接走规则词典抽取（预算正则 / 类目词典 / 场景词典）。
 *
 * 这样设计保证：无论模型是否可用，链路都不会断，且真实与 mock 的输出结构完全一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentService {

    private final LlmClient llmClient;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 类目词典：query 关键词 -> 商品表 category。词典可持续扩充或替换为 LLM 归一化 */
    private static final Map<String, String> CATEGORY_DICT = new LinkedHashMap<>();
    /** 场景词典 */
    private static final Map<String, String> SCENE_DICT = new LinkedHashMap<>();

    static {
        CATEGORY_DICT.put("保温杯", "水杯");   CATEGORY_DICT.put("水杯", "水杯");
        CATEGORY_DICT.put("杯子", "水杯");     CATEGORY_DICT.put("水壶", "水杯");
        CATEGORY_DICT.put("风扇", "小家电");   CATEGORY_DICT.put("加湿器", "小家电");
        CATEGORY_DICT.put("茶叶", "茶叶");     CATEGORY_DICT.put("茶", "茶叶");
        CATEGORY_DICT.put("按摩", "按摩健康"); CATEGORY_DICT.put("筋膜枪", "按摩健康");
        CATEGORY_DICT.put("耳机", "数码");     CATEGORY_DICT.put("充电宝", "数码");
        CATEGORY_DICT.put("体脂秤", "数码");   CATEGORY_DICT.put("零食", "食品");
        CATEGORY_DICT.put("坚果", "食品");     CATEGORY_DICT.put("咖啡", "食品");
        CATEGORY_DICT.put("粽子", "食品");     CATEGORY_DICT.put("手套", "劳保用品");
        CATEGORY_DICT.put("口罩", "劳保用品"); CATEGORY_DICT.put("劳保", "劳保用品");
        CATEGORY_DICT.put("背包", "箱包");     CATEGORY_DICT.put("双肩包", "箱包");
        CATEGORY_DICT.put("凉席", "家纺");     CATEGORY_DICT.put("被子", "家纺");
        CATEGORY_DICT.put("伞", "生活日用");   CATEGORY_DICT.put("冰袖", "生活日用");
        CATEGORY_DICT.put("饭盒", "厨具餐具"); CATEGORY_DICT.put("腰靠", "办公用品");

        SCENE_DICT.put("夏", "夏季");     SCENE_DICT.put("降暑", "夏季");
        SCENE_DICT.put("消暑", "夏季");   SCENE_DICT.put("清凉", "夏季");
        SCENE_DICT.put("防晒", "防晒");   SCENE_DICT.put("福利", "员工福利");
        SCENE_DICT.put("团建", "团建");   SCENE_DICT.put("送礼", "送礼");
        SCENE_DICT.put("礼盒", "送礼");   SCENE_DICT.put("客户", "送礼");
        SCENE_DICT.put("办公", "办公");   SCENE_DICT.put("差旅", "差旅");
        SCENE_DICT.put("出差", "差旅");   SCENE_DICT.put("劳保", "劳保");
        SCENE_DICT.put("车间", "劳保");   SCENE_DICT.put("健康", "健康");
        SCENE_DICT.put("年节", "年节");   SCENE_DICT.put("端午", "端午");
        SCENE_DICT.put("入职", "员工福利");
    }

    /** 预算抽取正则：预算50 / 50元以内 / 不超过100 / 100块以下 / 人均50 */
    private static final Pattern BUDGET_P1 = Pattern.compile("(?:预算|不超过|最多|低于|控制在|人均)\\D{0,3}(\\d+(?:\\.\\d+)?)");
    private static final Pattern BUDGET_P2 = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:元|块)?\\s*(?:以内|以下|之内|左右)");

    private static final String INTENT_SYSTEM_PROMPT =
            "你是电商商城的意图理解引擎。根据用户输入(可能附带图片)，输出严格 JSON，不要输出任何其他文字或markdown。" +
            "字段: category(目标类目,无则null), budgetMax(预算上限数字,无则null), " +
            "keywords(关键词数组), scenes(使用场景数组,从[夏季,员工福利,送礼,办公,差旅,劳保,健康,团建,年节,防晒,运动,居家]中选), " +
            "attributes(属性约束对象,如{\"容量\":\"350ml\"}), clarifications(缺失的关键信息数组,最多2条), " +
            "imageDescription(若有图片,用一句话描述图中商品的类型/颜色/特征,无图则null)。" +
            "示例输入:'想给团队买夏天降暑的福利,预算50一个人' -> " +
            "{\"category\":null,\"budgetMax\":50,\"keywords\":[\"降暑\",\"福利\"],\"scenes\":[\"夏季\",\"员工福利\"]," +
            "\"attributes\":{},\"clarifications\":[\"团队人数\"],\"imageDescription\":null}";

    public IntentResult extract(String query, String imageBase64, String imageMime) {
        // 1) 真实模型优先
        if (llmClient.isReal()) {
            try {
                String raw = llmClient.chatWithImage(INTENT_SYSTEM_PROMPT,
                        query == null ? "(用户只上传了图片)" : query, imageBase64, imageMime);
                IntentResult r = parseJson(raw);
                if (r != null) {
                    return r;
                }
                log.warn("LLM 意图输出解析失败，降级到规则抽取。raw={}", raw);
            } catch (Exception e) {
                log.warn("LLM 意图理解异常，降级到规则抽取: {}", e.getMessage());
            }
        }
        // 2) 规则抽取（mock 模式主路径 / 真实模式降级路径）
        return ruleExtract(query, imageBase64 != null);
    }

    /** 容错解析：剥掉可能的 ```json 包裹，再反序列化 */
    private IntentResult parseJson(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            String cleaned = raw.trim()
                    .replaceAll("^```(?:json)?", "")
                    .replaceAll("```$", "")
                    .trim();
            JsonNode node = mapper.readTree(cleaned);
            IntentResult r = new IntentResult();
            if (node.hasNonNull("category")) r.setCategory(node.get("category").asText());
            if (node.hasNonNull("budgetMax")) r.setBudgetMax(new BigDecimal(node.get("budgetMax").asText()));
            node.path("keywords").forEach(k -> r.getKeywords().add(k.asText()));
            node.path("scenes").forEach(s -> r.getScenes().add(s.asText()));
            node.path("attributes").fields().forEachRemaining(e ->
                    r.getAttributes().put(e.getKey(), e.getValue().asText()));
            node.path("clarifications").forEach(c -> r.getClarifications().add(c.asText()));
            if (node.hasNonNull("imageDescription")) r.setImageDescription(node.get("imageDescription").asText());
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    /** 规则词典抽取（保证零依赖可跑通全链路） */
    private IntentResult ruleExtract(String query, boolean hasImage) {
        IntentResult r = new IntentResult();
        String q = query == null ? "" : query;

        // 类目
        for (Map.Entry<String, String> e : CATEGORY_DICT.entrySet()) {
            if (q.contains(e.getKey())) {
                r.setCategory(e.getValue());
                r.getKeywords().add(e.getKey());
                break;
            }
        }
        // 场景
        for (Map.Entry<String, String> e : SCENE_DICT.entrySet()) {
            if (q.contains(e.getKey()) && !r.getScenes().contains(e.getValue())) {
                r.getScenes().add(e.getValue());
            }
        }
        // 预算
        Matcher m1 = BUDGET_P1.matcher(q);
        Matcher m2 = BUDGET_P2.matcher(q);
        if (m1.find()) {
            r.setBudgetMax(new BigDecimal(m1.group(1)));
        } else if (m2.find()) {
            r.setBudgetMax(new BigDecimal(m2.group(1)));
        }
        // 关键词兜底：简单二字切分取高频词之外，直接把去停用词后的原句作为语义 query 即可，
        // 这里补充抽取 2~4 字连续中文片段中的名词性候选（简化处理）
        List<String> stop = Arrays.asList("我想", "想要", "帮我", "给我", "买点", "一些", "什么", "推荐", "有没有");
        String cleaned = q;
        for (String s : stop) cleaned = cleaned.replace(s, " ");
        for (String piece : cleaned.split("[\\s,，。;；!！?？]+")) {
            if (piece.length() >= 2 && r.getKeywords().size() < 5 && !r.getKeywords().contains(piece)) {
                r.getKeywords().add(piece);
            }
        }
        // 图片：mock 模式没有真实视觉能力，标注占位并提示澄清 —— 切换真实 VLM 后此字段由模型填充
        if (hasImage) {
            r.setImageDescription("[mock模式无法识别图片内容，请切换真实VLM]");
            r.getClarifications().add("请用文字补充图中商品的类型或特征（当前为mock模式）");
        }
        // 澄清：无类目且无场景且无预算时，需要反问
        if (r.getCategory() == null && r.getScenes().isEmpty() && r.getBudgetMax() == null && r.getKeywords().isEmpty()) {
            r.getClarifications().add("想买哪类商品？大概什么预算、什么场景使用？");
        }
        return r;
    }
}
