package com.tsong.aisearch.model.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;

public class PipelineRequest {

    @NotBlank(message = "query 不能为空")
    @Size(max = 200, message = "query 最长 200 个字符")
    private String query;
    private String sort = "default";
    private Map<String, Object> filters = new LinkedHashMap<>();

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
    public String getSort() { return sort; }
    public void setSort(String sort) { this.sort = sort; }
    public Map<String, Object> getFilters() { return filters; }
    public void setFilters(Map<String, Object> filters) {
        this.filters = filters == null ? new LinkedHashMap<String, Object>() : filters;
    }
}
