package cn.vetech.ai.search.server.controller;

import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/system")
public class SystemStatusController {

    private static final Logger log = LoggerFactory.getLogger(SystemStatusController.class);

    private final HealthProbe elasticsearchProbe;
    private final HealthProbe redisProbe;

    @Autowired
    public SystemStatusController(RestHighLevelClient elasticsearch,
                                  RedisConnectionFactory redisConnectionFactory) {
        this(
                () -> {
                    if (!elasticsearch.ping(RequestOptions.DEFAULT)) {
                        throw new IllegalStateException("ping returned false");
                    }
                    return "cluster reachable";
                },
                () -> pingRedis(redisConnectionFactory)
        );
    }

    SystemStatusController(HealthProbe elasticsearchProbe, HealthProbe redisProbe) {
        this.elasticsearchProbe = elasticsearchProbe;
        this.redisProbe = redisProbe;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> services = new LinkedHashMap<>();
        services.put("backend", service("UP", 0, uptime()));
        services.put("elasticsearch", elasticsearchStatus());
        services.put("redis", redisStatus());

        boolean healthy = services.values().stream()
                .map(value -> (Map<?, ?>) value)
                .allMatch(value -> "UP".equals(value.get("status")));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", healthy ? "UP" : "DEGRADED");
        response.put("checkedAt", Instant.now().toString());
        response.put("services", services);
        return response;
    }

    private Map<String, Object> elasticsearchStatus() {
        return probe("Elasticsearch", elasticsearchProbe);
    }

    private Map<String, Object> redisStatus() {
        return probe("Redis", redisProbe);
    }

    private Map<String, Object> probe(String name, HealthProbe probe) {
        long started = System.nanoTime();
        try {
            return service("UP", elapsedMs(started), probe.check());
        } catch (Exception exception) {
            log.warn("{} health probe failed: {}", name, exception.getClass().getSimpleName());
            return service("DOWN", elapsedMs(started), exception.getClass().getSimpleName());
        }
    }

    private static String pingRedis(RedisConnectionFactory connectionFactory) {
        RedisConnection connection = null;
        try {
            connection = connectionFactory.getConnection();
            String pong = connection.ping();
            if (!"PONG".equalsIgnoreCase(pong)) {
                throw new IllegalStateException("unexpected response");
            }
            return "PONG";
        } finally {
            if (connection != null) {
                connection.close();
            }
        }
    }

    private static Map<String, Object> service(String status, long latencyMs, String detail) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("status", status);
        value.put("latencyMs", latencyMs);
        value.put("detail", detail);
        return value;
    }

    private static String uptime() {
        long seconds = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        return hours + "h " + minutes + "m";
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    @FunctionalInterface
    interface HealthProbe {
        String check() throws Exception;
    }
}
