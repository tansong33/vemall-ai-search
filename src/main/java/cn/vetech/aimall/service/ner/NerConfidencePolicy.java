package cn.vetech.aimall.service.ner;

import cn.vetech.aimall.config.AiMallProperties;
import cn.vetech.aimall.model.dto.NerEntity;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** 按实体类型控制接纳阈值，并拒绝越界、未知标签和文本不一致的输出。 */
@Component
public class NerConfidencePolicy {

    private static final Set<String> LABELS = new HashSet<>(Arrays.asList(
            "CATEGORY", "BRAND", "PRODUCT_TYPE", "SCENE", "ATTRIBUTE_VALUE"));

    private final AiMallProperties properties;

    public NerConfidencePolicy(AiMallProperties properties) {
        this.properties = properties;
    }

    public boolean accepts(String query, NerEntity entity) {
        if (entity == null || entity.getLabel() == null || entity.getText() == null) return false;
        String label = entity.getLabel().toUpperCase(Locale.ROOT);
        if (!LABELS.contains(label)) return false;
        if (entity.getStart() < 0 || entity.getEnd() <= entity.getStart()
                || entity.getEnd() > query.length()) return false;
        if (!query.substring(entity.getStart(), entity.getEnd()).equals(entity.getText())) return false;
        Double threshold = properties.getNer().getConfidenceThresholds().get(label);
        return entity.getConfidence() >= (threshold == null ? 1.0 : threshold);
    }
}
