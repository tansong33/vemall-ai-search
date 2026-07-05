package cn.vetech.aimall.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.vetech.aimall.llm.LlmClient;
import cn.vetech.aimall.model.dto.IntentResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 意图理解层：文字(+图片) -> 结构化意图 IntentResult。
 * 主路径：调用 LLM/VLM，要求严格 JSON 输出后解析。
 * 容错（不是 mock）：模型偶发超时/输出不合法时，构造"仅含原始关键词"的最简意图，
 * 让语义+关键词召回仍可工作、链路不 500；日志会明确标记走了容错。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentService {

    private final LlmClient llmClient;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final String INTENT_SYSTEM_PROMPT =
            "你是企业福利/集采商城的意图理解引擎。根据用户输入(可能附带图片)，输出严格 JSON，不要输出任何其他文字或markdown。" +
            "字段: category(目标类目,无则null), budgetMax(预算上限数字,无则null), " +
            "keywords(关键词数组), scenes(使用场景数组,从[夏季,冬季,员工福利,送礼,商务,年节,端午,中秋,春节,新人入职,办公,差旅,通勤,居家,健康,运动,户外,团建,劳保,防晒,降暑,亲子]中选), " +
            "attributes(属性约束对象,如{\"容量\":\"350ml\"}；B端条件也放这里,如{\"可开专票\":\"true\"},{\"可定制Logo\":\"true\"},{\"现货\":\"true\"}), " +
            "clarifications(缺失且影响推荐的关键信息数组,最多2条,无则空数组), " +
            "imageDescription(若有图片,用一句话描述图中商品的类型/颜色/材质/风格,无图则null)。" +
            "示例输入:'想给团队买夏天降暑的福利,预算50一个人' -> " +
            "{\"category\":null,\"budgetMax\":50,\"keywords\":[\"降暑\",\"福利\"],\"scenes\":[\"夏季\",\"员工福利\"]," +
            "\"attributes\":{},\"clarifications\":[\"团队人数\"],\"imageDescription\":null}" +
            "反例(禁止):输出任何解释文字、```json 包裹、或 JSON 之外的内容。";

    public IntentResult extract(String query, String imageBase64, String imageMime) {
        try {
            String raw = llmClient.chatWithImage(INTENT_SYSTEM_PROMPT,
                    (query == null || query.isEmpty()) ? "(用户只上传了图片)" : query,
                    imageBase64, imageMime);
            IntentResult r = parseJson(raw);
            if (r != null) {
                return r;
            }
            log.warn("意图输出解析失败，走容错最简意图。raw={}", raw);
        } catch (Exception e) {
            log.warn("意图理解调用异常，走容错最简意图: {}", e.getMessage());
        }
        return fallbackIntent(query);
    }

    /** 容错解析：剥掉可能的 ```json 包裹，再反序列化；失败返回 null 由上层兜底 */
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

    /** 容错最简意图：原句切词作关键词，语义召回直接用原句，保证链路可用 */
    private IntentResult fallbackIntent(String query) {
        IntentResult r = new IntentResult();
        if (query != null) {
            for (String piece : query.split("[\\s,，。;；!！?？]+")) {
                if (piece.length() >= 2 && r.getKeywords().size() < 5) {
                    r.getKeywords().add(piece);
                }
            }
        }
        return r;
    }
}
