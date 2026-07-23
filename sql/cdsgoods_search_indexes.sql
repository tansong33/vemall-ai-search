-- cdsgoods 搜索索引建议（MySQL 8）
-- 重要：先在只读副本执行 SHOW INDEX，删除已存在的 ADD 项；生产大表必须走公司在线 DDL/影子表流程。
USE cdsgoods;

SHOW INDEX FROM pro_spu;
SHOW INDEX FROM pro_sku;
SHOW INDEX FROM pro_sku_stock;

-- Java 的 MATCH 列与此索引必须完全一致。中文使用内置 ngram parser，避免在线前缀通配 LIKE。
ALTER TABLE pro_spu
  ADD FULLTEXT INDEX ft_spu_search
      (pro_name, pro_suggestion, query_keywords, brand_name) WITH PARSER ngram;

-- SPU 先按租户/渠道/状态/类目/品牌收窄，再回表取全文候选。
ALTER TABLE pro_spu
  ADD INDEX idx_spu_search_scope
      (tn_code, channel_code, is_deleted, on_state, audit_state, class_id, brand_id, id);

-- 只对最多 aimall.search.spu-candidate-limit 个 SPU 关联可售 SKU。
ALTER TABLE pro_sku
  ADD INDEX idx_sku_spu_sellable_price
      (spu_id, tn_code, is_deleted, on_state, sales_price, sort_val, id),
  ADD INDEX idx_sku_bar_code (bar_code),
  ADD INDEX idx_sku_supplier_sku_id (supplier_sku_id);

-- 截图中 sku_id 已有唯一索引；若 SHOW INDEX 确认存在，不要重复创建。
-- ALTER TABLE pro_sku_stock ADD UNIQUE INDEX uk_stock_sku_id (sku_id);

-- 类目/品牌词典刷新依赖这些小表的有效数据过滤。
ALTER TABLE pro_platform_class
  ADD INDEX idx_class_dictionary
      (tn_code, channel_code, is_deleted, is_displayed, class_level, id);

ALTER TABLE pro_platform_brand
  ADD INDEX idx_brand_dictionary
      (tn_code, is_enabled, id);

-- 执行完重新检查，不应有重复/冗余索引。
SHOW INDEX FROM pro_spu;
SHOW INDEX FROM pro_sku;

-- 用脱敏后的真实热词替换下方文本，在只读副本验证候选 CTE 的执行计划。
EXPLAIN ANALYZE
SELECT p.id,
       MATCH(p.pro_name, p.pro_suggestion, p.query_keywords, p.brand_name)
       AGAINST('办公 保温杯' IN NATURAL LANGUAGE MODE) AS search_score
FROM pro_spu p
WHERE p.is_deleted = '0'
  AND p.on_state = '1'
  AND p.audit_state = '1'
  AND MATCH(p.pro_name, p.pro_suggestion, p.query_keywords, p.brand_name)
      AGAINST('办公 保温杯' IN NATURAL LANGUAGE MODE) > 0
ORDER BY search_score DESC, p.search_weight DESC
LIMIT 1000;
