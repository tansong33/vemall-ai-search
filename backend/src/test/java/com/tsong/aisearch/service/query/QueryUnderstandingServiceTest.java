package com.tsong.aisearch.service.query;

import com.tsong.aisearch.model.dto.NerEntity;
import com.tsong.aisearch.model.dto.NerResult;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class QueryUnderstandingServiceTest {

    @Test
    void keepsExistingRewriteSynonymAndBoostBehavior() {
        NerResult ner = new NerResult();
        ner.setEntities(Arrays.asList(
                new NerEntity("公牛", "BRAND", 0, 2, "dictionary", null),
                new NerEntity("插座", "CATEGORY", 2, 4, "dictionary", null)
        ));

        com.tsong.aisearch.model.dto.ModelResult result =
                new QueryUnderstandingService().process("公牛插座 256g", ner);

        assertThat(result.getRewrittenQuery()).isEqualTo("公牛插座 256GB");
        assertThat(result.getSynonyms()).containsExactly("插座", "插排", "排插");
        assertThat(result.getFieldBoosts()).containsEntry("brand_name", 3.0f)
                .containsEntry("category_name", 2.0f);
    }
}
