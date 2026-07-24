package com.tsong.aisearch.repository.elasticsearch;

import com.tsong.aisearch.config.AiSearchProperties;
import com.tsong.aisearch.model.dto.ModelResult;
import com.tsong.aisearch.model.dto.NerEntity;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class ElasticsearchProductSearchRepositoryTest {

    private final ElasticsearchProductSearchRepository repository =
            new ElasticsearchProductSearchRepository(null, new AiSearchProperties());

    @Test
    void buildsFieldAwareQueryFromNerEntities() {
        ModelResult model = new ModelResult();
        model.setRewrittenQuery("公牛插座");

        SearchSourceBuilder source = repository.source(
                "公牛插座",
                model,
                Arrays.asList(
                        new NerEntity("公牛", "BRAND", 0, 2, "dictionary", null),
                        new NerEntity("插座", "CATEGORY", 2, 4, "dictionary", null)
                ),
                "default",
                Collections.emptyMap(),
                true
        );

        String json = source.toString();
        assertThat(json)
                .contains("\"brand_name\"")
                .contains("\"category_name\"")
                .contains("\"boost\":50.0")
                .contains("\"boost\":40.0")
                .doesNotContain("\"multi_match\"");
    }

    @Test
    void buildsTitleOnlyFallbackWithoutNerFieldConstraints() {
        ModelResult model = new ModelResult();
        model.setRewrittenQuery("公牛插座");

        SearchSourceBuilder source = repository.source(
                "公牛插座",
                model,
                Collections.singletonList(
                        new NerEntity("公牛", "BRAND", 0, 2, "dictionary", null)
                ),
                "default",
                Collections.emptyMap(),
                false
        );

        assertThat(source.toString())
                .contains("\"title\"")
                .doesNotContain("\"brand_name\"");
    }

    @Test
    void removesRecognizedRangesBeforeSearchingRemainingTitleText() {
        String remaining = ElasticsearchProductSearchRepository.removeRecognizedParts(
                "学生华为手机256G",
                Arrays.asList(
                        new NerEntity("学生", "AUDIENCE", 0, 2, "model", 0.9),
                        new NerEntity("华为", "BRAND", 2, 4, "model", 0.9),
                        new NerEntity("手机", "CATEGORY", 4, 6, "model", 0.9)
                )
        );

        assertThat(remaining).isEqualTo("256G");
    }
}
