package cn.vetech.aimall.service.suggestion;

import cn.vetech.aimall.model.dto.SuggestionItem;
import cn.vetech.aimall.model.search.DictionaryTerm;
import cn.vetech.aimall.repository.ProductCatalogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 小词典联想基线。约 1.1 万类目 + 2.2 万品牌可直接在内存做 contains；百万商品标题不可使用此实现。
 */
@Slf4j
@Component
public class DictionarySuggestionSource implements SuggestionSource {
    private final ProductCatalogRepository repository;
    private volatile List<Entry> snapshot = Collections.emptyList();

    public DictionarySuggestionSource(ProductCatalogRepository repository) {
        this.repository = repository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void afterApplicationReady() {
        refresh();
    }

    @Scheduled(fixedDelayString = "${aimall.suggestion.dictionary-refresh-ms:3600000}")
    public void refresh() {
        try {
            List<Entry> entries = new ArrayList<>();
            append(entries, repository.loadCategories(), "CATEGORY");
            append(entries, repository.loadBrands(), "BRAND");
            snapshot = Collections.unmodifiableList(entries);
            log.info("搜索联想词典刷新完成：候选 {}", entries.size());
        } catch (RuntimeException e) {
            log.warn("搜索联想词典刷新失败，继续使用上一版快照: {}", e.getMessage());
        }
    }

    @Override public String name() { return "dictionary"; }
    @Override public boolean isReady() { return !snapshot.isEmpty(); }

    @Override
    public List<SuggestionItem> suggest(String query, String tenantCode, String channelCode, int limit) {
        if (query == null || query.isEmpty() || limit <= 0) return Collections.emptyList();
        List<SuggestionItem> result = new ArrayList<>();
        for (Entry entry : snapshot) {
            Match best = match(entry.text, query, true);
            for (String alias : entry.aliases) {
                Match aliasMatch = match(alias, query, false);
                if (aliasMatch != null && (best == null || aliasMatch.score > best.score)) best = aliasMatch;
            }
            if (best != null) {
                result.add(new SuggestionItem(entry.text, entry.type, entry.id, best.matchedText,
                        best.position, best.score, name()));
            }
        }
        result.sort(Comparator.comparingDouble(SuggestionItem::getScore).reversed()
                .thenComparingInt(item -> item.getText().length())
                .thenComparing(SuggestionItem::getText));
        return new ArrayList<>(result.subList(0, Math.min(limit, result.size())));
    }

    private void append(List<Entry> target, List<DictionaryTerm> terms, String type) {
        if (terms == null) return;
        for (DictionaryTerm term : terms) {
            if (term == null || term.getName() == null || term.getName().trim().isEmpty()) continue;
            List<String> aliases = new ArrayList<>();
            if (term.getAliases() != null) {
                for (String alias : term.getAliases().split("[,，;；|/\\s]+")) {
                    if (!alias.trim().isEmpty()) aliases.add(alias.trim());
                }
            }
            target.add(new Entry(term.getId(), term.getName().trim(), type, aliases));
        }
    }

    private Match match(String candidate, String query, boolean canonical) {
        String normalized = normalize(candidate);
        int start = normalized.indexOf(query);
        if (start < 0) return null;
        String position;
        double score;
        if (normalized.equals(query)) {
            position = "EXACT";
            score = 1.00;
        } else if (start == 0) {
            position = "PREFIX";
            score = 0.95;
        } else if (start + query.length() == normalized.length()) {
            position = "SUFFIX";
            score = 0.90;
        } else {
            position = "INFIX";
            score = 0.85;
        }
        if (!canonical) score -= 0.03;
        return new Match(candidate, position, score);
    }

    private String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .trim().toLowerCase(Locale.ROOT);
    }

    private static final class Entry {
        private final String id;
        private final String text;
        private final String type;
        private final List<String> aliases;

        private Entry(String id, String text, String type, List<String> aliases) {
            this.id = id;
            this.text = text;
            this.type = type;
            this.aliases = aliases;
        }
    }

    private static final class Match {
        private final String matchedText;
        private final String position;
        private final double score;

        private Match(String matchedText, String position, double score) {
            this.matchedText = matchedText;
            this.position = position;
            this.score = score;
        }
    }
}
