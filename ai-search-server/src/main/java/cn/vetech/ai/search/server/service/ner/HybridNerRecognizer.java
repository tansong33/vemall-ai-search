package cn.vetech.ai.search.server.service.ner;

import cn.vetech.ai.search.server.config.NerProperties;
import cn.vetech.ai.search.server.service.dto.NerEntityDto;
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

/**
 * 词典与模型融合的实体识别入口。
 *
 * <p>归一化在本类内部完成，而不是交给上层编排 —— 这样搜索链路与调试链路
 * 拿到的实体完全一致。</p>
 */
@Primary
@Service
public class HybridNerRecognizer implements NerRecognizer {

    private static final Logger log = LoggerFactory.getLogger(HybridNerRecognizer.class);

    private final DictionaryNerRecognizer dictionary;
    private final NerModelClient model;
    private final EntityNormalizer normalizer;
    private final NerProperties properties;

    public HybridNerRecognizer(DictionaryNerRecognizer dictionary, NerModelClient model,
                               NerProperties properties, EntityNormalizer normalizer) {
        this.dictionary = dictionary;
        this.model = model;
        this.properties = properties;
        this.normalizer = normalizer;
    }

    @Override
    public List<NerEntityDto> recognize(String query) {
        if (!StringUtils.hasText(query)) {
            return Collections.emptyList();
        }
        String mode = normalizedMode();
        List<NerEntityDto> dictionaryEntities = dictionary.recognize(query);
        if ("dictionary".equals(mode) || !model.isReady()) {
            return normalizer.normalize(query, dictionaryEntities);
        }

        try {
            List<NerEntityDto> modelEntities = model.predict(query).getEntities();
            if ("shadow".equals(mode)) {
                log.info("NER shadow query={}, dictionary={}, model={}",
                        query, dictionaryEntities.size(), modelEntities.size());
                return normalizer.normalize(query, dictionaryEntities);
            }
            if ("model".equals(mode)) {
                return normalizer.normalize(query, modelEntities);
            }
            return normalizer.normalize(query, merge(modelEntities, dictionaryEntities));
        } catch (RuntimeException e) {
            log.warn("ONNX NER failed, falling back to dictionary: {}", e.getMessage());
            return normalizer.normalize(query, dictionaryEntities);
        }
    }

    private List<NerEntityDto> merge(List<NerEntityDto> primary, List<NerEntityDto> fallback) {
        List<NerEntityDto> result = new ArrayList<>();
        if (primary != null) {
            result.addAll(primary);
        }
        if (fallback != null) {
            for (NerEntityDto candidate : fallback) {
                List<NerEntityDto> overlaps = overlappingEntities(candidate, result);
                if (shouldUseCompleteDictionaryCategory(candidate, overlaps)) {
                    result.removeAll(overlaps);
                    result.add(candidate);
                } else if (overlaps.isEmpty()) {
                    result.add(candidate);
                }
            }
        }
        result.sort(Comparator.comparingInt(NerEntityDto::getStart));
        return result;
    }

    private List<NerEntityDto> overlappingEntities(NerEntityDto candidate,
                                                   List<NerEntityDto> selected) {
        List<NerEntityDto> overlaps = new ArrayList<>();
        for (NerEntityDto entity : selected) {
            if (candidate.getStart() < entity.getEnd() && entity.getStart() < candidate.getEnd()) {
                overlaps.add(entity);
            }
        }
        return overlaps;
    }

    /**
     * RaNER 在短 Query 上可能把完整品类切成「适用场景 + 单字品类」，例如「瑜伽 + 垫」。
     * 词典给出包含这些碎片的完整品类时改用完整跨度 —— 否则配件排除会拿着碎片去构造
     * must_not，搜「手机」照样出手机壳。
     *
     * <p>品牌、型号、系列是强身份实体，任何情况下都不被本规则覆盖。</p>
     */
    private boolean shouldUseCompleteDictionaryCategory(NerEntityDto candidate,
                                                        List<NerEntityDto> overlaps) {
        if (!"CATEGORY".equals(candidate.getLabel()) || overlaps.isEmpty()
                || candidate.getEnd() - candidate.getStart() < 2) {
            return false;
        }
        boolean containsCategoryFragment = false;
        boolean strictlyWider = false;
        for (NerEntityDto entity : overlaps) {
            if (entity.getStart() < candidate.getStart() || entity.getEnd() > candidate.getEnd()) {
                return false;
            }
            String label = entity.getLabel();
            if ("BRAND".equals(label) || "MODEL".equals(label) || "SERIES".equals(label)) {
                return false;
            }
            if ("CATEGORY".equals(label)) {
                containsCategoryFragment = true;
            }
            if (candidate.getStart() < entity.getStart() || candidate.getEnd() > entity.getEnd()) {
                strictlyWider = true;
            }
        }
        return containsCategoryFragment && strictlyWider;
    }

    private String normalizedMode() {
        String configured = properties.getMode();
        if (configured == null) {
            return "hybrid";
        }
        String value = configured.trim().toLowerCase(Locale.ROOT);
        if ("rule".equals(value)) {
            return "dictionary";
        }
        if ("dictionary".equals(value) || "model".equals(value)
                || "shadow".equals(value) || "hybrid".equals(value)) {
            return value;
        }
        return "hybrid";
    }

    @Override
    public String provider() {
        String mode = normalizedMode();
        if (!model.isReady() || "dictionary".equals(mode)) {
            return dictionary.provider();
        }
        if ("model".equals(mode)) {
            return model.provider();
        }
        if ("shadow".equals(mode)) {
            return "dictionary+onnx-shadow";
        }
        return "onnx+dictionary";
    }

    @Override
    public String modelVersion() {
        return model.isReady() ? model.modelVersion() : dictionary.modelVersion();
    }

    /** 归一化词典版本，调试链路展示用。 */
    public String normalizationVersion() {
        return normalizer.version();
    }
}
