package cn.vetech.ai.search.fccapi.api.search;

import lombok.Data;

import java.io.Serializable;
import java.util.List;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/**
 * 商品搜索请求。
 */
@Data
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
    @Data
    public static class Filters implements Serializable {

        private List<String> brands;
        private List<String> categories;
        private Long minPriceFen;
        private Long maxPriceFen;
        private Boolean inStock;
    }
}
