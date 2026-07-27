package cn.vetech.ai.search.server.service.dto;

import java.util.ArrayList;
import java.util.List;

/** Query 理解结果：改写后的查询词与同义词扩展。 */
public class QueryUnderstandDto {

    private String rewrittenQuery;
    private List<String> synonyms = new ArrayList<String>();
    private long costMs;

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
        this.synonyms = synonyms == null ? new ArrayList<String>() : synonyms;
    }

    public long getCostMs() {
        return costMs;
    }

    public void setCostMs(long costMs) {
        this.costMs = costMs;
    }
}
