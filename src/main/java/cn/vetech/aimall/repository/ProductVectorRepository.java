package cn.vetech.aimall.repository;

import cn.vetech.aimall.model.entity.ProductVector;
import org.springframework.data.jpa.repository.JpaRepository;

/** 向量持久化仓库。findAll/saveAll/deleteAllById 已够用 */
public interface ProductVectorRepository extends JpaRepository<ProductVector, Long> {
}
