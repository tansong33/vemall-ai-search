package cn.vetech.ai.search.server.service.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Query 理解结果：改写后的查询词与同义词扩展。 */
@Data
public class QueryUnderstandDto {

    private String rewrittenQuery;
    private List<String> synonyms = new ArrayList<>();
    private long costMs;

    public void setSynonyms(List<String> synonyms) {
        this.synonyms = synonyms == null ? new ArrayList<>() : synonyms;
    }
}
