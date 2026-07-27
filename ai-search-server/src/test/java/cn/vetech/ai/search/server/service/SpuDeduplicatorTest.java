package cn.vetech.ai.search.server.service;

import cn.vetech.ai.search.server.service.vo.SearchItemVo;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpuDeduplicatorTest {

    private final SpuDeduplicator deduplicator = new SpuDeduplicator();

    @Test
    void keepsHighestScoringSkuPerSpu() {
        List<SearchItemVo> result = deduplicator.deduplicate(Arrays.asList(
                item("sku1", "spuA", 1.0f),
                item("sku2", "spuB", 5.0f),
                item("sku3", "spuA", 9.0f)));

        assertThat(result).extracting(SearchItemVo::getSkuId)
                .containsExactly("sku3", "sku2");
    }

    @Test
    void keepsItemsWithoutSpuId() {
        List<SearchItemVo> result = deduplicator.deduplicate(Arrays.asList(
                item("sku1", null, 1.0f),
                item("sku2", "", 2.0f),
                item("sku3", "spuA", 3.0f)));

        assertThat(result).hasSize(3);
    }

    @Test
    void returnsEmptyListForNullInput() {
        assertThat(deduplicator.deduplicate(null)).isEmpty();
    }

    private static SearchItemVo item(String skuId, String spuId, float score) {
        SearchItemVo item = new SearchItemVo();
        item.setSkuId(skuId);
        item.setSpuId(spuId);
        item.setScore(score);
        return item;
    }
}
