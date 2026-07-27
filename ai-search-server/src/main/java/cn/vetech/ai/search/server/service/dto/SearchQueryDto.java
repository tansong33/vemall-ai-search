package cn.vetech.ai.search.server.service.dto;

import cn.vetech.ai.search.fccapi.api.search.SearchRequest;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索服务内部使用的查询参数。
 */
@Data
public class SearchQueryDto {

    private String query;
    private String rewrittenQuery;
    private List<String> synonyms = new ArrayList<>();
    private List<NerEntityDto> entities = new ArrayList<>();
    private SearchRequest.Sort sort = SearchRequest.Sort.RELEVANCE;
    private SearchRequest.Filters filters = new SearchRequest.Filters();
    private int page = 1;
    private int pageSize = 20;
    private boolean includeEsDsl;
    /**
     * 调试链路专用：仍如实探测缓存状态，但不因命中而提前返回。
     * 否则缓存一热，调试页的 NER / ES 各段就全是空的，看起来和识别失败一模一样。
     */
    private boolean alwaysRunPipeline;
}
