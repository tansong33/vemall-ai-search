-- ai-mall 低延迟搜索索引迁移（MySQL 8）
-- 500 万行环境请使用在线 DDL 工具/影子表灰度执行，避免直接在流量高峰建索引。
USE ai_mall;

-- 中文使用 MySQL ngram parser。查询 SQL 的 MATCH 列必须与此索引完全一致。
ALTER TABLE product
  ADD FULLTEXT INDEX ft_product_search (title, brand, scene_tags, description) WITH PARSER ngram;

-- 结构化路由和 FULLTEXT 命中后的过滤辅助索引。
ALTER TABLE product
  ADD INDEX idx_product_category_price_stock (category, price, stock, id),
  ADD INDEX idx_product_brand_price_stock (brand, price, stock, id);

-- 执行后检查：应看到 ft_product_search、idx_product_category_price_stock、idx_product_brand_price_stock。
SHOW INDEX FROM product;

-- 用真实热词检查执行计划；目标是 fulltext / ref / range，不应出现无界 ALL 扫描。
EXPLAIN ANALYZE
SELECT p.id, MATCH(p.title, p.brand, p.scene_tags, p.description)
       AGAINST('夏季 降暑 员工福利' IN NATURAL LANGUAGE MODE) AS score
FROM product p
WHERE p.stock > 0
  AND p.category = '小家电'
  AND p.price <= 50
  AND MATCH(p.title, p.brand, p.scene_tags, p.description)
      AGAINST('夏季 降暑 员工福利' IN NATURAL LANGUAGE MODE) > 0
ORDER BY score DESC, p.featured DESC, p.stock DESC
LIMIT 200;
