package cn.vetech.ai.search.server.service.vo;

import java.util.ArrayList;
import java.util.List;

/**
 * 商品搜索的聚合维度。
 */
public class FacetsVo {

    private List<Bucket> brands = new ArrayList<Bucket>();
    private List<Bucket> categories = new ArrayList<Bucket>();

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

    /**
     * 单个聚合桶。
     */
    public static class Bucket {

        private String key;
        private long count;

        public Bucket() {
        }

        public Bucket(String key, long count) {
            this.key = key;
            this.count = count;
        }

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
}
