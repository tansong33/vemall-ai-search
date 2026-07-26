package cn.vetech.ai.search.server.model.dto;

public class NerEntity {

    private String text;
    private String label;
    private int start;
    private int end;
    private String source;
    private Double confidence;

    public NerEntity() {}

    public NerEntity(String text, String label, int start, int end, String source, Double confidence) {
        this.text = text;
        this.label = label;
        this.start = start;
        this.end = end;
        this.source = source;
        this.confidence = confidence;
    }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public int getStart() { return start; }
    public void setStart(int start) { this.start = start; }
    public int getEnd() { return end; }
    public void setEnd(int end) { this.end = end; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }

    @Override
    public String toString() {
        return "NerEntity{" + text + '/' + label + '@' + start + ':' + end + ", source=" + source + '}';
    }
}
