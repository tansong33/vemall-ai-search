package com.tsong.aisearch.service.recall;

import com.tsong.aisearch.model.dto.ModelResult;
import com.tsong.aisearch.model.dto.SearchResult;
import com.tsong.aisearch.repository.ProductSearchRepository;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ElasticsearchRecallChannel implements RecallChannel {

    private final ProductSearchRepository repository;

    public ElasticsearchRecallChannel(ProductSearchRepository repository) {
        this.repository = repository;
    }

    @Override
    public String name() {
        return "elasticsearch";
    }

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public SearchResult recall(String query, ModelResult modelResult, String sort,
                               Map<String, Object> filters) {
        return repository.search(query, modelResult, sort, filters);
    }
}
