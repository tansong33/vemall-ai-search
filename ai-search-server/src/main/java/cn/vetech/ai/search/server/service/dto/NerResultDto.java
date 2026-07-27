package cn.vetech.ai.search.server.service.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索服务内部使用的 NER 识别结果。
 */
public class NerResultDto {

    private List<NerEntityDto> entities = new ArrayList<NerEntityDto>();
    private long costMs;
    private String provider;
    private String modelVersion;
    private String normalizationVersion;

    public List<NerEntityDto> getEntities() {
        return entities;
    }

    public void setEntities(List<NerEntityDto> entities) {
        this.entities = entities;
    }

    public long getCostMs() {
        return costMs;
    }

    public void setCostMs(long costMs) {
        this.costMs = costMs;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public void setModelVersion(String modelVersion) {
        this.modelVersion = modelVersion;
    }

    public String getNormalizationVersion() {
        return normalizationVersion;
    }

    public void setNormalizationVersion(String normalizationVersion) {
        this.normalizationVersion = normalizationVersion;
    }
}
