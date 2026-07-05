package cn.vetech.aimall.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import cn.vetech.aimall.model.entity.Product;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 商品 Mapper。BaseMapper 自带 selectById/selectList/insert/updateById/deleteBatchIds 等。
 * 关键词召回用注解 SQL；数据量大后应替换为 Elasticsearch/BM25（实现 RecallChannel 即可，见 README 扩展点）。
 */
public interface ProductMapper extends BaseMapper<Product> {

    @Select("select * from product " +
            "where stock > 0 and ( title like concat('%', #{kw}, '%') " +
            "   or category like concat('%', #{kw}, '%') " +
            "   or scene_tags like concat('%', #{kw}, '%') " +
            "   or description like concat('%', #{kw}, '%') )")
    List<Product> searchByKeyword(@Param("kw") String keyword);
}
