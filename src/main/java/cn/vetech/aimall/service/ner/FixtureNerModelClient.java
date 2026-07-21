package cn.vetech.aimall.service.ner;

import cn.vetech.aimall.config.AiMallProperties;
import cn.vetech.aimall.model.dto.NerEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 只用于联调框架的词条 fixture，不是机器学习模型。通过 aimall.ner.model-provider=fixture 开启。
 */
@Component
@ConditionalOnProperty(name = "aimall.ner.model-provider", havingValue = "fixture")
public class FixtureNerModelClient implements NerModelClient {

    private final AiMallProperties properties;
    private final List<Entry> entries = new ArrayList<>();

    public FixtureNerModelClient(AiMallProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void load() throws Exception {
        ClassPathResource resource = new ClassPathResource("models/ner/fixture-entities.tsv");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                String[] parts = trimmed.split("\\t");
                if (parts.length != 3) throw new IllegalArgumentException("非法 fixture NER 行: " + line);
                entries.add(new Entry(parts[0], parts[1], Double.parseDouble(parts[2])));
            }
        }
        entries.sort(Comparator.comparingInt((Entry e) -> e.text.length()).reversed());
    }

    @Override
    public NerModelOutput predict(String normalizedQuery) {
        long started = System.nanoTime();
        List<NerEntity> entities = new ArrayList<>();
        boolean[] occupied = new boolean[normalizedQuery.length()];
        for (Entry entry : entries) {
            int from = 0;
            while (from < normalizedQuery.length()) {
                int start = normalizedQuery.indexOf(entry.text, from);
                if (start < 0) break;
                int end = start + entry.text.length();
                if (free(occupied, start, end)) {
                    for (int i = start; i < end; i++) occupied[i] = true;
                    entities.add(new NerEntity(start, end, entry.text, entry.label,
                            entry.confidence, "fixture"));
                }
                from = Math.max(end, start + 1);
            }
        }
        entities.sort(Comparator.comparingInt(NerEntity::getStart));
        return new NerModelOutput(modelVersion(),
                (System.nanoTime() - started) / 1_000_000, entities);
    }

    private boolean free(boolean[] occupied, int start, int end) {
        for (int i = start; i < end; i++) if (occupied[i]) return false;
        return true;
    }

    @Override
    public boolean isReady() {
        return !entries.isEmpty();
    }

    @Override
    public String provider() {
        return "fixture";
    }

    @Override
    public String modelVersion() {
        String configured = properties.getNer().getModelVersion();
        return configured == null || configured.trim().isEmpty() || "none".equals(configured)
                ? "fixture-v1" : configured;
    }

    private static final class Entry {
        private final String label;
        private final String text;
        private final double confidence;

        private Entry(String label, String text, double confidence) {
            this.label = label;
            this.text = text;
            this.confidence = confidence;
        }
    }
}
