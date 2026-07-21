package cn.vetech.aimall.model.dto;

import lombok.Data;

/** 各阶段耗时，便于压测时直接定位慢点。 */
@Data
public class SearchTrace {
    private String route;
    private String nerSource;
    private String modelVersion;
    private String ruleVersion;
    private String indexVersion;
    private String cacheSource = "NONE";
    private int candidateCount;
    private long nerMs;
    private long databaseMs;
    private long ruleMs;
    private long totalMs;
}
