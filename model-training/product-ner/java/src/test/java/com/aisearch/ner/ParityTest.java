package com.aisearch.ner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 跨语言一致性测试。fixture 由 Python 侧 make_java_bundle.py 生成，
 * 这里断言 Java 实现产出完全相同的结果。
 *
 * <p>这不是"锦上添花"的测试：归一化差一个字符、BIO 解码差一条规则，
 * 线上就会出现 offset 错位或实体丢失，而且极难排查。
 */
class ParityTest {

    private static Path bundle;
    private static ObjectMapper mapper;

    @BeforeAll
    static void setUp() {
        bundle = Path.of(System.getProperty("ner.bundle", "src/test/resources/ner-bundle"));
        mapper = new ObjectMapper();
    }

    @Test
    void normalizationMatchesPython() throws Exception {
        JsonNode fixture = mapper.readTree(
                Files.readString(bundle.resolve("fixture_normalization.json")));
        for (JsonNode row : fixture) {
            String raw = row.get("raw").asText();
            String expected = row.get("normalized").asText();
            String actual = TextNormalizer.normalize(raw);
            assertEquals(expected, actual, "normalisation differs for: " + raw);
            assertEquals(raw.length(), actual.length(), "length changed for: " + raw);
        }
    }

    @Test
    void endToEndDecodingMatchesPython() throws Exception {
        JsonNode fixture = mapper.readTree(Files.readString(bundle.resolve("fixture_decode.json")));
        try (OnnxNerService service = new OnnxNerService(bundle)) {
            for (JsonNode row : fixture) {
                String query = row.get("query").asText();
                List<NerEntity> actual = service.recognize(query);
                JsonNode expected = row.get("entities");

                assertEquals(expected.size(), actual.size(),
                        "entity count differs for query: " + query);
                for (int i = 0; i < expected.size(); i++) {
                    JsonNode e = expected.get(i);
                    NerEntity a = actual.get(i);
                    assertEquals(e.get("start").asInt(), a.start(), "start differs: " + query);
                    assertEquals(e.get("end").asInt(), a.end(), "end differs: " + query);
                    assertEquals(e.get("label").asText(), a.label(), "label differs: " + query);
                    assertEquals(e.get("text").asText(), a.text(), "surface differs: " + query);
                    assertTrue(Math.abs(e.get("confidence").asDouble() - a.confidence()) < 1e-3,
                            "confidence differs: " + query);
                }
            }
        }
    }

    @Test
    void offsetsIndexTheOriginalQuery() throws Exception {
        try (OnnxNerService service = new OnnxNerService(bundle)) {
            String query = "华为手机 256G 黑色";
            for (NerEntity e : service.recognize(query)) {
                assertEquals(e.text(), query.substring(e.start(), e.end()),
                        "offset does not index the original string");
            }
        }
    }
}
