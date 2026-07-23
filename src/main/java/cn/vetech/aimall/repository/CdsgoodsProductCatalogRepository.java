package cn.vetech.aimall.repository;

import cn.vetech.aimall.config.AiMallProperties;
import cn.vetech.aimall.mapper.CdsgoodsProductMapper;
import cn.vetech.aimall.model.entity.Product;
import cn.vetech.aimall.model.search.DictionaryTerm;
import cn.vetech.aimall.model.search.SearchCriteria;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 公司 cdsgoods 表结构适配器；物理字段变更只应影响 mapper/provider。 */
@Repository
@RequiredArgsConstructor
@ConditionalOnProperty(name = "aimall.search.data-source", havingValue = "cdsgoods")
public class CdsgoodsProductCatalogRepository implements ProductCatalogRepository {
    private final CdsgoodsProductMapper mapper;
    private final AiMallProperties properties;

    @Override
    public Product findExact(String productCode, SearchCriteria criteria) {
        criteria.setProductCode(productCode);
        return mapper.findExact(criteria);
    }

    @Override public List<Product> search(SearchCriteria criteria) { return mapper.search(criteria); }
    @Override public List<Product> searchStructured(SearchCriteria criteria) { return mapper.searchStructured(criteria); }

    @Override
    public List<Product> listAfterId(String afterId, int limit) {
        SearchCriteria criteria = baseCriteria();
        criteria.setAfterId(afterId);
        criteria.setLimit(limit);
        criteria.setSpuCandidateLimit(limit);
        return mapper.listAfterId(criteria);
    }

    @Override public List<DictionaryTerm> loadCategories() { return mapper.selectCategories(baseCriteria()); }
    @Override public List<DictionaryTerm> loadBrands() { return mapper.selectBrands(baseCriteria()); }

    private SearchCriteria baseCriteria() {
        SearchCriteria criteria = new SearchCriteria();
        AiMallProperties.Search.CatalogStatus status = properties.getSearch().getStatus();
        criteria.setNotDeletedValue(status.getNotDeletedValue());
        criteria.setSpuOnState(status.getSpuOnState());
        criteria.setSpuAuditState(status.getSpuAuditState());
        criteria.setSkuOnState(status.getSkuOnState());
        criteria.setCategoryDisplayedValue(status.getCategoryDisplayedValue());
        criteria.setBrandEnabledValue(status.getBrandEnabledValue());
        return criteria;
    }
}
