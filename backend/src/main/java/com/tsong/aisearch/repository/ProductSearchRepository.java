package com.tsong.aisearch.repository;

import com.tsong.aisearch.model.dto.ModelResult;
import com.tsong.aisearch.model.dto.NerEntity;
import com.tsong.aisearch.model.dto.SearchResult;

import java.util.List;
import java.util.Map;

public interface ProductSearchRepository {

    SearchResult search(String query, ModelResult modelResult, List<NerEntity> nerEntities, String sort,
                        Map<String, Object> filters);
}
