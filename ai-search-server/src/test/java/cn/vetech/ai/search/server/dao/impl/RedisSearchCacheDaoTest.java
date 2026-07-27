package cn.vetech.ai.search.server.dao.impl;

import cn.vetech.ai.search.server.config.SearchProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisSearchCacheDaoTest {

    private StringRedisTemplate redisTemplate;
    private RedisSearchCacheDao dao;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);

        SearchProperties properties = new SearchProperties();
        SearchProperties.Search.Version version = properties.getSearch().getVersion();
        version.setIndex("index-v1");
        version.setDict("dict-v2");
        version.setNerModel("ner-v3");
        version.setRule("rule-v4");

        dao = new RedisSearchCacheDao(redisTemplate, connectionFactory, properties);
    }

    @Test
    void returnsNullWhenRedisIsUnavailable() {
        when(redisTemplate.opsForValue())
                .thenThrow(new RedisConnectionFailureException("Redis offline"));

        assertThat(dao.getSearchResult("search-key")).isNull();
    }

    @Test
    void buildsStableKeyAndSeparatesSortAndPageSize() {
        Map<String, String> dimensions = new LinkedHashMap<String, String>();
        dimensions.put("sort", "RELEVANCE");
        dimensions.put("pageSize", "20");
        dimensions.put("brands", "Huawei");

        Map<String, String> reordered = new LinkedHashMap<String, String>();
        reordered.put("brands", "Huawei");
        reordered.put("pageSize", "20");
        reordered.put("sort", "RELEVANCE");

        String base = dao.buildSearchResultKey("华为手机", dimensions, 1);

        assertThat(dao.buildSearchResultKey("华为手机", dimensions, 1)).isEqualTo(base);
        assertThat(dao.buildSearchResultKey("华为手机", reordered, 1)).isEqualTo(base);
        assertThat(base)
                .startsWith("search:result:index-v1:dict-v2:ner-v3:rule-v4:")
                .endsWith(":1");

        Map<String, String> differentSort =
                new LinkedHashMap<String, String>(dimensions);
        differentSort.put("sort", "PRICE_ASC");
        assertThat(dao.buildSearchResultKey("华为手机", differentSort, 1))
                .isNotEqualTo(base);

        Map<String, String> differentPageSize =
                new LinkedHashMap<String, String>(dimensions);
        differentPageSize.put("pageSize", "50");
        assertThat(dao.buildSearchResultKey("华为手机", differentPageSize, 1))
                .isNotEqualTo(base);
    }
}
