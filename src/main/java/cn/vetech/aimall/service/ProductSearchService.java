package cn.vetech.aimall.service;

import cn.vetech.aimall.config.AiMallProperties;
import cn.vetech.aimall.mapper.ProductMapper;
import cn.vetech.aimall.model.dto.IntentResult;
import cn.vetech.aimall.model.entity.Product;
import cn.vetech.aimall.model.search.SearchCriteria;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Rule -> 主键直查 / MySQL FULLTEXT -> 有条件的结构化放宽。 */
@Service
@RequiredArgsConstructor
public class ProductSearchService {

    private final ProductMapper productMapper;
    private final AiMallProperties properties;

    public DbSearchResult search(IntentResult intent) {
        if (intent.getProductId() != null) {
            Product product = productMapper.selectById(intent.getProductId());
            if (product == null || product.getStock() == null || product.getStock() <= 0) {
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
            products = productMapper.search(criteria);
            route = hasRetrievalAnchor ? "FULLTEXT_STRUCTURED" : "FULLTEXT";
        } else if (hasRetrievalAnchor) {
            products = productMapper.searchStructured(criteria);
            route = "STRUCTURED";
        } else {
            return new DbSearchResult(Collections.<Product>emptyList(), "FULLTEXT_DISABLED");
        }

        // 全文召回偶尔会因为短词/新词无结果；只有存在索引化结构条件时才安全放宽全文条件。
        if (products.isEmpty() && hasRetrievalAnchor) {
            products = productMapper.searchStructured(criteria);
            route = "STRUCTURED_RELAX";
        }
        return new DbSearchResult(products, route);
    }

    private SearchCriteria toCriteria(IntentResult intent) {
        SearchCriteria criteria = new SearchCriteria();
        criteria.setCategory(intent.getCategory());
        criteria.setBrand(intent.getBrand());
        criteria.setPriceMin(intent.getBudgetMin());
        criteria.setPriceMax(intent.getBudgetMax());
        criteria.setSearchText(intent.getSearchText());
        criteria.setLimit(Math.max(1, Math.min(properties.getSearch().getCandidateLimit(), 1000)));
        return criteria;
    }

    private boolean hasRetrievalAnchor(SearchCriteria c) {
        // 只有价格没有类目/品牌会命中海量行，不允许作为结构化放宽条件。
        return StringUtils.hasText(c.getCategory()) || StringUtils.hasText(c.getBrand());
    }
}
