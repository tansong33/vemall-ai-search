package cn.vetech.ai.search.server.service.ner;

import cn.vetech.ai.search.server.service.dto.NerEntityDto;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * NER 模型推理输出
 * 封装模型版本、推理耗时和识别出的实体列表。
 */
@Getter
public class NerModelOutput {

    private final String modelVersion;
    private final long costMs;
    private final List<NerEntityDto> entities;

    public NerModelOutput(String modelVersion, long costMs, List<NerEntityDto> entities) {
        this.modelVersion = modelVersion;
        this.costMs = costMs;
        this.entities = entities == null ? new ArrayList<>() : entities;
    }
}
