package cn.vetech.ai.search.server.model.dto;

import java.util.ArrayList;
import java.util.List;

public class NerResult {

    private List<NerEntity> entities = new ArrayList<>();
    private long costMs;
    private String provider;
    private String modelVersion;

    public List<NerEntity> getEntities() { return entities; }
    public void setEntities(List<NerEntity> entities) { this.entities = entities; }
    public long getCostMs() { return costMs; }
    public void setCostMs(long costMs) { this.costMs = costMs; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModelVersion() { return modelVersion; }
    public void setModelVersion(String modelVersion) { this.modelVersion = modelVersion; }
}
