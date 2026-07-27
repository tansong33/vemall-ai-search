package cn.vetech.ai.search.server.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 加载品类配件排除词表并构造搜索链路使用的排除词。
 */
@Component
public class SearchExclusionConfig {

    private static final Logger log = LoggerFactory.getLogger(SearchExclusionConfig.class);

    private final SearchProperties.Search properties;
    private volatile List<String> suffixes = Collections.emptyList();
    private volatile Map<String, List<String>> categoryExclusions = Collections.emptyMap();
    private volatile Map<String, List<String>> synonymMap = Collections.emptyMap();

    public SearchExclusionConfig(SearchProperties searchProperties) {
        this.properties = searchProperties.getSearch();
    }

    @PostConstruct
    public void initialize() {
        loadSuffixes();
        loadCategoryExclusions();
        loadSynonyms();
    }

    private void loadSuffixes() {
        final List<String> loaded = new ArrayList<String>();
        loadLines(properties.getSuffixExclusionPath(), new LineHandler() {
            @Override
            public void handle(String line) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    loaded.add(trimmed);
                }
            }
        });
        suffixes = Collections.unmodifiableList(loaded);
        log.info("Category exclusion suffixes loaded: count={}", suffixes.size());
    }

    private void loadCategoryExclusions() {
        final Map<String, List<String>> loaded = new HashMap<String, List<String>>();
        loadLines(properties.getCategoryExclusionPath(), new LineHandler() {
            @Override
            public void handle(String line) {
                String trimmed = line.trim();
                int colonIdx = trimmed.indexOf(':');
                if (colonIdx <= 0 || colonIdx >= trimmed.length() - 1) {
                    return;
                }
                String category = trimmed.substring(0, colonIdx).trim();
                String exclusionsPart = trimmed.substring(colonIdx + 1).trim();
                String[] parts = exclusionsPart.split(",");
                List<String> exclusions = new ArrayList<String>();
                for (String part : parts) {
                    String word = part.trim();
                    if (!word.isEmpty()) {
                        exclusions.add(word);
                    }
                }
                if (!exclusions.isEmpty()) {
                    loaded.put(category, Collections.unmodifiableList(exclusions));
                }
            }
        });
        categoryExclusions = Collections.unmodifiableMap(loaded);
        log.info("Category-specific exclusions loaded: categories={}", categoryExclusions.size());
    }

    private void loadSynonyms() {
        final Map<String, List<String>> loaded = new HashMap<String, List<String>>();
        loadLines(properties.getSynonymPath(), new LineHandler() {
            @Override
            public void handle(String line) {
                String trimmed = line.trim();
                int colonIdx = trimmed.indexOf(':');
                if (colonIdx <= 0 || colonIdx >= trimmed.length() - 1) {
                    return;
                }
                String original = trimmed.substring(0, colonIdx).trim();
                String synonymsPart = trimmed.substring(colonIdx + 1).trim();
                String[] parts = synonymsPart.split(",");
                List<String> synonyms = new ArrayList<String>();
                for (String part : parts) {
                    String word = part.trim();
                    if (!word.isEmpty()) {
                        synonyms.add(word);
                    }
                }
                if (!synonyms.isEmpty()) {
                    loaded.put(original, Collections.unmodifiableList(synonyms));
                }
            }
        });
        synonymMap = Collections.unmodifiableMap(loaded);
        log.info("Exclusion synonyms loaded: entries={}", synonymMap.size());
    }

    private void loadLines(String location, LineHandler handler) {
        Resource resource = location != null && location.startsWith("classpath:")
                ? new ClassPathResource(location.substring("classpath:".length()))
                : new FileSystemResource(location == null ? "" : location);
        if (!resource.exists()) {
            log.warn("Exclusion file does not exist: {}", location);
            return;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(
                    resource.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                handler.handle(trimmed);
            }
        } catch (Exception e) {
            log.error("Failed to load exclusion file: {}", location, e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    public List<String> getSuffixExclusions() {
        return suffixes;
    }

    public List<String> getCategorySpecificExclusions(String categoryWord) {
        List<String> exclusions = categoryExclusions.get(categoryWord);
        return exclusions != null ? exclusions : Collections.<String>emptyList();
    }

    /**
     * 完整配件品类不能继续拼接后缀，否则会产生无意义的排除条件。
     */
    public List<String> getApplicableSuffixExclusions(String categoryWord) {
        if (!hasText(categoryWord)
                || !categoryExclusions.containsKey(categoryWord)
                || isCompleteAccessoryCategory(categoryWord)) {
            return Collections.emptyList();
        }
        return suffixes;
    }

    public List<String> buildExclusionTerms(String categoryWord) {
        if (!hasText(categoryWord)) {
            return Collections.emptyList();
        }
        Set<String> terms = new LinkedHashSet<String>();
        for (String suffix : getApplicableSuffixExclusions(categoryWord)) {
            addTermAndSynonyms(terms, categoryWord + suffix);
        }
        for (String exclusion : getCategorySpecificExclusions(categoryWord)) {
            addTermAndSynonyms(terms, exclusion);
        }
        return Collections.unmodifiableList(new ArrayList<String>(terms));
    }

    public List<String> getSynonyms(String exclusionTerm) {
        List<String> synonyms = synonymMap.get(exclusionTerm);
        return synonyms != null ? synonyms : Collections.<String>emptyList();
    }

    private void addTermAndSynonyms(Set<String> terms, String term) {
        terms.add(term);
        terms.addAll(getSynonyms(term));
    }

    private boolean isCompleteAccessoryCategory(String categoryWord) {
        String category = categoryWord.trim();
        for (String suffix : suffixes) {
            if (category.length() > suffix.length() && category.endsWith(suffix)) {
                return true;
            }
        }
        return category.length() > 1 && (category.endsWith("包") || category.endsWith("垫"));
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private interface LineHandler {
        void handle(String line);
    }
}
