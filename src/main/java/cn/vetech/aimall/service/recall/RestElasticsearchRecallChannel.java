package cn.vetech.aimall.service.recall;

import cn.vetech.aimall.config.AiMallProperties;
import cn.vetech.aimall.model.dto.IntentResult;
import cn.vetech.aimall.model.entity.Product;
import cn.vetech.aimall.service.DbSearchResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Elasticsearch REST 召回；任何异常由 RecallOrchestrator 自动降级到 MySQL。 */
@Component
@ConditionalOnProperty(name = "aimall.search.elasticsearch-provider", havingValue = "rest")
public class RestElasticsearchRecallChannel implements RecallChannel {
    private final RestTemplate restTemplate;
    private final ElasticsearchQueryFactory queryFactory;
    private final ObjectMapper objectMapper;
    private final AiMallProperties properties;

    public RestElasticsearchRecallChannel(
            @Qualifier("mallElasticsearchRestTemplate") RestTemplate restTemplate,
            ElasticsearchQueryFactory queryFactory, ObjectMapper objectMapper, AiMallProperties properties) {
        this.restTemplate = restTemplate;
        this.queryFactory = queryFactory;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override public String name() { return "elasticsearch"; }

    @Override
    public boolean isReady() {
        AiMallProperties.Search.Elasticsearch es = properties.getSearch().getElasticsearch();
        return StringUtils.hasText(es.getEndpoint()) && StringUtils.hasText(es.getIndexAlias());
    }

    @Override
    public DbSearchResult recall(IntentResult intent) {
        AiMallProperties.Search.Elasticsearch es = properties.getSearch().getElasticsearch();
        String endpoint = es.getEndpoint().replaceAll("/+$", "");
        String url = endpoint + "/" + es.getIndexAlias() + "/_search";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(queryFactory.build(intent).toString(), headers);
        JsonNode response = restTemplate.postForObject(url, request, JsonNode.class);
        return new DbSearchResult(parseProducts(response), "ES");
    }

    private List<Product> parseProducts(JsonNode response) {
        List<Product> products = new ArrayList<>();
        JsonNode hits = response == null ? null : response.path("hits").path("hits");
        if (hits == null || !hits.isArray()) return products;
        for (JsonNode hit : hits) {
            JsonNode source = hit.path("_source");
            Product product = new Product();
            product.setId(text(source, "spu_id"));
            product.setTitle(text(source, "title"));
            product.setImageUrl(text(source, "image_url"));
            product.setCategoryId(text(source.path("category"), "id"));
            product.setCategory(text(source.path("category"), "name"));
            product.setBrandId(text(source.path("brand"), "id"));
            product.setBrand(text(source.path("brand"), "name"));
            product.setMinPrice(decimal(source, "min_price"));
            product.setMaxPrice(decimal(source, "max_price"));
            product.setMinPurchaseNum(decimal(source, "min_purchase_num"));
            product.setSearchWeight(decimal(source, "search_weight"));
            product.setFeatured(product.getSearchWeight() != null && product.getSearchWeight().signum() > 0);
            product.setRating(decimal(source, "rating"));
            product.setSalesCount(longValue(source, "sales_count"));
            product.setTenantCode(text(source, "tenant_code"));
            product.setChannelCode(text(source, "channel_code"));
            if (source.has("attrs_flat")) product.setAttrs(source.get("attrs_flat").toString());
            product.setSearchScore(hit.path("_score").isNumber() ? hit.path("_score").asDouble() : 0.0);
            applyMatchedSku(product, hit.path("inner_hits").path("matched_sku").path("hits").path("hits"));
            if (product.getId() != null && product.getStock() != null && product.getStock().signum() > 0) {
                products.add(product);
            }
        }
        return products;
    }

    private void applyMatchedSku(Product product, JsonNode skuHits) {
        if (!skuHits.isArray() || skuHits.size() == 0) return;
        JsonNode sku = skuHits.get(0).path("_source");
        // nested inner_hits 的 _source 在不同 ES 版本中可能是对象本身，也可能保留 skus 包装。
        if (sku.has("skus")) sku = sku.path("skus");
        product.setSkuId(text(sku, "sku_id"));
        product.setPrice(decimal(sku, "sales_price"));
        product.setStock(decimal(sku, "available_stock"));
        product.setBarCode(text(sku, "bar_code"));
        String skuImage = text(sku, "image_url");
        if (StringUtils.hasText(skuImage)) product.setImageUrl(skuImage);
        if (sku.has("attrs_flat")) product.setAttrs(sku.get("attrs_flat").toString());
        if (sku.has("specs")) product.setSpecs(sku.get("specs").toString());
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || !value.isNumber() ? null : value.decimalValue();
    }

    private Long longValue(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || !value.isNumber() ? null : value.asLong();
    }
}
