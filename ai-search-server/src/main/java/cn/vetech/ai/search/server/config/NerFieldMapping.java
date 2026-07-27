package cn.vetech.ai.search.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 集中管理 NER 标签到 Elasticsearch 字段及权重的映射。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ner")
public class NerFieldMapping {

    private Map<String, FieldWeight> fieldMapping = new HashMap<>();
    private Map<String, String> normalizationFieldMapping = new HashMap<>();

    @PostConstruct
    public void initDefaults() {
        fieldMapping.putIfAbsent("BRAND", new FieldWeight("brand_name", 50.0f));
        fieldMapping.putIfAbsent("CATEGORY", new FieldWeight("category_name", 50.0f));
        fieldMapping.putIfAbsent("ATTRIBUTE", new FieldWeight("attributes", 5.0f));
        fieldMapping.putIfAbsent("MODIFIER", new FieldWeight("tags", 10.0f));
        fieldMapping.putIfAbsent("MODEL", new FieldWeight("model_no", 30.0f));
        fieldMapping.putIfAbsent("PRODUCT", new FieldWeight("title", 20.0f));
        fieldMapping.putIfAbsent("SERIES", new FieldWeight("model_no", 20.0f));
        fieldMapping.putIfAbsent("COLOR", new FieldWeight("attributes", 8.0f));
        fieldMapping.putIfAbsent("MATERIAL", new FieldWeight("attributes", 8.0f));
        fieldMapping.putIfAbsent("SPEC", new FieldWeight("attributes", 8.0f));
        fieldMapping.putIfAbsent("FUNCTION", new FieldWeight("attributes", 6.0f));
        fieldMapping.putIfAbsent("STYLE", new FieldWeight("tags", 6.0f));
        fieldMapping.putIfAbsent("AUDIENCE", new FieldWeight("attributes", 5.0f));
        fieldMapping.putIfAbsent("SCENE", new FieldWeight("tags", 5.0f));
        fieldMapping.putIfAbsent("REGION", new FieldWeight("attributes", 4.0f));
        fieldMapping.putIfAbsent("PERSON", new FieldWeight("title", 3.0f));
        fieldMapping.putIfAbsent("ORGANIZATION", new FieldWeight("title", 3.0f));

        normalizationFieldMapping.putIfAbsent("product_series_id", "product_series_id");
        normalizationFieldMapping.putIfAbsent("model_id", "model_id");
        normalizationFieldMapping.putIfAbsent("brand_id", "brand_id");
        normalizationFieldMapping.putIfAbsent("standardized_power", "standardized_power");
    }

    public String getField(String nerLabel) {
        FieldWeight fieldWeight = fieldMapping.get(nerLabel);
        return fieldWeight != null ? fieldWeight.getField() : null;
    }

    public Float getBoost(String nerLabel) {
        FieldWeight fieldWeight = fieldMapping.get(nerLabel);
        return fieldWeight != null ? fieldWeight.getBoost() : 1.0f;
    }

    public FieldWeight getMapping(String nerLabel) {
        return fieldMapping.get(nerLabel);
    }

    public Map<String, FieldWeight> getAllMappings() {
        return Collections.unmodifiableMap(fieldMapping);
    }

    public String getNormalizationField(String normalizedType) {
        return normalizationFieldMapping.get(normalizedType);
    }

    @Data
    public static class FieldWeight {
        private String field;
        private Float boost;

        public FieldWeight() {
        }

        public FieldWeight(String field, Float boost) {
            this.field = field;
            this.boost = boost;
        }
    }
}
