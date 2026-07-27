package cn.vetech.ai.search.server.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 品类排除词生成规则测试。
 */
class SearchExclusionConfigTest {

    @Test
    void completeAccessoryCategoriesDoNotAppendGenericSuffixes() {
        SearchExclusionConfig config = new SearchExclusionConfig(new SearchProperties());
        config.initialize();

        assertThat(config.buildExclusionTerms("手机"))
                .contains("手机壳", "手机膜", "手机充电器");
        assertThat(config.buildExclusionTerms("手机壳")).isEmpty();
        assertThat(config.buildExclusionTerms("瑜伽垫"))
                .containsExactly("瑜伽垫包", "瑜伽垫带");
        assertThat(config.buildExclusionTerms("雨伞")).isEmpty();
    }
}
