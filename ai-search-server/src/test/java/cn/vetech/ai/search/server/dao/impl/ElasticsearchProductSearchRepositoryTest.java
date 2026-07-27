package cn.vetech.ai.search.server.dao.impl;

import cn.vetech.ai.search.server.service.dto.NerEntityDto;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class ElasticsearchProductSearchRepositoryTest {

    @Test
    void removesRecognizedRangesBeforeSearchingRemainingTitleText() {
        String remaining = EsProductSearchDao.removeRecognizedParts(
                "学生华为手机256G",
                Arrays.asList(
                        entity("学生", "AUDIENCE", 0, 2),
                        entity("华为", "BRAND", 2, 4),
                        entity("手机", "CATEGORY", 4, 6)
                )
        );

        assertThat(remaining).isEqualTo("256G");
    }

    @Test
    void clipsOutOfBoundsRangesAndIgnoresNullEntities() {
        String remaining = EsProductSearchDao.removeRecognizedParts(
                "abc",
                Arrays.asList(
                        entity("a", "BRAND", -10, 1),
                        null,
                        entity("c", "CATEGORY", 2, 100)
                )
        );

        assertThat(remaining).isEqualTo("b");
    }

    @Test
    void handlesNullAndEmptyInputs() {
        assertThat(EsProductSearchDao.removeRecognizedParts(
                null, Collections.<NerEntityDto>emptyList())).isEmpty();
        assertThat(EsProductSearchDao.removeRecognizedParts(
                "  untouched  ", null)).isEqualTo("untouched");
        assertThat(EsProductSearchDao.removeRecognizedParts(
                "  untouched  ", Collections.<NerEntityDto>emptyList()))
                .isEqualTo("untouched");
        assertThat(EsProductSearchDao.removeRecognizedParts(
                "abc", Collections.singletonList((NerEntityDto) null)))
                .isEqualTo("abc");
    }

    private static NerEntityDto entity(String text, String label, int start, int end) {
        NerEntityDto entity = new NerEntityDto();
        entity.setText(text);
        entity.setLabel(label);
        entity.setStart(start);
        entity.setEnd(end);
        return entity;
    }
}
