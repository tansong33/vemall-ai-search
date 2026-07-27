package cn.vetech.ai.search.server.service.dto;

/**
 * 搜索服务内部使用的命名实体。
 */
public class NerEntityDto {

    private String text;
    private String label;
    private int start;
    private int end;
    private Float confidence;
    private String normalizedText;
    private String normalizedId;
    private String normalizedType;

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
}
