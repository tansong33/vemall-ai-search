package cn.vetech.ai.search.server.dao.impl;

import cn.vetech.ai.search.server.config.SearchProperties;
import cn.vetech.ai.search.server.dao.SearchCacheDao;
import cn.vetech.ai.search.server.service.dto.NerResultDto;
import cn.vetech.ai.search.server.service.vo.SearchResultVo;
import com.alibaba.fastjson2.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * 使用版本化 key 和 JSON 值访问 Redis，任何缓存故障都静默降级。
 */
@Repository
public class RedisSearchCacheDao implements SearchCacheDao {

    private static final Logger logger = LoggerFactory.getLogger(RedisSearchCacheDao.class);

    private static final String PREFIX_RESULT = "search:result:";
    private static final String PREFIX_NER = "search:ner:";
    private static final String PREFIX_HOT = "search:hot:";

    private static final long SEARCH_RESULT_TTL_SECONDS = 120L;
    private static final int SEARCH_RESULT_JITTER_SECONDS = 30;
    private static final long HOT_QUERY_TTL_SECONDS = 120L;
    private static final long NER_RESULT_TTL_SECONDS = 3600L;

    private static final String HASH_ALGORITHM = "SHA-256";
    private static final int HASH_PREFIX_LENGTH = 16;
    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    private final StringRedisTemplate redisTemplate;
    private final RedisConnectionFactory connectionFactory;
    private final SearchProperties.Search.Version version;
    private final Random random = new Random();

    public RedisSearchCacheDao(StringRedisTemplate redisTemplate,
                               RedisConnectionFactory connectionFactory,
                               SearchProperties searchProperties) {
        this.redisTemplate = redisTemplate;
        this.connectionFactory = connectionFactory;
        this.version = searchProperties.getSearch().getVersion();
    }

    @Override
    public boolean isAvailable() {
        RedisConnection connection = null;
        try {
            connection = connectionFactory.getConnection();
            return "PONG".equalsIgnoreCase(connection.ping());
        } catch (Exception e) {
            logger.warn("Redis 可用性检查失败", e);
            return false;
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception e) {
                    logger.warn("关闭 Redis 检查连接失败", e);
                }
            }
        }
    }

    @Override
    public SearchResultVo getSearchResult(String cacheKey) {
        try {
            return readJson(cacheKey, SearchResultVo.class, "搜索结果");
        } catch (Exception e) {
            logger.warn("搜索结果缓存读取失败，已降级跳过，key={}", cacheKey, e);
            return null;
        }
    }

    @Override
    public boolean putSearchResult(String cacheKey, SearchResultVo data) {
        try {
            long ttl = SEARCH_RESULT_TTL_SECONDS
                    + random.nextInt(SEARCH_RESULT_JITTER_SECONDS + 1);
            writeJson(cacheKey, data, ttl, "搜索结果");
            return true;
        } catch (Exception e) {
            logger.warn("搜索结果缓存写入失败，已降级跳过，key={}", cacheKey, e);
            return false;
        }
    }

    @Override
    public SearchResultVo getHotQuery(String query) {
        String key = null;
        try {
            key = PREFIX_HOT + buildQueryHash(query);
            return readJson(key, SearchResultVo.class, "热搜词");
        } catch (Exception e) {
            logger.warn("热搜词缓存读取失败，已降级跳过，key={}", key, e);
            return null;
        }
    }

    @Override
    public void putHotQuery(String query, SearchResultVo data) {
        String key = null;
        try {
            key = PREFIX_HOT + buildQueryHash(query);
            writeJson(key, data, HOT_QUERY_TTL_SECONDS, "热搜词");
        } catch (Exception e) {
            logger.warn("热搜词缓存写入失败，已降级跳过，key={}", key, e);
        }
    }

    @Override
    public NerResultDto getNerResult(String query) {
        String key = null;
        try {
            key = PREFIX_NER + version.getDict() + ":" + version.getNerModel()
                    + ":" + buildQueryHash(query);
            return readJson(key, NerResultDto.class, "NER 结果");
        } catch (Exception e) {
            logger.warn("NER 结果缓存读取失败，已降级跳过，key={}", key, e);
            return null;
        }
    }

    @Override
    public void putNerResult(String query, NerResultDto result) {
        String key = null;
        try {
            key = PREFIX_NER + version.getDict() + ":" + version.getNerModel()
                    + ":" + buildQueryHash(query);
            writeJson(key, result, NER_RESULT_TTL_SECONDS, "NER 结果");
        } catch (Exception e) {
            logger.warn("NER 结果缓存写入失败，已降级跳过，key={}", key, e);
        }
    }

    @Override
    public String buildSearchResultKey(String query,
                                       Map<String, String> dimensions,
                                       int page) {
        try {
            return PREFIX_RESULT + version.getIndex() + ":" + version.getDict() + ":"
                    + version.getNerModel() + ":" + version.getRule() + ":"
                    + buildQueryHash(query) + ":" + buildFilterHash(dimensions) + ":" + page;
        } catch (Exception e) {
            logger.warn("搜索结果缓存 key 构建失败，已降级跳过", e);
            return null;
        }
    }

    private <T> T readJson(String key, Class<T> targetType, String label) {
        String json = redisTemplate.opsForValue().get(key);
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        T result = JSON.parseObject(json, targetType);
        logger.debug("{}缓存命中，key={}", label, key);
        return result;
    }

    private void writeJson(String key, Object data, long ttlSeconds, String label) {
        if (data == null) {
            return;
        }
        String json = JSON.toJSONString(data);
        redisTemplate.opsForValue().set(key, json, ttlSeconds, TimeUnit.SECONDS);
        logger.debug("{}缓存已写入，key={}，TTL={}s", label, key, ttlSeconds);
    }

    private String buildQueryHash(String query) {
        if (query == null || query.isEmpty()) {
            return "empty_query_hash";
        }
        return sha256Hex(query);
    }

    private String buildFilterHash(Map<String, String> dimensions) {
        if (dimensions == null || dimensions.isEmpty()) {
            return sha256Hex("empty_filters");
        }
        TreeMap<String, String> sorted = new TreeMap<String, String>(dimensions);
        StringBuilder source = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (source.length() > 0) {
                source.append('&');
            }
            source.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sha256Hex(source.toString());
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            char[] result = new char[HASH_PREFIX_LENGTH];
            for (int i = 0; i < HASH_PREFIX_LENGTH / 2; i++) {
                byte value = bytes[i];
                result[i * 2] = HEX_CHARS[(value >> 4) & 0xF];
                result[i * 2 + 1] = HEX_CHARS[value & 0xF];
            }
            return new String(result);
        } catch (NoSuchAlgorithmException e) {
            logger.error("SHA-256 算法不可用，使用降级哈希", e);
            return fallbackHash(input);
        }
    }

    private String fallbackHash(String input) {
        String hex = Integer.toHexString(input.hashCode());
        StringBuilder result = new StringBuilder(HASH_PREFIX_LENGTH);
        for (int i = hex.length(); i < HASH_PREFIX_LENGTH; i++) {
            result.append('0');
        }
        result.append(hex);
        return result.substring(result.length() - HASH_PREFIX_LENGTH);
    }
}
