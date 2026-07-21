package cn.vetech.aimall.service.ner;

import cn.vetech.aimall.mapper.ProductMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 类目/品牌实体词典。数据库只在启动完成后及定时刷新时访问，在线匹配只查内存 HashSet。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EntityDictionaryService {

    private final ProductMapper productMapper;
    private volatile DictionarySnapshot snapshot = DictionarySnapshot.empty();

    @EventListener(ApplicationReadyEvent.class)
    public void afterApplicationReady() {
        refresh();
    }

    @Scheduled(fixedDelayString = "${aimall.ner.dictionary-refresh-ms:3600000}")
    public void refresh() {
        try {
            List<String> categories = productMapper.selectDistinctCategories();
            List<String> brands = productMapper.selectDistinctBrands();
            snapshot = DictionarySnapshot.of(categories, brands);
            log.info("NER 词典刷新完成：类目 {}，品牌 {}", snapshot.categories.size(), snapshot.brands.size());
        } catch (Exception e) {
            // 数据库短时故障不清空旧快照，全文检索仍然可用。
            log.warn("NER 词典刷新失败，继续使用上一版词典: {}", e.getMessage());
        }
    }

    public String matchCategory(String normalizedQuery) {
        DictionarySnapshot s = snapshot;
        return longestMatch(normalizedQuery, s.categories, s.maxCategoryLength);
    }

    public String matchBrand(String normalizedQuery) {
        DictionarySnapshot s = snapshot;
        return longestMatch(normalizedQuery, s.brands, s.maxBrandLength);
    }

    private String longestMatch(String query, Set<String> dictionary, int maxLength) {
        if (query == null || query.isEmpty() || dictionary.isEmpty()) return null;
        int max = Math.min(maxLength, query.length());
        for (int len = max; len >= 2; len--) {
            for (int start = 0; start + len <= query.length(); start++) {
                String candidate = query.substring(start, start + len);
                if (dictionary.contains(candidate)) return candidate;
            }
        }
        return null;
    }

    private static final class DictionarySnapshot {
        private final Set<String> categories;
        private final Set<String> brands;
        private final int maxCategoryLength;
        private final int maxBrandLength;

        private DictionarySnapshot(Set<String> categories, Set<String> brands,
                                   int maxCategoryLength, int maxBrandLength) {
            this.categories = categories;
            this.brands = brands;
            this.maxCategoryLength = maxCategoryLength;
            this.maxBrandLength = maxBrandLength;
        }

        private static DictionarySnapshot empty() {
            return new DictionarySnapshot(Collections.<String>emptySet(),
                    Collections.<String>emptySet(), 0, 0);
        }

        private static DictionarySnapshot of(List<String> categoryList, List<String> brandList) {
            Set<String> categories = normalize(categoryList);
            Set<String> brands = normalize(brandList);
            return new DictionarySnapshot(categories, brands, maxLength(categories), maxLength(brands));
        }

        private static Set<String> normalize(List<String> source) {
            Set<String> result = new HashSet<>();
            if (source == null) return result;
            for (String value : source) {
                if (value == null) continue;
                String normalized = value.trim().toLowerCase(Locale.ROOT);
                if (normalized.length() >= 2 && normalized.length() <= 40) result.add(normalized);
            }
            return result;
        }

        private static int maxLength(Set<String> values) {
            int max = 0;
            for (String value : values) max = Math.max(max, value.length());
            return max;
        }
    }
}
