package com.tsong.aisearch.repository;

import com.tsong.aisearch.model.dto.ModelResult;
import com.tsong.aisearch.model.dto.SearchResult;

import java.util.Map;

public interface ProductSearchRepository {

    SearchResult search(String query, ModelResult modelResult, String sort,
                        Map<String, Object> filters);
}
