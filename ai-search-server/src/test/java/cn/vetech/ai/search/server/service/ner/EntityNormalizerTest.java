package cn.vetech.ai.search.server.service.ner;

import cn.vetech.ai.search.server.service.dto.NerEntityDto;
import cn.vetech.ai.search.server.config.NerProperties;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class EntityNormalizerTest {

    @Test
    void normalizesAliasesToCanonicalValueAndBusinessId() {
        EntityNormalizer normalizer = normalizer();
        NerEntityDto entity = new NerEntityDto("苹果15", "SERIES", 0, 4, "dictionary");

        normalizer.normalize("苹果15手机", Arrays.asList(entity));

        assertThat(entity.getNormalizedText()).isEqualTo("iPhone 15");
        assertThat(entity.getNormalizedId()).isEqualTo("iphone_15");
        assertThat(entity.getNormalizedType()).isEqualTo("product_series_id");
        assertThat(entity.searchText()).isEqualTo("iPhone 15");
    }

    @Test
    void normalizesHorsepowerWithDeterministicRule() {
        EntityNormalizer normalizer = normalizer();
        NerEntityDto entity = new NerEntityDto("3.5匹", "SPEC", 0, 4, "dictionary");

        normalizer.normalize("3.5匹空调", Arrays.asList(entity));

        assertThat(entity.getNormalizedText()).isEqualTo("3.5 HP");
        assertThat(entity.getNormalizedType()).isEqualTo("standardized_power");
        assertThat(entity.getNormalizationSource()).isEqualTo("rule:power");
    }

    @Test
    void normalizesBusinessAliasSplitIntoBrandAndSeries() {
        EntityNormalizer normalizer = normalizer();
        NerEntityDto brand = new NerEntityDto("苹果", "BRAND", 0, 2, "raner-onnx");
        NerEntityDto series = new NerEntityDto("15", "SERIES", 2, 4, "raner-onnx");

        normalizer.normalize("苹果15手机壳", Arrays.asList(brand, series));

        assertThat(brand.getNormalizedText()).isNull();
        assertThat(series.getNormalizedText()).isEqualTo("iPhone 15");
        assertThat(series.getNormalizedId()).isEqualTo("iphone_15");
    }

    @Test
    void normalizesModelAliasWhenRanerMapsItsSuffixToSeries() {
        EntityNormalizer normalizer = normalizer();
        NerEntityDto brand = new NerEntityDto("大疆", "BRAND", 0, 2, "raner-onnx");
        NerEntityDto series = new NerEntityDto("御3", "SERIES", 2, 4, "raner-onnx");

        normalizer.normalize("大疆御3无人机", Arrays.asList(brand, series));

        assertThat(series.getNormalizedText()).isEqualTo("DJI Mavic 3");
        assertThat(series.getNormalizedId()).isEqualTo("dji_mavic_3");
        assertThat(series.getNormalizedType()).isEqualTo("model_id");
    }

    private EntityNormalizer normalizer() {
        EntityNormalizer normalizer = new EntityNormalizer(new NerProperties());
        normalizer.initialize();
        return normalizer;
    }
}
