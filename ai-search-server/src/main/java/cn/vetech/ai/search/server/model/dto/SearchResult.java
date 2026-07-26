package cn.vetech.ai.search.server.model.dto;

import java.util.ArrayList;
import java.util.List;

public class SearchResult {

    private long total;
    private List<ProductItem> products = new ArrayList<>();
    private AggregationResult aggregations = new AggregationResult();
    private long costMs;

    public long getTotal() { return total; }
    public void setTotal(long total) { this.total = total; }
    public List<ProductItem> getProducts() { return products; }
    public void setProducts(List<ProductItem> products) { this.products = products; }
    public AggregationResult getAggregations() { return aggregations; }
    public void setAggregations(AggregationResult aggregations) { this.aggregations = aggregations; }
    public long getCostMs() { return costMs; }
    public void setCostMs(long costMs) { this.costMs = costMs; }

    public static class ProductItem {
        private String id;
        private String title;
        private String highlightTitle;
        private String brand;
        private String category;
        private double price;
        private String image;
        private float score;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getHighlightTitle() { return highlightTitle; }
        public void setHighlightTitle(String highlightTitle) { this.highlightTitle = highlightTitle; }
        public String getBrand() { return brand; }
        public void setBrand(String brand) { this.brand = brand; }
        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
        public double getPrice() { return price; }
        public void setPrice(double price) { this.price = price; }
        public String getImage() { return image; }
        public void setImage(String image) { this.image = image; }
        public float getScore() { return score; }
        public void setScore(float score) { this.score = score; }
    }

    public static class AggregationResult {
        private List<AggBucket> brands = new ArrayList<>();
        private List<AggBucket> categories = new ArrayList<>();

        public List<AggBucket> getBrands() { return brands; }
        public void setBrands(List<AggBucket> brands) { this.brands = brands; }
        public List<AggBucket> getCategories() { return categories; }
        public void setCategories(List<AggBucket> categories) { this.categories = categories; }
    }

    public static class AggBucket {
        private String key;
        private long docCount;

        public AggBucket() {}
        public AggBucket(String key, long docCount) {
            this.key = key;
            this.docCount = docCount;
        }

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public long getDocCount() { return docCount; }
        public void setDocCount(long docCount) { this.docCount = docCount; }
    }
}
