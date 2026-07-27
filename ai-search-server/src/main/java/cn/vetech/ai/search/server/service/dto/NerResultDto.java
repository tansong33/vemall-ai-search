package cn.vetech.ai.search.server.service.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索服务内部使用的 NER 识别结果。
 */
@Data
public class NerResultDto {

    private List<NerEntityDto> entities = new ArrayList<>();
    private long costMs;
    private String provider;
    private String modelVersion;
    private String normalizationVersion;
}
