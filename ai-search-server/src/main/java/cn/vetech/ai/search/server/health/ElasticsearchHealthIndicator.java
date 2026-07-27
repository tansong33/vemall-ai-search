package cn.vetech.ai.search.server.health;

import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * ES 健康检查。
 *
 * <p>Redis 的检查由 Spring Boot 自带的 RedisHealthContributor 提供，无需自己写；
 * 因此这里只补 ES 这一个，`/actuator/health` 即可同时反映两者。</p>
 */
@Component("elasticsearch")
public class ElasticsearchHealthIndicator implements HealthIndicator {

    private final RestHighLevelClient client;

    public ElasticsearchHealthIndicator(RestHighLevelClient client) {
        this.client = client;
    }

    @Override
    public Health health() {
        try {
            return client.ping(RequestOptions.DEFAULT)
                    ? Health.up().build()
                    : Health.down().withDetail("reason", "ping returned false").build();
        } catch (Exception e) {
            return Health.down().withDetail("reason", e.getClass().getSimpleName()).build();
        }
    }
}
