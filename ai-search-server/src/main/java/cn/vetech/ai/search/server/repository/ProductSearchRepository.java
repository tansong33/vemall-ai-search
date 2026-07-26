package cn.vetech.ai.search.server.repository;

import cn.vetech.ai.search.server.model.dto.ModelResult;
import cn.vetech.ai.search.server.model.dto.NerEntity;
import cn.vetech.ai.search.server.model.dto.SearchResult;

import java.util.List;
import java.util.Map;

public interface ProductSearchRepository {

    SearchResult search(String query, ModelResult modelResult, List<NerEntity> nerEntities, String sort,
                        Map<String, Object> filters);
}
