package cn.vetech.ai.search.server.service.vo;

import java.util.ArrayList;
import java.util.List;

/** 热搜词缓存预热结果。 */
public class CacheWarmupVo {

    private int total;
    private int succeeded;
    private int failed;
    private long costMs;
    private List<String> queries = new ArrayList<String>();

    public int getTotal() { return total; }
    public void setTotal(int total) { this.total = total; }
    public int getSucceeded() { return succeeded; }
    public void setSucceeded(int succeeded) { this.succeeded = succeeded; }
    public int getFailed() { return failed; }
    public void setFailed(int failed) { this.failed = failed; }
    public long getCostMs() { return costMs; }
    public void setCostMs(long costMs) { this.costMs = costMs; }
    public List<String> getQueries() { return queries; }
    public void setQueries(List<String> queries) { this.queries = queries; }
}
