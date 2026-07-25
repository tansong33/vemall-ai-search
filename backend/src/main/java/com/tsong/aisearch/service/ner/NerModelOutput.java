package com.tsong.aisearch.service.ner;

import com.tsong.aisearch.model.dto.NerEntity;

import java.util.ArrayList;
import java.util.List;

public class NerModelOutput {

    private final String modelVersion;
    private final long costMs;
    private final List<NerEntity> entities;

    public NerModelOutput(String modelVersion, long costMs, List<NerEntity> entities) {
        this.modelVersion = modelVersion;
        this.costMs = costMs;
        this.entities = entities == null ? new ArrayList<NerEntity>() : entities;
    }

    public String getModelVersion() { return modelVersion; }
    public long getCostMs() { return costMs; }
    public List<NerEntity> getEntities() { return entities; }
}
