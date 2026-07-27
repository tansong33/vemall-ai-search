package cn.vetech.ai.search.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 把真实执行的 ES DSL 翻译成人类可读的条件列表。
 *
 * <p>调试页的条件展示必须以实际下发的 DSL 为唯一来源 —— 另写一套构造逻辑，
 * 两边一旦漂移，调试页展示的就是假象。</p>
 */
@Component
public class EsDslExplainer {

    private static final Logger log = LoggerFactory.getLogger(EsDslExplainer.class);

    private final ObjectMapper objectMapper;

    public EsDslExplainer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 返回 must / should / must_not 三组描述；解析失败时三组均为空。 */
    public Clauses explain(String dsl) {
        Clauses clauses = new Clauses();
        if (!StringUtils.hasText(dsl)) {
            return clauses;
        }
        try {
            JsonNode bool = locateBoolQuery(objectMapper.readTree(dsl));
            if (bool != null) {
                clauses.must = describe(bool.get("must"));
                clauses.should = describe(bool.get("should"));
                clauses.mustNot = describe(bool.get("must_not"));
            }
        } catch (Exception e) {
            log.warn("解析 ES DSL 失败，条件列表留空: {}", e.getMessage());
        }
        return clauses;
    }

    /** 默认排序时 bool 被 function_score 包了一层，两种结构都要认。 */
    private JsonNode locateBoolQuery(JsonNode root) {
        JsonNode query = root.get("query");
        if (query == null) {
            return null;
        }
        if (query.has("bool")) {
            return query.get("bool");
        }
        JsonNode functionScore = query.get("function_score");
        if (functionScore != null && functionScore.has("query")
                && functionScore.get("query").has("bool")) {
            return functionScore.get("query").get("bool");
        }
        return null;
    }

    private List<String> describe(JsonNode clauses) {
        List<String> result = new ArrayList<String>();
        if (clauses == null) {
            return result;
        }
        if (clauses.isArray()) {
            for (JsonNode clause : clauses) {
                result.add(describeOne(clause));
            }
        } else {
            result.add(describeOne(clauses));
        }
        return result;
    }

    /** 压成一行，例如 match_phrase(title=手机壳)。 */
    private String describeOne(JsonNode clause) {
        if (clause == null || !clause.fields().hasNext()) {
            return String.valueOf(clause);
        }
        Map.Entry<String, JsonNode> type = clause.fields().next();
        JsonNode body = type.getValue();
        if (body != null && body.fields().hasNext()) {
            Map.Entry<String, JsonNode> field = body.fields().next();
            JsonNode value = field.getValue();
            String text = value != null && value.has("query")
                    ? value.get("query").asText() : String.valueOf(value).replace("\"", "");
            return type.getKey() + "(" + field.getKey() + "=" + text + ")";
        }
        return type.getKey();
    }

    /** 三组条件描述的载体。 */
    public static final class Clauses {
        private List<String> must = Collections.emptyList();
        private List<String> should = Collections.emptyList();
        private List<String> mustNot = Collections.emptyList();

        public List<String> getMust() { return must; }
        public List<String> getShould() { return should; }
        public List<String> getMustNot() { return mustNot; }
    }
}
