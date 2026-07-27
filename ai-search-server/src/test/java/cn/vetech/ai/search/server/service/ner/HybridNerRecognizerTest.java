package cn.vetech.ai.search.server.service.ner;

import cn.vetech.ai.search.server.service.dto.NerEntityDto;
import cn.vetech.ai.search.server.config.NerProperties;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for hybrid (model + dictionary) NER recognition.
 *
 * Uses Mockito to mock DictionaryNerRecognizer and a manual anonymous
 * NerModelClient for the model layer. Verifies merge logic: model wins,
 * dictionary fills non-overlapping entities.
 */
class HybridNerRecognizerTest {

    @Test
    void modelWinsAndDictionaryFillsNonOverlappingEntities() {
        // ── Arrange ──
        NerProperties properties = new NerProperties();
        properties.setMode("hybrid");

        DictionaryNerRecognizer dictionary = Mockito.mock(DictionaryNerRecognizer.class);
        Mockito.when(dictionary.recognize("公牛插座"))
                .thenReturn(Collections.singletonList(
                        new NerEntityDto("插座", "CATEGORY", 2, 4, "dictionary")));

        NerModelClient model = new NerModelClient() {
            @Override
            public NerModelOutput predict(String query) {
                NerEntityDto entity = new NerEntityDto("公牛", "BRAND", 0, 2, "onnx");
                return new NerModelOutput("test-v1", 0,
                        Collections.singletonList(entity));
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public String provider() {
                return "onnx";
            }

            @Override
            public String modelVersion() {
                return "test-v1";
            }
        
            @Override
            public String unavailableReason() {
                return null;
            }
        };

        EntityNormalizer normalizer = Mockito.mock(EntityNormalizer.class);
        Mockito.when(normalizer.normalize(Mockito.anyString(), Mockito.anyList()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        HybridNerRecognizer recognizer = new HybridNerRecognizer(
                dictionary, model, properties, normalizer);

        // ── Act ──
        List<NerEntityDto> entities = recognizer.recognize("公牛插座");

        // ── Assert ──
        assertThat(entities).extracting(NerEntityDto::getText)
                .containsExactly("公牛", "插座");
        assertThat(entities.get(0).getSource()).isEqualTo("onnx");
        assertThat(entities.get(1).getSource()).isEqualTo("dictionary");
    }

    @Test
    void completeDictionaryCategoryReplacesRanerFragments() {
        NerProperties properties = new NerProperties();
        properties.setMode("hybrid");

        DictionaryNerRecognizer dictionary = Mockito.mock(DictionaryNerRecognizer.class);
        Mockito.when(dictionary.recognize("手机壳"))
                .thenReturn(Collections.singletonList(
                        new NerEntityDto("手机壳", "CATEGORY", 0, 3, "dictionary")));
        Mockito.when(dictionary.recognize("瑜伽垫"))
                .thenReturn(Collections.singletonList(
                        new NerEntityDto("瑜伽垫", "CATEGORY", 0, 3, "dictionary")));

        NerModelClient model = new NerModelClient() {
            @Override
            public NerModelOutput predict(String query) {
                List<NerEntityDto> entities;
                if ("手机壳".equals(query)) {
                    entities = Arrays.asList(
                            new NerEntityDto("手机", "TARGET", 0, 2, "onnx"),
                            new NerEntityDto("壳", "CATEGORY", 2, 3, "onnx"));
                } else {
                    entities = Arrays.asList(
                            new NerEntityDto("瑜伽", "SCENE", 0, 2, "onnx"),
                            new NerEntityDto("垫", "CATEGORY", 2, 3, "onnx"));
                }
                return new NerModelOutput("test-v1", 0, entities);
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public String provider() {
                return "onnx";
            }

            @Override
            public String modelVersion() {
                return "test-v1";
            }
        
            @Override
            public String unavailableReason() {
                return null;
            }
        };

        EntityNormalizer normalizer = Mockito.mock(EntityNormalizer.class);
        Mockito.when(normalizer.normalize(Mockito.anyString(), Mockito.anyList()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        HybridNerRecognizer recognizer = new HybridNerRecognizer(
                dictionary, model, properties, normalizer);

        assertThat(recognizer.recognize("手机壳"))
                .extracting(NerEntityDto::getText, NerEntityDto::getLabel)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("手机壳", "CATEGORY"));
        assertThat(recognizer.recognize("瑜伽垫"))
                .extracting(NerEntityDto::getText, NerEntityDto::getLabel)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("瑜伽垫", "CATEGORY"));
    }
}
