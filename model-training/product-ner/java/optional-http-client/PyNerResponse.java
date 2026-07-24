package com.aisearch.ner.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Python /ner 响应。字段名对应 NER_FIELD_STYLE=camel（默认）。
 *
 * TODO(接入第一步)：把这个类和你现有的 NER DTO 对齐 —— 要么让现有 DTO 直接复用这里的
 * 字段名，要么在 HybridNerService 里做一次映射。不要两边各写一套。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PyNerResponse(
        String query,
        List<Entity> entities,
        /** model | dictionary | hybrid —— 这一条答案实际由谁产出 */
        String source,
        /** true 表示模型不可用或不自信，已由词典兜底 */
        boolean degraded,
        String modelVersion,
        String schemaVersion,
        double tookMs,
        String requestId) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entity(
            String text,
            String label,
            /** 原始 query 的字符下标，含首不含尾，可直接用于高亮 */
            int start,
            int end,
            double confidence,
            String source) {}
}
