package cn.vetech.ai.search.server.service.dto;

/**
 * 搜索服务内部使用的命名实体。
 */
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

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public int getStart() {
        return start;
    }

    public void setStart(int start) {
        this.start = start;
    }

    public int getEnd() {
        return end;
    }

    public void setEnd(int end) {
        this.end = end;
    }

    public Float getConfidence() {
        return confidence;
    }

    public void setConfidence(Float confidence) {
        this.confidence = confidence;
    }

    public String getNormalizedText() {
        return normalizedText;
    }

    public void setNormalizedText(String normalizedText) {
        this.normalizedText = normalizedText;
    }

    public String getNormalizedId() {
        return normalizedId;
    }

    public void setNormalizedId(String normalizedId) {
        this.normalizedId = normalizedId;
    }

    public String getNormalizedType() {
        return normalizedType;
    }

    public void setNormalizedType(String normalizedType) {
        this.normalizedType = normalizedType;
    }

    public String getRawLabel() {
        return rawLabel;
    }

    public void setRawLabel(String rawLabel) {
        this.rawLabel = rawLabel;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getNormalizationSource() {
        return normalizationSource;
    }

    public void setNormalizationSource(String normalizationSource) {
        this.normalizationSource = normalizationSource;
    }
}
