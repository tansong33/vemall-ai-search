package cn.vetech.ai.search.server.service.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * DAO 返回给搜索服务的检索结果。
 */
@Data
public class SearchResultVo {

    private long total;
    private long rawTotal;
    private List<SearchItemVo> items = new ArrayList<>();
    private FacetsVo facets = new FacetsVo();
    private long costMs;
    private String esDsl;
}
