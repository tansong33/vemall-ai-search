package cn.vetech.aimall.service.recall;

import cn.vetech.aimall.config.AiMallProperties;
import cn.vetech.aimall.model.dto.IntentResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;

/** 只负责生成参数值受控的 ES DSL，字段名均为代码常量。 */
@Component
@RequiredArgsConstructor
public class ElasticsearchQueryFactory {
    private final ObjectMapper objectMapper;
    private final AiMallProperties properties;

    public ObjectNode build(IntentResult intent) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("size", Math.max(1, Math.min(properties.getSearch().getCandidateLimit(), 1000)));
        body.put("track_total_hits", false);
        body.putArray("_source").add("spu_id").add("title").add("subtitle")
                .add("brand").add("category").add("min_price").add("max_price")
                .add("min_purchase_num").add("total_available_stock").add("image_url").add("attrs_flat")
                .add("search_weight").add("sales_count").add("rating")
                .add("tenant_code").add("channel_code");

        ObjectNode rootBool = objectMapper.createObjectNode();
        ArrayNode must = rootBool.putArray("must");
        ArrayNode filter = rootBool.putArray("filter");

        if (StringUtils.hasText(intent.getSearchText())) {
            ObjectNode textAlternatives = objectMapper.createObjectNode();
            ArrayNode textShould = textAlternatives.putArray("should");
            textShould.addObject().set("multi_match", rootTextQuery(intent.getSearchText()));
            textShould.add(buildSkuNested(intent, true, false));
            textAlternatives.put("minimum_should_match", 1);
            must.addObject().set("bool", textAlternatives);
        } else {
            must.addObject().set("match_all", objectMapper.createObjectNode());
        }

        termFilter(filter, "searchable", true);
        termFilter(filter, "has_stock", true);
        optionalTerm(filter, "tenant_code", intent.getTenantCode());
        optionalTerm(filter, "channel_code", intent.getChannelCode());
        optionalTerm(filter, "category.id", intent.getCategoryId());
        if (!StringUtils.hasText(intent.getCategoryId())) optionalTerm(filter, "category.name.raw", intent.getCategory());
        optionalTerm(filter, "brand.id", intent.getBrandId());
        if (!StringUtils.hasText(intent.getBrandId())) optionalTerm(filter, "brand.name.raw", intent.getBrand());
        BigDecimal requestedQuantity = decimalAttribute(intent, "采购数量");
        if (requestedQuantity != null) {
            ObjectNode minPurchase = objectMapper.createObjectNode();
            ArrayNode allowed = minPurchase.putArray("should");
            allowed.addObject().putObject("range").putObject("min_purchase_num").put("lte", requestedQuantity);
            allowed.addObject().putObject("bool").putArray("must_not")
                    .addObject().putObject("exists").put("field", "min_purchase_num");
            minPurchase.put("minimum_should_match", 1);
            filter.addObject().set("bool", minPurchase);
        }
        // inner_hits 从这条 eligibility 查询返回真正满足价格/库存约束的 SKU。
        filter.add(buildSkuNested(intent, false, true, requestedQuantity));

        ObjectNode functionScore = objectMapper.createObjectNode();
        functionScore.set("query", objectMapper.createObjectNode().set("bool", rootBool));
        functionScore.put("score_mode", "sum");
        functionScore.put("boost_mode", "sum");
        ArrayNode functions = functionScore.putArray("functions");
        fieldValueFactor(functions, "search_weight", 0.10, "log1p", 0);
        fieldValueFactor(functions, "sales_count", 0.02, "log1p", 0);
        fieldValueFactor(functions, "rating", 0.05, "sqrt", 0);
        body.set("query", objectMapper.createObjectNode().set("function_score", functionScore));
        return body;
    }

    private ObjectNode rootTextQuery(String text) {
        ObjectNode multiMatch = objectMapper.createObjectNode();
        multiMatch.put("query", text);
        multiMatch.put("type", "best_fields");
        multiMatch.put("operator", "or");
        multiMatch.putArray("fields").add("title^5").add("subtitle^2")
                .add("suggestion^2").add("query_keywords^4")
                .add("brand.name^3").add("brand.aliases^3")
                .add("category.name^3").add("category.keywords^2");
        return multiMatch;
    }

    private ObjectNode buildSkuNested(IntentResult intent, boolean requireText, boolean includeInnerHits) {
        return buildSkuNested(intent, requireText, includeInnerHits, decimalAttribute(intent, "采购数量"));
    }

    private ObjectNode buildSkuNested(IntentResult intent, boolean requireText, boolean includeInnerHits,
                                      BigDecimal requestedQuantity) {
        ObjectNode skuBool = objectMapper.createObjectNode();
        ArrayNode filters = skuBool.putArray("filter");
        rangeFilter(filters, "skus.available_stock", BigDecimal.ZERO, null, false);
        if (requestedQuantity != null) {
            rangeFilter(filters, "skus.available_stock", requestedQuantity, null, true);
        }
        if (intent.getBudgetMin() != null || intent.getBudgetMax() != null) {
            rangeFilter(filters, "skus.sales_price", intent.getBudgetMin(), intent.getBudgetMax(), true);
        }
        if (requireText && StringUtils.hasText(intent.getSearchText())) {
            ObjectNode mm = objectMapper.createObjectNode();
            mm.put("query", intent.getSearchText());
            mm.putArray("fields").add("skus.title^2").add("skus.spec_values");
            skuBool.putArray("must").addObject().set("multi_match", mm);
        }

        ObjectNode nested = objectMapper.createObjectNode();
        nested.put("path", "skus");
        nested.put("score_mode", "max");
        nested.set("query", objectMapper.createObjectNode().set("bool", skuBool));
        if (includeInnerHits) {
            ObjectNode innerHits = nested.putObject("inner_hits");
            innerHits.put("name", "matched_sku");
            innerHits.put("size", 1);
            innerHits.putArray("sort").addObject().putObject("skus.sort_value").put("order", "desc");
        }
        return objectMapper.createObjectNode().set("nested", nested);
    }

    private void optionalTerm(ArrayNode filters, String field, String value) {
        if (StringUtils.hasText(value)) termFilter(filters, field, value);
    }

    private void termFilter(ArrayNode filters, String field, Object value) {
        filters.addObject().putObject("term").putPOJO(field, value);
    }

    private void rangeFilter(ArrayNode filters, String field, BigDecimal min, BigDecimal max, boolean inclusiveMin) {
        ObjectNode range = filters.addObject().putObject("range").putObject(field);
        if (min != null) range.put(inclusiveMin ? "gte" : "gt", min);
        if (max != null) range.put("lte", max);
    }

    private void fieldValueFactor(ArrayNode functions, String field, double factor,
                                  String modifier, double missing) {
        ObjectNode value = functions.addObject().putObject("field_value_factor");
        value.put("field", field);
        value.put("factor", factor);
        value.put("modifier", modifier);
        value.put("missing", missing);
    }

    private BigDecimal decimalAttribute(IntentResult intent, String key) {
        try {
            String value = intent.getAttributes().get(key);
            return value == null ? null : new BigDecimal(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
