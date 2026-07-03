package com.vetech.aimall.repository;

import com.vetech.aimall.model.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long> {

    /** 关键词召回：标题/类目/标签/描述 LIKE 匹配（数据量大时应替换为 ES/BM25，见 README 扩展点） */
    @Query("select p from Product p where p.stock > 0 and (" +
           " p.title like %:kw% or p.category like %:kw% or p.sceneTags like %:kw% or p.description like %:kw%)")
    List<Product> searchByKeyword(@Param("kw") String keyword);
}
