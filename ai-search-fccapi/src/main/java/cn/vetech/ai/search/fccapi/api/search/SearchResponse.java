package cn.vetech.ai.search.fccapi.api.search;

import java.io.Serializable;
import java.util.List;

/**
 * 商品搜索响应。
 */
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

        public String getSkuId() {
            return skuId;
        }

        public void setSkuId(String skuId) {
            this.skuId = skuId;
        }

        public String getSpuId() {
            return spuId;
        }

        public void setSpuId(String spuId) {
            this.spuId = spuId;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getHighlightTitle() {
            return highlightTitle;
        }

        public void setHighlightTitle(String highlightTitle) {
            this.highlightTitle = highlightTitle;
        }

        public String getBrandName() {
            return brandName;
        }

        public void setBrandName(String brandName) {
            this.brandName = brandName;
        }

        public String getCategoryName() {
            return categoryName;
        }

        public void setCategoryName(String categoryName) {
            this.categoryName = categoryName;
        }

        public String getImageUrl() {
            return imageUrl;
        }

        public void setImageUrl(String imageUrl) {
            this.imageUrl = imageUrl;
        }

        public Long getPriceFen() {
            return priceFen;
        }

        public void setPriceFen(Long priceFen) {
            this.priceFen = priceFen;
        }

        public Boolean getInStock() {
            return inStock;
        }

        public void setInStock(Boolean inStock) {
            this.inStock = inStock;
        }

        public Float getScore() {
            return score;
        }

        public void setScore(Float score) {
            this.score = score;
        }
    }

    /**
     * 商品搜索聚合维度。
     */
    public static class Facets implements Serializable {

        private List<Bucket> brands;
        private List<Bucket> categories;

        /**
         * 单个聚合桶。
         */
        public static class Bucket implements Serializable {

            private String key;
            private long count;

            public String getKey() {
                return key;
            }

            public void setKey(String key) {
                this.key = key;
            }

            public long getCount() {
                return count;
            }

            public void setCount(long count) {
                this.count = count;
            }
        }

        public List<Bucket> getBrands() {
            return brands;
        }

        public void setBrands(List<Bucket> brands) {
            this.brands = brands;
        }

        public List<Bucket> getCategories() {
            return categories;
        }

        public void setCategories(List<Bucket> categories) {
            this.categories = categories;
        }
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public long getRawTotal() {
        return rawTotal;
    }

    public void setRawTotal(long rawTotal) {
        this.rawTotal = rawTotal;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public List<Item> getItems() {
        return items;
    }

    public void setItems(List<Item> items) {
        this.items = items;
    }

    public Facets getFacets() {
        return facets;
    }

    public void setFacets(Facets facets) {
        this.facets = facets;
    }

    public long getTookMs() {
        return tookMs;
    }

    public void setTookMs(long tookMs) {
        this.tookMs = tookMs;
    }

    public String getCacheStatus() {
        return cacheStatus;
    }

    public void setCacheStatus(String cacheStatus) {
        this.cacheStatus = cacheStatus;
    }

    public boolean isDegraded() {
        return degraded;
    }

    public void setDegraded(boolean degraded) {
        this.degraded = degraded;
    }

    public List<String> getDegradeReasons() {
        return degradeReasons;
    }

    public void setDegradeReasons(List<String> degradeReasons) {
        this.degradeReasons = degradeReasons;
    }
}
