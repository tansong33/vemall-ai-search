package cn.vetech.aimall.service.ner;

import cn.vetech.aimall.model.search.DictionaryTerm;
import cn.vetech.aimall.repository.ProductCatalogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 类目/品牌实体词典。数据库只在启动完成后及定时刷新时访问，在线匹配只查不可变快照 Map。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EntityDictionaryService {

    private final ProductCatalogRepository productRepository;
    private volatile DictionarySnapshot snapshot = DictionarySnapshot.empty();

    @EventListener(ApplicationReadyEvent.class)
    public void afterApplicationReady() {
        refresh();
    }

    @Scheduled(fixedDelayString = "${aimall.ner.dictionary-refresh-ms:3600000}")
    public void refresh() {
        try {
            List<DictionaryTerm> categories = productRepository.loadCategories();
            List<DictionaryTerm> brands = productRepository.loadBrands();
            snapshot = DictionarySnapshot.of(categories, brands);
            log.info("NER 词典刷新完成：类目 {}，品牌 {}", snapshot.categories.size(), snapshot.brands.size());
        } catch (Exception e) {
            // 数据库短时故障不清空旧快照，全文检索仍然可用。
            log.warn("NER 词典刷新失败，继续使用上一版词典: {}", e.getMessage());
        }
    }

    public String matchCategory(String normalizedQuery) {
        DictionarySnapshot s = snapshot;
        DictionaryTerm term = longestMatch(normalizedQuery, s.categories, s.maxCategoryLength);
        return term == null ? null : term.getName();
    }

    public String matchBrand(String normalizedQuery) {
        DictionarySnapshot s = snapshot;
        DictionaryTerm term = longestMatch(normalizedQuery, s.brands, s.maxBrandLength);
        return term == null ? null : term.getName();
    }

    public DictionaryTerm resolveCategory(String nameOrAlias) {
        return nameOrAlias == null ? null : snapshot.categories.get(normalize(nameOrAlias));
    }

    public DictionaryTerm resolveBrand(String nameOrAlias) {
        return nameOrAlias == null ? null : snapshot.brands.get(normalize(nameOrAlias));
    }

    private DictionaryTerm longestMatch(String query, Map<String, DictionaryTerm> dictionary, int maxLength) {
        if (query == null || query.isEmpty() || dictionary.isEmpty()) return null;
        int max = Math.min(maxLength, query.length());
        for (int len = max; len >= 2; len--) {
            for (int start = 0; start + len <= query.length(); start++) {
                String candidate = query.substring(start, start + len);
                DictionaryTerm term = dictionary.get(candidate);
                if (term != null) return term;
            }
        }
        return null;
    }

    private static final class DictionarySnapshot {
        private final Map<String, DictionaryTerm> categories;
        private final Map<String, DictionaryTerm> brands;
        private final int maxCategoryLength;
        private final int maxBrandLength;

        private DictionarySnapshot(Map<String, DictionaryTerm> categories, Map<String, DictionaryTerm> brands,
                                   int maxCategoryLength, int maxBrandLength) {
            this.categories = categories;
            this.brands = brands;
            this.maxCategoryLength = maxCategoryLength;
            this.maxBrandLength = maxBrandLength;
        }

        private static DictionarySnapshot empty() {
            return new DictionarySnapshot(Collections.<String, DictionaryTerm>emptyMap(),
                    Collections.<String, DictionaryTerm>emptyMap(), 0, 0);
        }

        private static DictionarySnapshot of(List<DictionaryTerm> categoryList, List<DictionaryTerm> brandList) {
            Map<String, DictionaryTerm> categories = terms(categoryList);
            Map<String, DictionaryTerm> brands = terms(brandList);
            return new DictionarySnapshot(categories, brands, maxLength(categories), maxLength(brands));
        }

        private static Map<String, DictionaryTerm> terms(List<DictionaryTerm> source) {
            Map<String, DictionaryTerm> result = new HashMap<>();
            if (source == null) return result;
            for (DictionaryTerm term : source) {
                if (term == null || term.getName() == null) continue;
                put(result, term.getName(), term);
                if (term.getAliases() != null) {
                    for (String alias : term.getAliases().split("[,，;；|/\\s]+")) put(result, alias, term);
                }
            }
            return result;
        }

        private static void put(Map<String, DictionaryTerm> result, String value, DictionaryTerm term) {
            String normalized = normalize(value);
            if (normalized.length() >= 2 && normalized.length() <= 40) result.put(normalized, term);
        }

        private static int maxLength(Map<String, DictionaryTerm> values) {
            int max = 0;
            for (String value : values.keySet()) max = Math.max(max, value.length());
            return max;
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
