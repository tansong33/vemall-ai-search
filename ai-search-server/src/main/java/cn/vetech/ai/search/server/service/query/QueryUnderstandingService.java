package cn.vetech.ai.search.server.service.query;

import cn.vetech.ai.search.server.service.dto.QueryUnderstandDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Query 改写与同义词扩展。
 *
 * <p>规则来自词表文件而非硬编码 —— 这类词表会持续增加，每次改都要重新编译不可接受。</p>
 */
@Service
public class QueryUnderstandingService {

    private static final Logger log = LoggerFactory.getLogger(QueryUnderstandingService.class);

    private final String rewritePath;
    private final String synonymPath;

    private volatile Map<String, String> rewrites = Collections.emptyMap();
    private volatile Map<String, List<String>> synonyms = Collections.emptyMap();

    public QueryUnderstandingService() {
        this("classpath:query-rewrite.txt", "classpath:query-synonyms.txt");
    }

    QueryUnderstandingService(String rewritePath, String synonymPath) {
        this.rewritePath = rewritePath;
        this.synonymPath = synonymPath;
    }

    @PostConstruct
    public void initialize() {
        Map<String, String> loadedRewrites = new LinkedHashMap<String, String>();
        loadLines(rewritePath, new LineHandler() {
            @Override
            public void handle(String key, String value) {
                loadedRewrites.put(key.toLowerCase(Locale.ROOT), value);
            }
        });
        rewrites = Collections.unmodifiableMap(loadedRewrites);

        Map<String, List<String>> loadedSynonyms = new LinkedHashMap<String, List<String>>();
        loadLines(synonymPath, new LineHandler() {
            @Override
            public void handle(String key, String value) {
                List<String> words = new ArrayList<String>();
                for (String part : value.split(",")) {
                    String word = part.trim();
                    if (!word.isEmpty()) {
                        words.add(word);
                    }
                }
                if (!words.isEmpty()) {
                    loadedSynonyms.put(key, Collections.unmodifiableList(words));
                }
            }
        });
        synonyms = Collections.unmodifiableMap(loadedSynonyms);

        log.info("Query understanding loaded: rewrites={}, synonym triggers={}",
                rewrites.size(), synonyms.size());
    }

    public QueryUnderstandDto process(String query) {
        long started = System.nanoTime();
        QueryUnderstandDto result = new QueryUnderstandDto();
        result.setRewrittenQuery(rewrite(query));
        result.setSynonyms(expand(query));
        result.setCostMs((System.nanoTime() - started) / 1_000_000);
        return result;
    }

    private String rewrite(String query) {
        if (!StringUtils.hasText(query)) {
            return "";
        }
        String result = query;
        for (Map.Entry<String, String> entry : rewrites.entrySet()) {
            result = replaceIgnoreCase(result, entry.getKey(), entry.getValue());
        }
        return result;
    }

    private List<String> expand(String query) {
        List<String> result = new ArrayList<String>();
        if (!StringUtils.hasText(query)) {
            return result;
        }
        Set<String> collected = new LinkedHashSet<String>();
        for (Map.Entry<String, List<String>> entry : synonyms.entrySet()) {
            if (query.contains(entry.getKey())) {
                collected.addAll(entry.getValue());
            }
        }
        result.addAll(collected);
        return result;
    }

    private static String replaceIgnoreCase(String text, String target, String replacement) {
        StringBuilder builder = new StringBuilder();
        String lowerText = text.toLowerCase(Locale.ROOT);
        int position = 0;
        while (true) {
            int index = lowerText.indexOf(target, position);
            if (index < 0) {
                builder.append(text, position, text.length());
                return builder.toString();
            }
            builder.append(text, position, index).append(replacement);
            position = index + target.length();
        }
    }

    private void loadLines(String location, LineHandler handler) {
        Resource resource = location != null && location.startsWith("classpath:")
                ? new ClassPathResource(location.substring("classpath:".length()))
                : new FileSystemResource(location == null ? "" : location);
        if (!resource.exists()) {
            log.warn("Query understanding word list does not exist: {}", location);
            return;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int colon = trimmed.indexOf(':');
                if (colon <= 0 || colon >= trimmed.length() - 1) {
                    continue;
                }
                handler.handle(trimmed.substring(0, colon).trim(),
                        trimmed.substring(colon + 1).trim());
            }
        } catch (Exception e) {
            log.error("Failed to load query understanding word list: {}", location, e);
        }
    }

    private interface LineHandler {
        void handle(String key, String value);
    }
}
