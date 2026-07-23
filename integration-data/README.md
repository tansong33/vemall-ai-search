# 脱敏联调数据放置规范

此目录只提交格式说明和 `examples/` 中的虚构样例。真实公司数据放在以下被 Git 忽略的目录：

```text
integration-data/catalog/       SPU/SKU/库存/类目/品牌/属性脱敏快照
integration-data/queries/       脱敏 query 日志
integration-data/relevance/     query → 商品相关性金标
integration-data/reports/       联调、对账、性能报告
```

商品快照至少包含 `spu_id`、SPU 状态、类目、品牌、所有 SKU、`sales_price`、SKU 状态、总/锁定库存和 JSON 属性。query 最少包含稳定哈希 ID、脱敏文本、频次、结果数；有条件再带点击/加购/下单的匿名商品 ID。

严禁提交数据库账号、内网地址、采购人/供应商联系人、手机号、地址、真实 session/user ID 或未脱敏日志。
