package com.aisearch.ner;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模型 + 现有 Java 词典的融合层（进程内版本，逻辑与 Python 的 nerkit/fusion.py 一致）。
 *
 * <p>策略：模型结果优先；词典只填模型没覆盖到的空缺，不允许覆盖模型；模型整体不自信时
 * 整条退回词典。<b>任何异常都退回词典</b> —— 模型是增强，不是依赖。
 *
 * <p>TODO(接入)：把 {@code DictionaryNerService} 换成你仓库里真实的类型。
 */
public class HybridNerFacade {

    private static final Logger log = LoggerFactory.getLogger(HybridNerFacade.class);

    public enum Mode { MODEL, DICTIONARY, HYBRID }

    private final OnnxNerService model;
    private final DictionaryNerService dictionary;   // 你现有的词典实现
    private volatile Mode mode;
    private final double tauFallback;

    public HybridNerFacade(OnnxNerService model, DictionaryNerService dictionary,
                           Mode mode, double tauFallback) {
        this.model = model;
        this.dictionary = dictionary;
        this.mode = mode;
        this.tauFallback = tauFallback;
    }

    public void setMode(Mode mode) {
        this.mode = mode;   // 配置中心可热切，出问题 10 秒回滚到 DICTIONARY
    }

    public List<NerEntity> recognize(String query) {
        List<NerEntity> dictResult = dictionary.recognize(query);
        if (mode == Mode.DICTIONARY) {
            return dictResult;
        }
        try {
            List<NerEntity> modelResult = model.recognize(query);
            if (modelResult.isEmpty()) {
                return dictResult;
            }
            double avg = modelResult.stream().mapToDouble(NerEntity::confidence).average().orElse(0);
            if (avg < tauFallback) {
                log.debug("model unsure (avg={}), falling back to dictionary for '{}'", avg, query);
                return dictResult;
            }
            if (mode == Mode.MODEL) {
                return modelResult;
            }
            // HYBRID：词典只填空缺
            List<NerEntity> merged = new ArrayList<>(modelResult);
            for (NerEntity d : dictResult) {
                boolean overlaps = modelResult.stream()
                        .anyMatch(m -> d.start() < m.end() && m.start() < d.end());
                if (!overlaps) {
                    merged.add(d);
                }
            }
            merged.sort((a, b) -> Integer.compare(a.start(), b.start()));
            return merged;
        } catch (Exception e) {
            log.warn("NER model path failed, using dictionary. cause={}", e.toString());
            return dictResult;
        }
    }
}
