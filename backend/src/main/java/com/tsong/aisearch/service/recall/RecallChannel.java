package com.tsong.aisearch.service.recall;

import com.tsong.aisearch.model.dto.ModelResult;
import com.tsong.aisearch.model.dto.SearchResult;

import java.util.Map;

public interface RecallChannel {

    String name();

    boolean isReady();

    SearchResult recall(String query, ModelResult modelResult, String sort,
                        Map<String, Object> filters);
}
