package cn.vetech.aimall.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 商品向量持久化实体，对应 product_vector 表。
 * 主键 = 商品ID（手动赋值，非自增），故 IdType.INPUT。
 */
@Data
@TableName("product_vector")
public class ProductVector {

    @TableId(value = "product_id", type = IdType.INPUT)
    private Long productId;

    /** 向量：float32 小端字节序（VectorCodec 编解码），列类型 LONGBLOB */
    private byte[] vector;

    private Integer dim;

    /** 生成该向量的模型标签（model:dim），换模型/换维度即触发重嵌 */
    private String model;

    /** 商品语义文本(toEmbeddingText)的 SHA-256，内容变了才重嵌 */
    private String contentHash;
}
