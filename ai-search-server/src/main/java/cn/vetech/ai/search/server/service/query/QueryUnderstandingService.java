package cn.vetech.ai.search.server.service.query;

import cn.vetech.ai.search.server.model.dto.ModelResult;
import cn.vetech.ai.search.server.model.dto.NerEntity;
import cn.vetech.ai.search.server.model.dto.NerResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class QueryUnderstandingService {

    public ModelResult process(String query, NerResult nerResult) {
        long started = System.nanoTime();
        ModelResult result = new ModelResult();
        result.setRewrittenQuery(rewrite(query));
        result.setSynonyms(synonyms(query));
        result.setFieldBoosts(boosts(nerResult));
        result.setFilters(filters(nerResult));
        result.setCostMs((System.nanoTime() - started) / 1_000_000);
        return result;
    }

    private String rewrite(String query) {
        if (query == null) return "";
        return query.replace("5g", "5G").replace("256g", "256GB");
    }

    private List<String> synonyms(String query) {
        List<String> values = new ArrayList<>();
        if (query == null) return values;
        if (query.contains("充电宝") || query.contains("移动电源")) {
            values.add("充电宝");
            values.add("移动电源");
        }
        if (query.contains("插座") || query.contains("插排")) {
            values.add("插座");
            values.add("插排");
            values.add("排插");
        }
        return values;
    }

    private Map<String, Float> boosts(NerResult nerResult) {
        Map<String, Float> values = new LinkedHashMap<>();
        if (nerResult == null || nerResult.getEntities() == null) return values;
        for (NerEntity entity : nerResult.getEntities()) {
            if ("BRAND".equals(entity.getLabel())) values.put("brand_name", 3.0f);
            else if ("CATEGORY".equals(entity.getLabel()) || "PRODUCT_TYPE".equals(entity.getLabel())) {
                values.put("category_name", 2.0f);
            } else if ("ATTRIBUTE".equals(entity.getLabel()) || "ATTRIBUTE_VALUE".equals(entity.getLabel())
                    || "SPEC".equals(entity.getLabel())) {
                values.put("title", 1.5f);
            }
        }
        return values;
    }

    private List<ModelResult.FilterCondition> filters(NerResult nerResult) {
        List<ModelResult.FilterCondition> values = new ArrayList<>();
        if (nerResult == null || nerResult.getEntities() == null) return values;
        for (NerEntity entity : nerResult.getEntities()) {
            if ("ATTRIBUTE".equals(entity.getLabel()) || "ATTRIBUTE_VALUE".equals(entity.getLabel())
                    || "SPEC".equals(entity.getLabel())) {
                values.add(new ModelResult.FilterCondition("title", entity.getText()));
            }
        }
        return values;
    }
}
