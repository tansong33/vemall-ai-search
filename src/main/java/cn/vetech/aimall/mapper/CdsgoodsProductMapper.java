package cn.vetech.aimall.mapper;

import cn.vetech.aimall.model.entity.Product;
import cn.vetech.aimall.model.search.DictionaryTerm;
import cn.vetech.aimall.model.search.SearchCriteria;
import org.apache.ibatis.annotations.SelectProvider;

import java.util.List;

/** 公司商城 11 张真实表的只读查询入口。 */
public interface CdsgoodsProductMapper {

    @SelectProvider(type = CdsgoodsProductSearchSqlProvider.class, method = "findExact")
    Product findExact(SearchCriteria criteria);

    @SelectProvider(type = CdsgoodsProductSearchSqlProvider.class, method = "search")
    List<Product> search(SearchCriteria criteria);

    @SelectProvider(type = CdsgoodsProductSearchSqlProvider.class, method = "searchStructured")
    List<Product> searchStructured(SearchCriteria criteria);

    @SelectProvider(type = CdsgoodsProductSearchSqlProvider.class, method = "listAfterId")
    List<Product> listAfterId(SearchCriteria criteria);

    @SelectProvider(type = CdsgoodsProductSearchSqlProvider.class, method = "selectCategories")
    List<DictionaryTerm> selectCategories(SearchCriteria criteria);

    @SelectProvider(type = CdsgoodsProductSearchSqlProvider.class, method = "selectBrands")
    List<DictionaryTerm> selectBrands(SearchCriteria criteria);
}
