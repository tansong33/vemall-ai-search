package cn.vetech.aimall.repository;

import cn.vetech.aimall.mapper.ProductMapper;
import cn.vetech.aimall.model.entity.Product;
import cn.vetech.aimall.model.search.DictionaryTerm;
import cn.vetech.aimall.model.search.SearchCriteria;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/** 仓库自带 product 样例表适配器。 */
@Repository
@RequiredArgsConstructor
@ConditionalOnProperty(name = "aimall.search.data-source", havingValue = "demo", matchIfMissing = true)
public class DemoProductCatalogRepository implements ProductCatalogRepository {
    private final ProductMapper mapper;

    @Override
    public Product findExact(String productCode, SearchCriteria criteria) {
        return mapper.selectById(productCode);
    }

    @Override public List<Product> search(SearchCriteria criteria) { return mapper.search(criteria); }
    @Override public List<Product> searchStructured(SearchCriteria criteria) { return mapper.searchStructured(criteria); }
    @Override public List<Product> listAfterId(String afterId, int limit) { return mapper.listAfterId(afterId, limit); }

    @Override
    public List<DictionaryTerm> loadCategories() {
        return terms(mapper.selectDistinctCategories());
    }

    @Override
    public List<DictionaryTerm> loadBrands() {
        return terms(mapper.selectDistinctBrands());
    }

    private List<DictionaryTerm> terms(List<String> values) {
        List<DictionaryTerm> result = new ArrayList<>();
        if (values == null) return result;
        for (String value : values) result.add(new DictionaryTerm(value, value, null));
        return result;
    }
}
