package cn.vetech.aimall.mapper;

import cn.vetech.aimall.model.entity.Product;
import cn.vetech.aimall.model.search.SearchCriteria;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 只做有索引、有限条数的查询，禁止在线全表 LIKE 和无界 selectList。 */
public interface ProductMapper extends BaseMapper<Product> {

    @SelectProvider(type = ProductSearchSqlProvider.class, method = "search")
    List<Product> search(SearchCriteria criteria);

    @SelectProvider(type = ProductSearchSqlProvider.class, method = "searchStructured")
    List<Product> searchStructured(SearchCriteria criteria);

    @Select("select * from product where id > #{afterId} order by id asc limit #{limit}")
    List<Product> listAfterId(@Param("afterId") String afterId, @Param("limit") int limit);

    @Select("select distinct category from product where category is not null and category <> ''")
    List<String> selectDistinctCategories();

    @Select("select distinct brand from product where brand is not null and brand <> ''")
    List<String> selectDistinctBrands();
}
