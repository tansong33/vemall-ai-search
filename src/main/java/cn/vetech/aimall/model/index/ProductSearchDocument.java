package cn.vetech.aimall.model.index;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** ES `mall-spu-v1` 强类型文档契约，供后续全量/CDC 同步模块直接复用。 */
@Data
public class ProductSearchDocument {
    @JsonProperty("schema_version") private String schemaVersion = "mall-spu-v1";
    @JsonProperty("spu_id") private String spuId;
    private String title;
    private String subtitle;
    private String suggestion;
    @JsonProperty("query_keywords") private String queryKeywords;
    private Brand brand;
    private Category category;
    private List<Attribute> attrs = new ArrayList<>();
    @JsonProperty("attrs_flat") private Map<String, Object> attrsFlat = new LinkedHashMap<>();
    private List<Sku> skus = new ArrayList<>();
    @JsonProperty("min_price") private BigDecimal minPrice;
    @JsonProperty("max_price") private BigDecimal maxPrice;
    @JsonProperty("min_purchase_num") private BigDecimal minPurchaseNum;
    @JsonProperty("total_available_stock") private BigDecimal totalAvailableStock;
    @JsonProperty("has_stock") private boolean hasStock;
    private boolean searchable;
    @JsonProperty("search_weight") private BigDecimal searchWeight;
    @JsonProperty("sales_count") private Long salesCount;
    @JsonProperty("review_count") private Long reviewCount;
    private BigDecimal rating;
    @JsonProperty("good_rate") private BigDecimal goodRate;
    @JsonProperty("image_url") private String imageUrl;
    @JsonProperty("shop_id") private String shopId;
    @JsonProperty("supplier_id") private String supplierId;
    @JsonProperty("tenant_code") private String tenantCode;
    @JsonProperty("channel_code") private String channelCode;
    @JsonProperty("created_time") private OffsetDateTime createdTime;
    @JsonProperty("updated_time") private OffsetDateTime updatedTime;
    @JsonProperty("indexed_time") private OffsetDateTime indexedTime;

    @Data
    public static class Brand {
        private String id;
        private String name;
        private List<String> aliases = new ArrayList<>();
        @JsonProperty("parent_id") private String parentId;
    }

    @Data
    public static class Category {
        private String id;
        private String name;
        private Integer level;
        @JsonProperty("path_ids") private List<String> pathIds = new ArrayList<>();
        @JsonProperty("path_names") private List<String> pathNames = new ArrayList<>();
        private String keywords;
    }

    @Data
    public static class Attribute {
        private String name;
        @JsonProperty("name_text") private String nameText;
        private String value;
        @JsonProperty("value_text") private String valueText;
        @JsonProperty("numeric_value") private BigDecimal numericValue;
        private String unit;
    }

    @Data
    public static class Sku {
        @JsonProperty("sku_id") private String skuId;
        @JsonProperty("supplier_sku_id") private String supplierSkuId;
        @JsonProperty("bar_code") private String barCode;
        private String title;
        @JsonProperty("sales_price") private BigDecimal salesPrice;
        @JsonProperty("market_price") private BigDecimal marketPrice;
        @JsonProperty("available_stock") private BigDecimal availableStock;
        @JsonProperty("in_stock") private boolean inStock;
        @JsonProperty("sort_value") private Integer sortValue;
        @JsonProperty("spec_values") private String specValues;
        private Map<String, Object> specs = new LinkedHashMap<>();
        @JsonProperty("attrs_flat") private Map<String, Object> attrsFlat = new LinkedHashMap<>();
        @JsonProperty("image_url") private String imageUrl;
    }
}
