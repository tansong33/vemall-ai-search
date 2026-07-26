package cn.vetech.ai.search.server.service.ner;

import cn.vetech.ai.search.server.config.AiSearchProperties;
import cn.vetech.ai.search.server.model.dto.NerEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DictionaryNerRecognizerTest {

    @Test
    void recognizesCurrentProductionDictionary() {
        AiSearchProperties properties = new AiSearchProperties();
        properties.getNer().setDictionaryPath("classpath:ner_dict.txt");
        DictionaryNerRecognizer recognizer = new DictionaryNerRecognizer(properties);
        recognizer.initialize();

        List<NerEntity> entities = recognizer.recognize("公牛插座");

        assertThat(entities).extracting(NerEntity::getText)
                .containsExactly("公牛", "插座");
        assertThat(entities).extracting(NerEntity::getLabel)
                .containsExactly("BRAND", "CATEGORY");
    }
}
