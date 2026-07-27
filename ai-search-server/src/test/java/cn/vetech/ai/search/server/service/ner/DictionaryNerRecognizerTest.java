package cn.vetech.ai.search.server.service.ner;

import cn.vetech.ai.search.server.service.dto.NerEntityDto;
import cn.vetech.ai.search.server.config.NerProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DictionaryNerRecognizerTest {

    @TempDir
    Path tempDirectory;

    @Test
    void explicitPriorityLetsFullSeriesOverrideNestedBrand() throws Exception {
        Path dictionary = tempDirectory.resolve("dict.tsv");
        Files.write(dictionary, Arrays.asList(
                "苹果\tBRAND",
                "苹果15\tSERIES\t100"
        ), StandardCharsets.UTF_8);
        NerProperties properties = new NerProperties();
        properties.setDictionaryPath(dictionary.toString());
        properties.setOverlayDictionaryPath(null);
        DictionaryNerRecognizer recognizer = new DictionaryNerRecognizer(properties);
        recognizer.initialize();

        List<NerEntityDto> entities = recognizer.recognize("苹果15手机");

        assertThat(entities).extracting(NerEntityDto::getText)
                .containsExactly("苹果15");
        assertThat(entities).extracting(NerEntityDto::getLabel)
                .containsExactly("SERIES");
    }

    @Test
    void asciiTermsIgnoreCaseButDoNotMatchInsideAnotherAsciiWord() throws Exception {
        Path dictionary = tempDirectory.resolve("ascii.tsv");
        Files.write(dictionary, Arrays.asList("Pro\tMODEL"), StandardCharsets.UTF_8);
        NerProperties properties = new NerProperties();
        properties.setDictionaryPath(dictionary.toString());
        properties.setOverlayDictionaryPath(null);
        DictionaryNerRecognizer recognizer = new DictionaryNerRecognizer(properties);
        recognizer.initialize();

        assertThat(recognizer.recognize("pro手机")).hasSize(1);
        assertThat(recognizer.recognize("iPro手机")).isEmpty();
    }
}
