package cn.vetech.ai.search.server.service.ner;

import cn.vetech.ai.search.server.service.dto.NerEntityDto;
import cn.vetech.ai.search.server.config.NerProperties;
import org.ahocorasick.trie.Emit;
import org.ahocorasick.trie.Trie;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Aho-Corasick 词典 NER。
 *
 * 基础词典负责大规模召回，覆盖词典负责别名、系列词和坏例修复。匹配阶段保留所有
 * 重叠候选，再按“显式优先级 -> 标签优先级 -> 长度 -> 起点”统一裁决，避免 Trie
 * 在内部提前丢弃更有业务价值的候选。
 *
 * 词典格式：
 * <pre>
 *   实体词\t标签
 *   实体词\t标签\t显式优先级
 * </pre>
 */
@Component
public class DictionaryNerRecognizer implements NerRecognizer {

    private static final Logger log = LoggerFactory.getLogger(DictionaryNerRecognizer.class);
    private static final Map<String, Integer> LABEL_PRIORITIES = priorities();

    private final NerProperties properties;
    private volatile Trie trie = Trie.builder().build();
    private volatile Map<String, DictionaryEntry> entries = Collections.emptyMap();

    public DictionaryNerRecognizer(NerProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void initialize() {
        Trie.TrieBuilder builder = Trie.builder().ignoreCase();
        Map<String, DictionaryEntry> loaded = new HashMap<String, DictionaryEntry>();
        int rows = load(properties.getDictionaryPath(), builder, loaded);
        rows += load(properties.getOverlayDictionaryPath(), builder, loaded);
        entries = Collections.unmodifiableMap(loaded);
        trie = builder.build();
        log.info("NER dictionary loaded: rows={}, unique={}, overlay={}",
                rows, loaded.size(), properties.getOverlayDictionaryPath());
    }

    private int load(String location, Trie.TrieBuilder builder,
                     Map<String, DictionaryEntry> loaded) {
        Resource resource = resource(location);
        if (resource == null || !resource.exists()) {
            if (StringUtils.hasText(location)) log.warn("NER dictionary does not exist: {}", location);
            return 0;
        }

        int rows = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String value = line.trim();
                if (value.isEmpty() || value.startsWith("#")) continue;
                String[] parts = value.split("\\t", -1);
                if (parts.length < 2) continue;
                String word = parts[0].trim();
                String label = parts[1].trim().toUpperCase(Locale.ROOT);
                if (!StringUtils.hasText(word) || !StringUtils.hasText(label)) continue;
                int explicitPriority = parts.length > 2 ? parsePriority(parts[2]) : 0;
                DictionaryEntry candidate = new DictionaryEntry(word, label, explicitPriority);
                String key = normalizeKeyword(word);
                DictionaryEntry existing = loaded.get(key);
                if (existing == null || ENTRY_COMPARATOR.compare(candidate, existing) < 0) {
                    loaded.put(key, candidate);
                }
                builder.addKeyword(word);
                rows++;
            }
        } catch (Exception e) {
            log.error("Failed to load NER dictionary {}", location, e);
        }
        return rows;
    }

    @Override
    public List<NerEntityDto> recognize(String query) {
        if (!StringUtils.hasText(query) || entries.isEmpty()) {
            return Collections.<NerEntityDto>emptyList();
        }

        List<Candidate> candidates = new ArrayList<Candidate>();
        for (Emit emit : trie.parseText(query)) {
            int start = emit.getStart();
            int end = emit.getEnd() + 1;
            if (start < 0 || end > query.length() || start >= end) continue;
            String matchedText = query.substring(start, end);
            DictionaryEntry entry = entries.get(normalizeKeyword(emit.getKeyword()));
            if (entry == null || !hasValidBoundary(query, start, end, entry.word)) continue;
            candidates.add(new Candidate(matchedText, entry.label, start, end,
                    entry.explicitPriority));
        }
        Collections.sort(candidates, CANDIDATE_COMPARATOR);

        boolean[] occupied = new boolean[query.length()];
        List<NerEntityDto> entities = new ArrayList<NerEntityDto>();
        for (Candidate candidate : candidates) {
            if (!free(occupied, candidate.start, candidate.end)) continue;
            occupy(occupied, candidate.start, candidate.end);
            entities.add(new NerEntityDto(candidate.text, candidate.label, candidate.start,
                    candidate.end, "dictionary", null));
        }
        Collections.sort(entities, new Comparator<NerEntityDto>() {
            @Override
            public int compare(NerEntityDto left, NerEntityDto right) {
                return Integer.compare(left.getStart(), right.getStart());
            }
        });
        return entities;
    }

    @Override
    public String provider() {
        return "dictionary";
    }

    @Override
    public String modelVersion() {
        return "dictionary-" + entries.size();
    }

    private static final Comparator<DictionaryEntry> ENTRY_COMPARATOR =
            new Comparator<DictionaryEntry>() {
                @Override
                public int compare(DictionaryEntry left, DictionaryEntry right) {
                    if (left.explicitPriority != right.explicitPriority) {
                        return Integer.compare(right.explicitPriority, left.explicitPriority);
                    }
                    int leftLabelPriority = priority(left.label);
                    int rightLabelPriority = priority(right.label);
                    if (leftLabelPriority != rightLabelPriority) {
                        return Integer.compare(rightLabelPriority, leftLabelPriority);
                    }
                    return Integer.compare(right.word.length(), left.word.length());
                }
            };

    private static final Comparator<Candidate> CANDIDATE_COMPARATOR =
            new Comparator<Candidate>() {
                @Override
                public int compare(Candidate left, Candidate right) {
                    if (left.explicitPriority != right.explicitPriority) {
                        return Integer.compare(right.explicitPriority, left.explicitPriority);
                    }
                    int leftLabelPriority = priority(left.label);
                    int rightLabelPriority = priority(right.label);
                    if (leftLabelPriority != rightLabelPriority) {
                        return Integer.compare(rightLabelPriority, leftLabelPriority);
                    }
                    if (left.length() != right.length()) {
                        return Integer.compare(right.length(), left.length());
                    }
                    return Integer.compare(left.start, right.start);
                }
            };

    private static boolean hasValidBoundary(String query, int start, int end, String keyword) {
        if (!isAsciiWord(keyword)) return true;
        boolean leftJoined = start > 0 && isAsciiLetterOrDigit(query.charAt(start - 1));
        boolean rightJoined = end < query.length() && isAsciiLetterOrDigit(query.charAt(end));
        return !leftJoined && !rightJoined;
    }

    private static boolean isAsciiLetterOrDigit(char value) {
        return value < 128 && Character.isLetterOrDigit(value);
    }

    private static boolean isAsciiWord(String value) {
        if (value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c > 127 || (!Character.isLetterOrDigit(c) && c != '-' && c != '_')) {
                return false;
            }
        }
        return true;
    }

    private static boolean free(boolean[] occupied, int start, int end) {
        for (int i = start; i < end && i < occupied.length; i++) {
            if (occupied[i]) return false;
        }
        return true;
    }

    private static void occupy(boolean[] occupied, int start, int end) {
        for (int i = start; i < end && i < occupied.length; i++) occupied[i] = true;
    }

    private static int priority(String label) {
        Integer value = LABEL_PRIORITIES.get(label);
        return value == null ? 0 : value;
    }

    private static int parsePriority(String value) {
        if (!StringUtils.hasText(value)) return 0;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String normalizeKeyword(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static Resource resource(String location) {
        if (!StringUtils.hasText(location)) return null;
        if (location.startsWith("classpath:")) {
            return new ClassPathResource(location.substring("classpath:".length()));
        }
        return new FileSystemResource(location);
    }

    private static Map<String, Integer> priorities() {
        Map<String, Integer> values = new HashMap<String, Integer>();
        values.put("BRAND", 17);
        values.put("CATEGORY", 16);
        values.put("PRODUCT", 15);
        values.put("SERIES", 14);
        values.put("MODEL", 13);
        values.put("COLOR", 12);
        values.put("MATERIAL", 11);
        values.put("SPEC", 10);
        values.put("FUNCTION", 9);
        values.put("STYLE", 8);
        values.put("AUDIENCE", 7);
        values.put("SCENE", 6);
        values.put("REGION", 5);
        values.put("ORGANIZATION", 4);
        values.put("PERSON", 3);
        values.put("ATTRIBUTE", 2);
        values.put("MODIFIER", 1);
        return values;
    }

    private static final class DictionaryEntry {
        private final String word;
        private final String label;
        private final int explicitPriority;

        private DictionaryEntry(String word, String label, int explicitPriority) {
            this.word = word;
            this.label = label;
            this.explicitPriority = explicitPriority;
        }
    }

    private static final class Candidate {
        private final String text;
        private final String label;
        private final int start;
        private final int end;
        private final int explicitPriority;

        private Candidate(String text, String label, int start, int end,
                          int explicitPriority) {
            this.text = text;
            this.label = label;
            this.start = start;
            this.end = end;
            this.explicitPriority = explicitPriority;
        }

        private int length() {
            return end - start;
        }
    }
}
