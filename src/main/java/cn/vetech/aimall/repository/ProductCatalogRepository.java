package cn.vetech.aimall.repository;

import cn.vetech.aimall.model.entity.Product;
import cn.vetech.aimall.model.search.DictionaryTerm;
import cn.vetech.aimall.model.search.SearchCriteria;

import java.util.List;

/** 物理商品表与在线搜索链路之间的防腐层。 */
public interface ProductCatalogRepository {
    Product findExact(String productCode, SearchCriteria criteria);
    List<Product> search(SearchCriteria criteria);
    List<Product> searchStructured(SearchCriteria criteria);
    List<Product> listAfterId(String afterId, int limit);
    List<DictionaryTerm> loadCategories();
    List<DictionaryTerm> loadBrands();
}
