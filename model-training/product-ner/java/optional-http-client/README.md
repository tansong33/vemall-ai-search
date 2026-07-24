# Spring Boot 接入方案

> ⚠️ 这些文件是**参考实现**，不是可直接编译进你仓库的成品。我无法读取你现有的
> `NerResult` / `NerEntity` DTO 和 Controller，所以下面按最常见的 Spring Boot 3 + Jackson
> 约定写。接入第一步是打开你现有的 DTO，把 `PyNerResponse` 的字段对齐过去；
> 如果你的 DTO 是 snake_case，把 Python 侧的 `NER_FIELD_STYLE` 设成 `snake` 即可，
> **不要改 Python 代码**。

## 文件
| 文件 | 作用 |
|---|---|
| `NerProperties.java` | `ner.*` 配置绑定，含开关、超时、阈值 |
| `PyNerResponse.java` | Python 服务响应的 DTO（需与你现有 DTO 对齐） |
| `PythonNerClient.java` | HTTP 客户端：超时 / 重试 / 熔断 / 降级 |
| `HybridNerService.java` | 策略层：model / dictionary / hybrid / shadow |
| `application-ner.yml` | 配置样例 |

## 上线路径（不要跳步）
1. **shadow（影子）**：`ner.mode=dictionary`，`ner.shadow.enabled=true`。
   线上仍然 100% 用现有 Java 词典，模型结果只写日志做对比。跑 ≥3 天，用
   `ner_shadow_diff` 日志统计模型与词典的差异率、新增实体数、延迟分布。
2. **灰度**：`ner.mode=hybrid`，`ner.gray.percentage=5` → 20 → 50。
   按 `queryHash % 100` 分流，保证同一 query 稳定落在同一侧，A/B 才有意义。
3. **全量**：`percentage=100`，词典保留为熔断兜底，**永远不要删掉**。

## 熔断与降级（关键）
搜索链路不允许因为 NER 服务挂掉而失败。三层保护：
- `PythonNerClient` 超时（默认 80ms）→ 直接抛 `NerUnavailableException`
- Resilience4j 熔断：失败率 >50% 时打开 30s，期间**不发请求**直接降级
- `HybridNerService` catch 所有异常 → 调用现有 `DictionaryNerService`

Python 侧同样有一层：模型加载失败时服务照常启动，返回 `degraded=true`。
两层加起来，只有词典本身出问题才会影响搜索。
