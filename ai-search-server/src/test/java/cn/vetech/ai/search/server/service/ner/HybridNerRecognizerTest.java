package cn.vetech.ai.search.server.service.ner;

import cn.vetech.ai.search.server.config.AiSearchProperties;
import cn.vetech.ai.search.server.model.dto.NerEntity;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HybridNerRecognizerTest {

    @Test
    void modelWinsAndDictionaryFillsNonOverlappingEntities() {
        AiSearchProperties properties = new AiSearchProperties();
        properties.getNer().setMode("hybrid");
        properties.getNer().setDictionaryPath("classpath:ner_dict.txt");
        DictionaryNerRecognizer dictionary = new DictionaryNerRecognizer(properties);
        dictionary.initialize();
        NerModelClient model = new NerModelClient() {
            @Override
            public NerModelOutput predict(String query) {
                return new NerModelOutput("test-v1", 1, Collections.singletonList(
                        new NerEntity("公牛", "BRAND", 0, 2, "onnx", 0.99)));
            }
            @Override public boolean isReady() { return true; }
            @Override public String provider() { return "onnx"; }
            @Override public String modelVersion() { return "test-v1"; }
        };
        HybridNerRecognizer recognizer = new HybridNerRecognizer(dictionary, model, properties);

        List<NerEntity> entities = recognizer.recognize("公牛插座");

        assertThat(entities).extracting(NerEntity::getText)
                .containsExactly("公牛", "插座");
        assertThat(entities.get(0).getSource()).isEqualTo("onnx");
        assertThat(entities.get(1).getSource()).isEqualTo("dictionary");
    }
}
