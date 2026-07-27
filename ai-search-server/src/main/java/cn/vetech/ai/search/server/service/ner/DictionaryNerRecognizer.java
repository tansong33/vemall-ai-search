package cn.vetech.ai.search.server.service.ner;

import cn.vetech.ai.search.server.config.NerProperties;
import cn.vetech.ai.search.server.model.dto.NerEntity;
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
import java.util.Map;

@Component
public class DictionaryNerRecognizer implements NerRecognizer {

    private static final Logger log = LoggerFactory.getLogger(DictionaryNerRecognizer.class);
    private static final Map<String, Integer> PRIORITIES = priorities();

    private final NerProperties properties;
    private volatile Trie trie = Trie.builder().build();
    private volatile Map<String, String> labels = Collections.emptyMap();

    public DictionaryNerRecognizer(NerProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void initialize() {
        String location = properties.getDictionaryPath();
        Resource resource = location != null && location.startsWith("classpath:")
                ? new ClassPathResource(location.substring("classpath:".length()))
                : new FileSystemResource(location == null ? "" : location);
        if (!resource.exists()) {
            log.warn("NER dictionary does not exist: {}", location);
            return;
        }

        Trie.TrieBuilder builder = Trie.builder().ignoreOverlaps();
        Map<String, String> loaded = new HashMap<>();
        int rows = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                String[] parts = trimmed.split("\\t", 2);
                if (parts.length != 2) continue;
                String word = parts[0].trim();
                String label = parts[1].trim().toUpperCase();
                if (word.isEmpty()) continue;
                String existing = loaded.get(word);
                if (existing == null || priority(label) >= priority(existing)) {
                    loaded.put(word, label);
                }
                builder.addKeyword(word);
                rows++;
            }
            labels = Collections.unmodifiableMap(loaded);
            trie = builder.build();
            log.info("NER dictionary loaded: rows={}, unique={}", rows, loaded.size());
        } catch (Exception e) {
            log.error("Failed to load NER dictionary {}, dictionary NER remains empty", location, e);
        }
    }

    @Override
    public List<NerEntity> recognize(String query) {
        if (!StringUtils.hasText(query) || labels.isEmpty()) return Collections.emptyList();
        List<Candidate> candidates = new ArrayList<>();
        for (Emit emit : trie.parseText(query)) {
            String text = emit.getKeyword();
            int start = emit.getStart();
            int end = emit.getEnd() + 1;
            String label = labels.get(text);
            if (label != null) candidates.add(new Candidate(text, label, start, end));
        }
        candidates.sort(Comparator
                .comparingInt((Candidate item) -> priority(item.label)).reversed()
                .thenComparing(Comparator.comparingInt(Candidate::length).reversed())
                .thenComparingInt(item -> item.start));

        boolean[] occupied = new boolean[query.length()];
        List<NerEntity> entities = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (!free(occupied, candidate.start, candidate.end)) continue;
            occupy(occupied, candidate.start, candidate.end);
            entities.add(new NerEntity(candidate.text, candidate.label, candidate.start,
                    candidate.end, "dictionary", null));
        }
        entities.sort(Comparator.comparingInt(NerEntity::getStart));
        return entities;
    }

    @Override
    public String provider() {
        return "dictionary";
    }

    @Override
    public String modelVersion() {
        return "dictionary-" + labels.size();
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
        Integer value = PRIORITIES.get(label);
        return value == null ? 0 : value;
    }

    private static Map<String, Integer> priorities() {
        Map<String, Integer> values = new HashMap<>();
        values.put("BRAND", 6);
        values.put("CATEGORY", 5);
        values.put("PRODUCT", 4);
        values.put("MODEL", 3);
        values.put("ATTRIBUTE", 2);
        values.put("SPEC", 2);
        values.put("MODIFIER", 1);
        return values;
    }

    private static final class Candidate {
        private final String text;
        private final String label;
        private final int start;
        private final int end;

        private Candidate(String text, String label, int start, int end) {
            this.text = text;
            this.label = label;
            this.start = start;
            this.end = end;
        }

        private int length() {
            return end - start;
        }
    }
}
