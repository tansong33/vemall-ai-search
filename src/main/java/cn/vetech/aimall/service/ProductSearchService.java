package cn.vetech.aimall.service;

import cn.vetech.aimall.config.AiMallProperties;
import cn.vetech.aimall.model.dto.IntentResult;
import cn.vetech.aimall.model.entity.Product;
import cn.vetech.aimall.model.search.SearchCriteria;
import cn.vetech.aimall.repository.ProductCatalogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Rule -> 主键直查 / MySQL FULLTEXT -> 有条件的结构化放宽。 */
@Service
@RequiredArgsConstructor
public class ProductSearchService {

    private final ProductCatalogRepository productRepository;
    private final AiMallProperties properties;

    public DbSearchResult search(IntentResult intent) {
        if (intent.getProductId() != null) {
            SearchCriteria criteria = toCriteria(intent);
            Product product = productRepository.findExact(intent.getProductId(), criteria);
            if (product == null || product.getStock() == null || product.getStock().signum() <= 0) {
                return new DbSearchResult(Collections.<Product>emptyList(), "EXACT_ID");
            }
            product.setSearchScore(1.0);
            return new DbSearchResult(Collections.singletonList(product), "EXACT_ID");
        }

        SearchCriteria criteria = toCriteria(intent);
        boolean hasText = StringUtils.hasText(criteria.getSearchText());
        boolean hasRetrievalAnchor = hasRetrievalAnchor(criteria);
        if (!hasText && !hasRetrievalAnchor) {
            return new DbSearchResult(Collections.<Product>emptyList(), "NEED_MORE_INFO");
        }

        List<Product> products = new ArrayList<>();
        String route;
        if (properties.getSearch().isFulltextEnabled() && hasText) {
            products = productRepository.search(criteria);
            route = hasRetrievalAnchor ? "FULLTEXT_STRUCTURED" : "FULLTEXT";
        } else if (hasRetrievalAnchor) {
            products = productRepository.searchStructured(criteria);
            route = "STRUCTURED";
        } else {
            return new DbSearchResult(Collections.<Product>emptyList(), "FULLTEXT_DISABLED");
        }

        // 全文召回偶尔会因为短词/新词无结果；只有存在索引化结构条件时才安全放宽全文条件。
        if (products.isEmpty() && hasRetrievalAnchor) {
            products = productRepository.searchStructured(criteria);
            route = "STRUCTURED_RELAX";
        }
        return new DbSearchResult(products, route);
    }

    private SearchCriteria toCriteria(IntentResult intent) {
        SearchCriteria criteria = new SearchCriteria();
        criteria.setProductCode(intent.getProductId());
        criteria.setCategory(intent.getCategory());
        criteria.setCategoryId(intent.getCategoryId());
        criteria.setBrand(intent.getBrand());
        criteria.setBrandId(intent.getBrandId());
        criteria.setTenantCode(intent.getTenantCode());
        criteria.setChannelCode(intent.getChannelCode());
        criteria.setPriceMin(intent.getBudgetMin());
        criteria.setPriceMax(intent.getBudgetMax());
        criteria.setRequestedQuantity(decimalAttribute(intent, "采购数量"));
        criteria.setSearchText(intent.getSearchText());
        criteria.setSpuCandidateLimit(Math.max(1,
                Math.min(properties.getSearch().getSpuCandidateLimit(), 5000)));
        criteria.setLimit(Math.max(1, Math.min(properties.getSearch().getCandidateLimit(), 1000)));
        AiMallProperties.Search.CatalogStatus status = properties.getSearch().getStatus();
        criteria.setNotDeletedValue(status.getNotDeletedValue());
        criteria.setSpuOnState(status.getSpuOnState());
        criteria.setSpuAuditState(status.getSpuAuditState());
        criteria.setSkuOnState(status.getSkuOnState());
        criteria.setCategoryDisplayedValue(status.getCategoryDisplayedValue());
        criteria.setBrandEnabledValue(status.getBrandEnabledValue());
        return criteria;
    }

    private boolean hasRetrievalAnchor(SearchCriteria c) {
        // 只有价格没有类目/品牌会命中海量行，不允许作为结构化放宽条件。
        return StringUtils.hasText(c.getCategory()) || StringUtils.hasText(c.getBrand());
    }

    private BigDecimal decimalAttribute(IntentResult intent, String key) {
        try {
            String value = intent.getAttributes().get(key);
            return value == null ? null : new BigDecimal(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
