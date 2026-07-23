package cn.vetech.aimall.service.suggestion;

import cn.vetech.aimall.config.AiMallProperties;
import cn.vetech.aimall.model.dto.SuggestionItem;
import cn.vetech.aimall.model.dto.SuggestionResponse;
import cn.vetech.aimall.service.ner.RuleBasedNerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 聚合多个联想源、故障隔离、去重和统一限量。 */
@Slf4j
@Service
public class SearchSuggestionService {
    private final List<SuggestionSource> sources;
    private final AiMallProperties properties;

    public SearchSuggestionService(List<SuggestionSource> sources, AiMallProperties properties) {
        this.sources = sources;
        this.properties = properties;
    }

    public SuggestionResponse suggest(String rawQuery, String tenantCode, String channelCode, Integer requestedLimit) {
        long started = System.nanoTime();
        SuggestionResponse response = new SuggestionResponse();
        response.setQuery(rawQuery == null ? "" : rawQuery);
        response.setEnabled(properties.getSuggestion().isEnabled());
        if (!response.isEnabled()) return finish(response, started);

        String query = RuleBasedNerService.normalize(rawQuery);
        if (query.length() < Math.max(1, properties.getSuggestion().getMinQueryLength())) {
            return finish(response, started);
        }
        int configuredMax = Math.max(1, Math.min(properties.getSuggestion().getMaxResults(), 50));
        int limit = requestedLimit == null ? configuredMax : Math.max(1, Math.min(requestedLimit, configuredMax));
        Map<String, SuggestionItem> deduplicated = new LinkedHashMap<>();
        for (SuggestionSource source : sources) {
            if (!source.isReady()) continue;
            try {
                List<SuggestionItem> items = source.suggest(query, tenantCode, channelCode, limit);
                response.getSources().add(source.name());
                for (SuggestionItem item : items) {
                    String key = item.getType() + '|' + (StringUtils.hasText(item.getEntityId())
                            ? item.getEntityId() : item.getText().toLowerCase());
                    SuggestionItem previous = deduplicated.get(key);
                    if (previous == null || item.getScore() > previous.getScore()) deduplicated.put(key, item);
                }
            } catch (RuntimeException e) {
                log.warn("联想源失败，已跳过: source={} error={}", source.name(), e.getMessage());
            }
        }
        List<SuggestionItem> items = new ArrayList<>(deduplicated.values());
        items.sort(Comparator.comparingDouble(SuggestionItem::getScore).reversed()
                .thenComparingInt(item -> item.getText().length())
                .thenComparing(SuggestionItem::getText));
        response.setItems(new ArrayList<>(items.subList(0, Math.min(limit, items.size()))));
        return finish(response, started);
    }

    private SuggestionResponse finish(SuggestionResponse response, long started) {
        response.setTookMs((System.nanoTime() - started) / 1_000_000);
        return response;
    }
}
