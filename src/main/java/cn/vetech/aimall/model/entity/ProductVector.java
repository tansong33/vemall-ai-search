package cn.vetech.aimall.model.entity;

import lombok.Data;

import javax.persistence.*;

/** 商品向量持久化实体，对应 product_vector 表 */
@Data
@Entity
@Table(name = "product_vector")
public class ProductVector {

    @Id
    @Column(name = "product_id")
    private Long productId;

    /** 向量：float32 小端字节序，见 VectorCodec */
    @Lob
    @Column(name = "vector", columnDefinition = "LONGBLOB")
    private byte[] vector;

    private Integer dim;

    /** 生成该向量的模型标签（provider:model:dim），换模型/换维度即触发重嵌 */
    private String model;

    /** 商品语义文本(toEmbeddingText)的哈希，内容变了才重嵌 */
    @Column(name = "content_hash")
    private String contentHash;
}
