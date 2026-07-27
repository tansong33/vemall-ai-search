package cn.vetech.ai.search.server.service.dto;

import cn.vetech.ai.search.fccapi.api.search.SearchRequest;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索服务内部使用的查询参数。
 */
public class SearchQueryDto {

    private String query;
    private String rewrittenQuery;
    private List<String> synonyms = new ArrayList<String>();
    private List<NerEntityDto> entities = new ArrayList<NerEntityDto>();
    private SearchRequest.Sort sort = SearchRequest.Sort.RELEVANCE;
    private SearchRequest.Filters filters = new SearchRequest.Filters();
    private int page = 1;
    private int pageSize = 20;
    private boolean includeEsDsl;

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public String getRewrittenQuery() {
        return rewrittenQuery;
    }

    public void setRewrittenQuery(String rewrittenQuery) {
        this.rewrittenQuery = rewrittenQuery;
    }

    public List<String> getSynonyms() {
        return synonyms;
    }

    public void setSynonyms(List<String> synonyms) {
        this.synonyms = synonyms;
    }

    public List<NerEntityDto> getEntities() {
        return entities;
    }

    public void setEntities(List<NerEntityDto> entities) {
        this.entities = entities;
    }

    public SearchRequest.Sort getSort() {
        return sort;
    }

    public void setSort(SearchRequest.Sort sort) {
        this.sort = sort;
    }

    public SearchRequest.Filters getFilters() {
        return filters;
    }

    public void setFilters(SearchRequest.Filters filters) {
        this.filters = filters;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public boolean isIncludeEsDsl() {
        return includeEsDsl;
    }

    public void setIncludeEsDsl(boolean includeEsDsl) {
        this.includeEsDsl = includeEsDsl;
    }
}
