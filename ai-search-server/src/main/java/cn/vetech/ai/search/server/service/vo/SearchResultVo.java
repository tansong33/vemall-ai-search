package cn.vetech.ai.search.server.service.vo;

import java.util.ArrayList;
import java.util.List;

/**
 * DAO 返回给搜索服务的检索结果。
 */
public class SearchResultVo {

    private long total;
    private long rawTotal;
    private List<SearchItemVo> items = new ArrayList<SearchItemVo>();
    private FacetsVo facets = new FacetsVo();
    private long costMs;
    private String esDsl;

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public long getRawTotal() {
        return rawTotal;
    }

    public void setRawTotal(long rawTotal) {
        this.rawTotal = rawTotal;
    }

    public List<SearchItemVo> getItems() {
        return items;
    }

    public void setItems(List<SearchItemVo> items) {
        this.items = items;
    }

    public FacetsVo getFacets() {
        return facets;
    }

    public void setFacets(FacetsVo facets) {
        this.facets = facets;
    }

    public long getCostMs() {
        return costMs;
    }

    public void setCostMs(long costMs) {
        this.costMs = costMs;
    }

    public String getEsDsl() {
        return esDsl;
    }

    public void setEsDsl(String esDsl) {
        this.esDsl = esDsl;
    }
}
