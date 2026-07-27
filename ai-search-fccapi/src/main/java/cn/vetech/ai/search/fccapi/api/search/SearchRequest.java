package cn.vetech.ai.search.fccapi.api.search;

import java.io.Serializable;
import java.util.List;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/**
 * 商品搜索请求。
 */
public class SearchRequest implements Serializable {

    @NotBlank(message = "query 不能为空")
    @Size(max = 100)
    private String query;

    @Min(1)
    private Integer page = 1;

    @Min(1)
    @Max(100)
    private Integer pageSize = 20;

    private Sort sort = Sort.RELEVANCE;
    private Filters filters;

    public enum Sort {
        RELEVANCE,
        PRICE_ASC,
        PRICE_DESC,
        SALES,
        RATING
    }

    /**
     * 商品搜索筛选条件。
     */
    public static class Filters implements Serializable {

        private List<String> brands;
        private List<String> categories;
        private Long minPriceFen;
        private Long maxPriceFen;
        private Boolean inStock;

        public List<String> getBrands() {
            return brands;
        }

        public void setBrands(List<String> brands) {
            this.brands = brands;
        }

        public List<String> getCategories() {
            return categories;
        }

        public void setCategories(List<String> categories) {
            this.categories = categories;
        }

        public Long getMinPriceFen() {
            return minPriceFen;
        }

        public void setMinPriceFen(Long minPriceFen) {
            this.minPriceFen = minPriceFen;
        }

        public Long getMaxPriceFen() {
            return maxPriceFen;
        }

        public void setMaxPriceFen(Long maxPriceFen) {
            this.maxPriceFen = maxPriceFen;
        }

        public Boolean getInStock() {
            return inStock;
        }

        public void setInStock(Boolean inStock) {
            this.inStock = inStock;
        }
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public Integer getPage() {
        return page;
    }

    public void setPage(Integer page) {
        this.page = page;
    }

    public Integer getPageSize() {
        return pageSize;
    }

    public void setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
    }

    public Sort getSort() {
        return sort;
    }

    public void setSort(Sort sort) {
        this.sort = sort;
    }

    public Filters getFilters() {
        return filters;
    }

    public void setFilters(Filters filters) {
        this.filters = filters;
    }
}
