package cn.vetech.aimall.service;

import cn.vetech.aimall.config.AiMallProperties;
import cn.vetech.aimall.model.dto.RecommendResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Caffeine L1 + 可选 Redis L2。Redis 故障会短路退避，不能拖慢每一个搜索请求。 */
@Slf4j
@Service
public class SearchCacheService {

    private final AiMallProperties properties;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final Cache<String, RecommendResponse> localCache;
    private final AtomicLong redisRetryAfter = new AtomicLong(0);

    public SearchCacheService(AiMallProperties properties, ObjectMapper objectMapper,
                              ObjectProvider<StringRedisTemplate> redisProvider) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.redisTemplate = redisProvider.getIfAvailable();
        this.localCache = Caffeine.newBuilder()
                .maximumSize(Math.max(1, properties.getCache().getMaximumSize()))
                .expireAfterWrite(Math.max(1, properties.getCache().getTtlSeconds()), TimeUnit.SECONDS)
                .build();
    }

    public Optional<RecommendResponse> get(String normalizedQuery) {
        if (!properties.getCache().isEnabled()) return Optional.empty();
        String key = cacheKey(normalizedQuery);
        RecommendResponse local = localCache.getIfPresent(key);
        if (local != null) {
            RecommendResponse copy = copy(local);
            if (copy.getTrace() != null) copy.getTrace().setCacheSource("L1");
            return Optional.of(copy);
        }
        if (!redisAvailable()) return Optional.empty();
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) return Optional.empty();
            RecommendResponse response = objectMapper.readValue(json, RecommendResponse.class);
            localCache.put(key, response);
            RecommendResponse copy = copy(response);
            if (copy.getTrace() != null) copy.getTrace().setCacheSource("L2");
            return Optional.of(copy);
        } catch (Exception e) {
            tripRedisCircuit(e);
            return Optional.empty();
        }
    }

    public void put(String normalizedQuery, RecommendResponse response) {
        if (!properties.getCache().isEnabled()) return;
        String key = cacheKey(normalizedQuery);
        localCache.put(key, copy(response));
        if (!redisAvailable()) return;
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(response),
                    Duration.ofSeconds(Math.max(1, properties.getCache().getTtlSeconds())));
        } catch (Exception e) {
            tripRedisCircuit(e);
        }
    }

    private boolean redisAvailable() {
        return properties.getCache().isRedisEnabled() && redisTemplate != null
                && System.currentTimeMillis() >= redisRetryAfter.get();
    }

    private void tripRedisCircuit(Exception e) {
        redisRetryAfter.set(System.currentTimeMillis()
                + Math.max(1000, properties.getCache().getRedisFailureBackoffMs()));
        log.warn("Redis L2 暂时不可用，缓存已退化到 L1: {}", e.getMessage());
    }

    private RecommendResponse copy(RecommendResponse response) {
        return objectMapper.convertValue(response, RecommendResponse.class);
    }

    private String cacheKey(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder("aimall:search:")
                    .append(properties.getVersions().getCache()).append(':')
                    .append(properties.getVersions().getRule()).append(':')
                    .append(properties.getVersions().getIndex()).append(':')
                    .append(properties.getNer().getMode()).append(':')
                    .append(properties.getNer().getModelVersion()).append(':');
            for (byte b : bytes) key.append(String.format("%02x", b));
            return key.toString();
        } catch (Exception e) {
            return "aimall:search:" + properties.getVersions().getCache() + ":" + value.hashCode();
        }
    }
}
