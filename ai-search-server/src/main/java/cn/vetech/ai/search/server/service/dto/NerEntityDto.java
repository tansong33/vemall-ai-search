package cn.vetech.ai.search.server.service.dto;

import lombok.Data;

/**
 * 搜索服务内部使用的命名实体。
 */
@Data
public class NerEntityDto {

    private String text;
    private String label;
    /** RaNER 映射前的原始标签，标签映射出问题时靠它定位 */
    private String rawLabel;
    private int start;
    private int end;
    /** 产出该实体的识别器：dictionary / onnx */
    private String source;
    private Float confidence;
    private String normalizedText;
    private String normalizedId;
    private String normalizedType;
    /** 归一化来源：dictionary / rule:power 等 */
    private String normalizationSource;

    public NerEntityDto() {
    }

    public NerEntityDto(String text, String label, int start, int end, String source) {
        this(text, label, start, end, source, null);
    }

    public NerEntityDto(String text, String label, int start, int end,
                        String source, Double confidence) {
        this.text = text;
        this.label = label;
        this.start = start;
        this.end = end;
        this.source = source;
        this.confidence = confidence == null ? null : confidence.floatValue();
    }

    /**
     * 返回用于检索的文本；归一化文本非空时优先使用。
     */
    public String searchText() {
        if (normalizedText != null && !normalizedText.trim().isEmpty()) {
            return normalizedText;
        }
        return text;
    }
}
