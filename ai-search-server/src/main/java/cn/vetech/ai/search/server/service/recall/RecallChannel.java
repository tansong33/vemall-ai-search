package cn.vetech.ai.search.server.service.recall;

import cn.vetech.ai.search.server.model.dto.ModelResult;
import cn.vetech.ai.search.server.model.dto.NerEntity;
import cn.vetech.ai.search.server.model.dto.SearchResult;

import java.util.List;
import java.util.Map;

public interface RecallChannel {

    String name();

    boolean isReady();

    SearchResult recall(String query, ModelResult modelResult, List<NerEntity> nerEntities, String sort,
                        Map<String, Object> filters);
}
