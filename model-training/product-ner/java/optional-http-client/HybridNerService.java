package com.aisearch.ner.service;

import com.aisearch.ner.client.PythonNerClient;
import com.aisearch.ner.config.NerProperties;
import com.aisearch.ner.dto.PyNerResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.CRC32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 策略层：决定这一次查询用模型还是现有 Java 词典。
 *
 * 设计原则：<b>任何异常路径都必须落回 dictionaryNerService</b>。模型是增强，不是依赖。
 *
 * TODO(接入第一步)：把 DictionaryNerService / NerEntity 换成你仓库里真实的类型，
 * 并实现 toDomain() 的字段映射。
 */
@Service
public class HybridNerService {

    private static final Logger log = LoggerFactory.getLogger(HybridNerService.class);
    private static final Logger shadowLog = LoggerFactory.getLogger("ner_shadow_diff");

    private final PythonNerClient client;
    private final NerProperties props;
    private final DictionaryNerService dictionaryNerService; // 你现有的实现

    public HybridNerService(PythonNerClient client, NerProperties props,
                            DictionaryNerService dictionaryNerService) {
        this.client = client;
        this.props = props;
        this.dictionaryNerService = dictionaryNerService;
    }

    public List<NerEntity> recognize(String query, String requestId) {
        List<NerEntity> dictionaryResult = dictionaryNerService.recognize(query);

        // 1) 影子模式：线上永远返回词典结果，模型只写对比日志
        if (props.getShadow().isEnabled() && sampled(query, props.getShadow().getSampleRate())) {
            try {
                PyNerResponse model = client.recognize(query, requestId);
                logDiff(query, dictionaryResult, model);
            } catch (Exception e) {
                log.debug("shadow call failed (ignored): {}", e.toString());
            }
        }

        if (props.getMode() == NerProperties.Mode.DICTIONARY) {
            return dictionaryResult;
        }
        // 2) 灰度分流：同一 query 稳定落在同一侧
        if (!inGray(query)) {
            return dictionaryResult;
        }
        // 3) 走模型，失败即降级
        try {
            PyNerResponse model = client.recognize(query, requestId);
            if (model == null || model.entities() == null || model.entities().isEmpty()) {
                return dictionaryResult;
            }
            if (model.degraded()) {
                log.debug("model degraded, using its dictionary-backed answer");
            }
            return toDomain(model);
        } catch (Exception e) {
            log.warn("NER model path failed, using dictionary. cause={}", e.toString());
            return dictionaryResult;
        }
    }

    private boolean inGray(String query) {
        int pct = props.getGray().getPercentage();
        if (pct >= 100) return true;
        if (pct <= 0) return false;
        return Math.floorMod(hash(query), 100) < pct;
    }

    private boolean sampled(String query, double rate) {
        if (rate >= 1.0) return true;
        return Math.floorMod(hash(query), 1000) < (int) (rate * 1000);
    }

    private static int hash(String s) {
        CRC32 crc = new CRC32();
        crc.update(s.getBytes(StandardCharsets.UTF_8));
        return (int) crc.getValue();
    }

    private void logDiff(String query, List<NerEntity> dict, PyNerResponse model) {
        if (model == null) return;
        // 结构化输出，方便用 Kibana 直接聚合差异率
        shadowLog.info("query={} dict_n={} model_n={} model_version={} took_ms={} model={}",
                query, dict.size(), model.entities().size(), model.modelVersion(),
                model.tookMs(), model.entities());
    }

    private List<NerEntity> toDomain(PyNerResponse resp) {
        return resp.entities().stream()
                .map(e -> new NerEntity(e.text(), e.label(), e.start(), e.end()))
                .toList();
    }
}
