package cn.vetech.ai.search.fccapi.api.search;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 商品搜索响应。
 */
@Data
public class SearchResponse implements Serializable {

    private long total;
    private long rawTotal;
    private int page;
    private int pageSize;
    private List<Item> items;
    private Facets facets;
    private long tookMs;
    /**
     * 缓存状态，取值为 HIT、MISS 或 UNAVAILABLE。
     */
    private String cacheStatus;
    private boolean degraded;

    /**
     * 元素取值为 REDIS_UNAVAILABLE、NER_UNAVAILABLE、MODEL_UNAVAILABLE、
     * STALE_CACHE 或 DEDUP_SKIPPED。
     */
    private List<String> degradeReasons;

    /**
     * 缓存状态字符串常量。
     */
    public static final class CacheStatus {

        public static final String HIT = "HIT";
        public static final String MISS = "MISS";
        public static final String UNAVAILABLE = "UNAVAILABLE";

        private CacheStatus() {
        }
    }

    /**
     * 搜索结果中的单个商品。
     */
    @Data
    public static class Item implements Serializable {

        private String skuId;
        private String spuId;
        private String title;
        private String highlightTitle;
        private String brandName;
        private String categoryName;
        private String imageUrl;
        private Long priceFen;
        private Boolean inStock;
        private Float score;
    }

    /**
     * 商品搜索聚合维度。
     */
    @Data
    public static class Facets implements Serializable {

        private List<Bucket> brands;
        private List<Bucket> categories;

        /**
         * 单个聚合桶。
         */
        @Data
        public static class Bucket implements Serializable {

            private String key;
            private long count;
        }
    }
}
