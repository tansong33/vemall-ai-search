package cn.vetech.ai.search.server.model.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ModelResult {

    private String rewrittenQuery;
    private List<String> synonyms = new ArrayList<>();
    private Map<String, Float> fieldBoosts = new LinkedHashMap<>();
    private List<FilterCondition> filters = new ArrayList<>();
    private long costMs;

    public String getRewrittenQuery() { return rewrittenQuery; }
    public void setRewrittenQuery(String rewrittenQuery) { this.rewrittenQuery = rewrittenQuery; }
    public List<String> getSynonyms() { return synonyms; }
    public void setSynonyms(List<String> synonyms) { this.synonyms = synonyms; }
    public Map<String, Float> getFieldBoosts() { return fieldBoosts; }
    public void setFieldBoosts(Map<String, Float> fieldBoosts) { this.fieldBoosts = fieldBoosts; }
    public List<FilterCondition> getFilters() { return filters; }
    public void setFilters(List<FilterCondition> filters) { this.filters = filters; }
    public long getCostMs() { return costMs; }
    public void setCostMs(long costMs) { this.costMs = costMs; }

    public static class FilterCondition {
        private String field;
        private String value;

        public FilterCondition() {}
        public FilterCondition(String field, String value) {
            this.field = field;
            this.value = value;
        }

        public String getField() { return field; }
        public void setField(String field) { this.field = field; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
    }
}
