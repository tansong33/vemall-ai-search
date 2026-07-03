-- 向量持久化表：存已算好的向量，避免每次启动重复调 embedding API
-- 因为项目 ddl-auto=none，这张表需手动执行一次（或加进 sql/init.sql 后重建库）
USE ai_mall;

CREATE TABLE IF NOT EXISTS product_vector (
    product_id   BIGINT PRIMARY KEY COMMENT '商品ID，与 product.id 对应',
    vector       LONGBLOB      NOT NULL COMMENT '向量(float32 小端字节序)',
    dim          INT           NOT NULL COMMENT '维度',
    model        VARCHAR(64)   NOT NULL COMMENT '生成该向量的模型标签，换模型即触发重嵌',
    content_hash VARCHAR(64)   NOT NULL COMMENT '商品语义文本的哈希，变了才重嵌',
    updated_at   TIMESTAMP     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品向量持久化表';
