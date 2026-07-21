package cn.vetech.aimall.service.ner;

import cn.vetech.aimall.config.AiMallProperties;
import cn.vetech.aimall.model.dto.IntentResult;
import cn.vetech.aimall.model.dto.NerEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 规则与小模型的稳定切换点。价格、数量、SKU 和布尔约束始终以规则为准；模型只处理五类软实体。
 */
@Slf4j
@Primary
@Service
public class HybridIntentRecognizer implements IntentRecognizer {

    private final RuleBasedNerService ruleRecognizer;
    private final NerModelClient modelClient;
    private final NerConfidencePolicy confidencePolicy;
    private final AiMallProperties properties;

    public HybridIntentRecognizer(RuleBasedNerService ruleRecognizer,
                                  NerModelClient modelClient,
                                  NerConfidencePolicy confidencePolicy,
                                  AiMallProperties properties) {
        this.ruleRecognizer = ruleRecognizer;
        this.modelClient = modelClient;
        this.confidencePolicy = confidencePolicy;
        this.properties = properties;
    }

    @Override
    public IntentResult extract(String rawQuery) {
        String query = RuleBasedNerService.normalize(rawQuery);
        IntentResult rule = ruleRecognizer.extract(query);
        String mode = normalizedMode();
        rule.setNerSource("RULE");
        rule.setModelVersion("none");

        // 货号/ID 已经确定，不允许模型把精确路由改成模糊搜索。
        if (rule.getProductId() != null || "rule".equals(mode)) return rule;

        if ("shadow".equals(mode)) {
            if (selectedForShadow(query)) runShadow(query, rule);
            rule.setNerSource("RULE_SHADOW");
            return rule;
        }

        if (!modelClient.isReady()) {
            return fallback(rule, "model_not_ready", null);
        }

        try {
            NerModelOutput output = modelClient.predict(query);
            List<NerEntity> accepted = accepted(query, output);
            IntentResult target = "model".equals(mode) ? hardRuleSkeleton(rule) : rule;
            mergeModelEntities(target, accepted, "model".equals(mode));
            target.setModelEntities(accepted);
            target.setModelVersion(output.getModelVersion());
            target.setNerSource("model".equals(mode) ? "MODEL" : "HYBRID");
            return target;
        } catch (RuntimeException e) {
            if (!properties.getNer().isFallbackOnError()) throw e;
            return fallback(rule, "model_exception", e);
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("mode", normalizedMode());
        status.put("provider", modelClient.provider());
        status.put("modelVersion", modelClient.modelVersion());
        status.put("modelReady", modelClient.isReady());
        status.put("fallbackOnError", properties.getNer().isFallbackOnError());
        status.put("confidenceThresholds", properties.getNer().getConfidenceThresholds());
        return status;
    }

    private void runShadow(String query, IntentResult rule) {
        if (!modelClient.isReady()) return;
        try {
            NerModelOutput output = modelClient.predict(query);
            List<NerEntity> accepted = accepted(query, output);
            log.info("ner_shadow model={} entities={} ruleCategory={} ruleBrand={} inference={}ms",
                    output.getModelVersion(), accepted, rule.getCategory(), rule.getBrand(),
                    output.getInferenceMs());
        } catch (RuntimeException e) {
            log.warn("NER shadow 推理失败，主链路仍使用规则: provider={} error={}",
                    modelClient.provider(), e.getMessage());
        }
    }

    private List<NerEntity> accepted(String query, NerModelOutput output) {
        List<NerEntity> result = new ArrayList<>();
        if (output == null || output.getEntities() == null) return result;
        for (NerEntity entity : output.getEntities()) {
            if (confidencePolicy.accepts(query, entity)) {
                entity.setLabel(entity.getLabel().toUpperCase(Locale.ROOT));
                result.add(entity);
            }
        }
        return result;
    }

    private void mergeModelEntities(IntentResult target, List<NerEntity> entities, boolean preferModel) {
        for (NerEntity entity : entities) {
            String value = entity.getText();
            switch (entity.getLabel()) {
                case "CATEGORY":
                    if (preferModel || !StringUtils.hasText(target.getCategory())) target.setCategory(value);
                    addUnique(target.getKeywords(), value);
                    break;
                case "BRAND":
                    if (preferModel || !StringUtils.hasText(target.getBrand())) target.setBrand(value);
                    addUnique(target.getKeywords(), value);
                    break;
                case "PRODUCT_TYPE":
                    addUnique(target.getKeywords(), value);
                    break;
                case "SCENE":
                    addUnique(target.getScenes(), value);
                    addUnique(target.getKeywords(), value);
                    break;
                case "ATTRIBUTE_VALUE":
                    target.getAttributes().putIfAbsent("模型属性:" + value, value);
                    addUnique(target.getKeywords(), value);
                    break;
                default:
                    break;
            }
            appendSearchTerm(target, value);
        }
        if (!entities.isEmpty()) target.getClarifications().clear();
    }

    private IntentResult hardRuleSkeleton(IntentResult rule) {
        IntentResult result = new IntentResult();
        result.setProductId(rule.getProductId());
        result.setBudgetMin(rule.getBudgetMin());
        result.setBudgetMax(rule.getBudgetMax());
        result.setSearchText(rule.getSearchText());
        result.setRoute(rule.getRoute());
        result.setAttributes(new LinkedHashMap<>(rule.getAttributes()));
        result.setClarifications(new ArrayList<>(rule.getClarifications()));
        return result;
    }

    private IntentResult fallback(IntentResult rule, String reason, RuntimeException error) {
        rule.setNerSource("RULE_FALLBACK");
        rule.setModelVersion(modelClient.modelVersion());
        if (error == null) {
            log.debug("NER 模型不可用，回退规则: provider={} reason={}", modelClient.provider(), reason);
        } else {
            log.warn("NER 模型异常，回退规则: provider={} reason={} error={}",
                    modelClient.provider(), reason, error.getMessage());
        }
        return rule;
    }

    private boolean selectedForShadow(String query) {
        double rate = Math.max(0, Math.min(1, properties.getNer().getShadowSampleRate()));
        if (rate <= 0) return false;
        if (rate >= 1) return true;
        return Math.floorMod(query.hashCode(), 10_000) < (int) (rate * 10_000);
    }

    private String normalizedMode() {
        String mode = properties.getNer().getMode();
        if (mode == null) return "rule";
        String normalized = mode.trim().toLowerCase(Locale.ROOT);
        return "shadow".equals(normalized) || "hybrid".equals(normalized) || "model".equals(normalized)
                ? normalized : "rule";
    }

    private void appendSearchTerm(IntentResult result, String term) {
        if (!StringUtils.hasText(term)) return;
        String current = result.getSearchText();
        if (!StringUtils.hasText(current)) {
            result.setSearchText(term);
        } else if (!current.contains(term)) {
            result.setSearchText(current + " " + term);
        }
    }

    private void addUnique(List<String> values, String value) {
        if (StringUtils.hasText(value) && !values.contains(value)) values.add(value);
    }
}
