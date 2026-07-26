package cn.vetech.ai.search.server.service.ner;

import cn.vetech.ai.search.server.config.AiSearchProperties;
import cn.vetech.ai.search.server.model.dto.NerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Primary
@Service
public class HybridNerRecognizer implements NerRecognizer {

    private static final Logger log = LoggerFactory.getLogger(HybridNerRecognizer.class);

    private final DictionaryNerRecognizer dictionary;
    private final NerModelClient model;
    private final AiSearchProperties properties;

    public HybridNerRecognizer(DictionaryNerRecognizer dictionary, NerModelClient model,
                               AiSearchProperties properties) {
        this.dictionary = dictionary;
        this.model = model;
        this.properties = properties;
    }

    @Override
    public List<NerEntity> recognize(String query) {
        if (!StringUtils.hasText(query)) return Collections.emptyList();
        String mode = normalizedMode();
        List<NerEntity> dictionaryEntities = dictionary.recognize(query);
        if ("dictionary".equals(mode) || !model.isReady()) return dictionaryEntities;

        try {
            List<NerEntity> modelEntities = model.predict(query).getEntities();
            if ("shadow".equals(mode)) {
                log.info("NER shadow query={}, dictionary={}, model={}",
                        query, dictionaryEntities.size(), modelEntities.size());
                return dictionaryEntities;
            }
            if ("model".equals(mode)) return modelEntities;
            return merge(modelEntities, dictionaryEntities);
        } catch (RuntimeException e) {
            log.warn("ONNX NER failed, falling back to dictionary: {}", e.getMessage());
            return dictionaryEntities;
        }
    }

    private List<NerEntity> merge(List<NerEntity> primary, List<NerEntity> fallback) {
        List<NerEntity> result = new ArrayList<>();
        if (primary != null) result.addAll(primary);
        if (fallback != null) {
            for (NerEntity candidate : fallback) {
                if (!overlapsAny(candidate, result)) result.add(candidate);
            }
        }
        result.sort(Comparator.comparingInt(NerEntity::getStart));
        return result;
    }

    private boolean overlapsAny(NerEntity candidate, List<NerEntity> selected) {
        for (NerEntity entity : selected) {
            if (candidate.getStart() < entity.getEnd() && entity.getStart() < candidate.getEnd()) {
                return true;
            }
        }
        return false;
    }

    private String normalizedMode() {
        String configured = properties.getNer().getMode();
        if (configured == null) return "hybrid";
        String value = configured.trim().toLowerCase(Locale.ROOT);
        if ("rule".equals(value)) return "dictionary";
        if ("dictionary".equals(value) || "model".equals(value)
                || "shadow".equals(value) || "hybrid".equals(value)) return value;
        return "hybrid";
    }

    @Override
    public String provider() {
        if (!model.isReady() || "dictionary".equals(normalizedMode())) return dictionary.provider();
        if ("model".equals(normalizedMode())) return model.provider();
        if ("shadow".equals(normalizedMode())) return "dictionary+onnx-shadow";
        return "onnx+dictionary";
    }

    @Override
    public String modelVersion() {
        return model.isReady() ? model.modelVersion() : dictionary.modelVersion();
    }
}
