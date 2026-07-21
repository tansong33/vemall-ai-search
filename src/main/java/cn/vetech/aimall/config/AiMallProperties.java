package cn.vetech.aimall.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/** aimall.* 配置总入口。所有召回上限、规则权重和缓存策略都可热部署前调参。 */
@Data
@ConfigurationProperties(prefix = "aimall")
public class AiMallProperties {

    private Search search = new Search();
    private Rerank rerank = new Rerank();
    private Cache cache = new Cache();
    private Ner ner = new Ner();
    private Versions versions = new Versions();

    @Data
    public static class Search {
        /** 数据库最多返回的候选数。规则引擎只在这批小集合上运行。 */
        private int candidateLimit = 200;
        private boolean fulltextEnabled = true;
        /** mysql / elasticsearch / auto。ES 未就绪时 auto 和 elasticsearch 都会降级到 MySQL。 */
        private String backend = "mysql";
        /** stub / rest；当前先保留 stub，拿到商品字段后由 ES 负责人实现 rest。 */
        private String elasticsearchProvider = "stub";
    }

    @Data
    public static class Rerank {
        private double weightDatabase = 0.45;
        private double weightRule = 0.35;
        private double weightFeatured = 0.10;
        private double weightBudgetFit = 0.10;
        private int topN = 20;
    }

    @Data
    public static class Cache {
        private boolean enabled = true;
        private long ttlSeconds = 60;
        private long maximumSize = 10000;
        /** 多实例时再开启 Redis L2；单机 Demo 默认只用进程内 L1，避免 Redis 故障拖慢请求。 */
        private boolean redisEnabled = false;
        private long redisFailureBackoffMs = 30000;
    }

    @Data
    public static class Ner {
        /** 从数据库刷新类目/品牌词典的周期。 */
        private long dictionaryRefreshMs = 3600000;
        /** rule / shadow / hybrid / model。生产初始值必须为 rule。 */
        private String mode = "rule";
        /** stub / fixture / onnx。当前骨架提供 stub 与 fixture；训练团队交付后接 onnx。 */
        private String modelProvider = "stub";
        private String modelVersion = "none";
        private double shadowSampleRate = 0.10;
        private boolean fallbackOnError = true;
        private Map<String, Double> confidenceThresholds = defaultThresholds();

        private static Map<String, Double> defaultThresholds() {
            Map<String, Double> thresholds = new LinkedHashMap<>();
            thresholds.put("CATEGORY", 0.85);
            thresholds.put("BRAND", 0.92);
            thresholds.put("PRODUCT_TYPE", 0.75);
            thresholds.put("SCENE", 0.78);
            thresholds.put("ATTRIBUTE_VALUE", 0.82);
            return thresholds;
        }
    }

    @Data
    public static class Versions {
        private String schema = "intent-v1";
        private String rule = "rules-v1";
        private String index = "mysql-v1";
        private String cache = "search-v3";
    }
}
