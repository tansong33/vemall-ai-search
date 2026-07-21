# AI 商城搜索开发文档：从 NER 训练到 500 万 SKU 上线

> 文档状态：V1.0
> 适用项目：`ai-mall-demo`
> 在线运行约束：JDK 8、Spring Boot 2.7、MySQL 8
> 更新日期：2026-07-21

## 1. 目标与最终形态

本项目的目标不是让 NER 单独“替代所有向量检索”，而是让确定性需求尽可能走低延迟结构化搜索，把慢模型和语义检索从默认主链路移走。

目标架构：

```text
用户 Query
  │
  ├─ Query 规范化、热词缓存
  │
  ├─ 极速规则
  │    ├─ 商品 ID / SKU
  │    ├─ 价格、数量、容量
  │    └─ 现货、专票、Logo、积分等布尔条件
  │
  ├─ 小模型 NER / 意图分类（仅在规则不能完整确定时运行）
  │    ├─ CATEGORY
  │    ├─ BRAND
  │    ├─ PRODUCT_TYPE
  │    ├─ SCENE
  │    └─ ATTRIBUTE_VALUE
  │
  ├─ 实体标准化与置信度路由
  │
  ├─ MySQL FULLTEXT + 结构化索引，最多召回 200 条
  │
  ├─ Java 规则引擎精排
  │
  └─ 立即返回商品列表

可选旁路：
  ├─ 低置信度 / 零结果 Query → ANN 语义补召回
  └─ 导购话术 → 独立异步 SSE，不阻塞商品结果
```

核心原则：

1. 商品编号、数字和强业务约束优先使用规则，不浪费模型计算；
2. NER 只解决它擅长的实体边界和上下文歧义；
3. 大模型只参与离线标注、改写、困难样本挖掘和 Teacher 生产；
4. 任何模型异常都必须回退到现有 `RuleBasedNerService`；
5. 搜索优化以端到端 Recall/NDCG 和 P95/P99 为准，不能只看 NER F1。

## 2. 第一版技术决策

### 2.1 模型选择

第一轮只比较以下四个基线：

| 实验 | 模型 | 用途 | 是否在线 | 目的 |
|---|---|---|---|---|
| A | 当前规则 NER | 基线与永久兜底 | 是 | 得到真实速度、召回和错误分布 |
| B | `hfl/minirbt-h256` | Student 直接微调 | 是 | 首选轻量模型 |
| C | `hfl/chinese-macbert-base` | Teacher 微调 | 否 | 建立精度上限、生成 soft labels |
| D | MacBERT → MiniRBT-H256 | 下游任务蒸馏 | 是 | 判断蒸馏是否优于 Student 直接微调 |
| E（可选） | `hfl/rbt3` | 较大 Student 对照 | 是 | MiniRBT 精度不足时评估容量收益 |

推荐结论：

- Teacher：`hfl/chinese-macbert-base`。它不进入在线 Java 服务，只用于获得更好的领域标注和 logits。
- Student 首选：`hfl/minirbt-h256`。官方资料给出的结构是 6 层、hidden size 256、约 10.4M 参数，适合 CPU 和短 Query。
- Student 对照：`hfl/rbt3`。它是 3 层但 hidden size 较宽，约 38.5M 参数；当 MiniRBT 的长尾品牌/商品类型召回不足时再测。
- 第一版不要上十亿参数 SLM。商城短 Query 的实体抽取没有必要承担其内存、并发和部署成本。
- 第一版使用 `BERT + token classification softmax`，不要先加 CRF。只有当 BIO 非法转移和边界错误确实成为主要问题时，再评估 CRF；CRF 会增加导出和 Java 后处理复杂度。

模型卡许可为 Apache-2.0，但上线前仍需由公司合规流程保存模型版本、commit hash、许可证副本和依赖清单。

### 2.2 规则和模型如何分工

| 信息 | 首选识别方式 | 原因 | 最终约束 |
|---|---|---|---|
| 商品 ID / SKU | 正则 + 唯一索引 | 规则几乎无歧义 | 主键/唯一键直查 |
| 价格上下限 | 正则 | 数字边界明确 | 数据库硬过滤 |
| 数量、容量 | 正则 + 单位归一化 | 可解释、稳定 | 规则匹配/属性过滤 |
| 现货、积分、专票、Logo | 规则词典 + 否定词处理 | 属于业务布尔条件 | 规则硬过滤 |
| 类目 | NER + 类目词典 | 有别名和上下文歧义 | 数据库结构化过滤 |
| 品牌 | NER + 品牌词典 | 长尾、新品牌多 | 数据库结构化过滤 |
| 商品类型 | NER | 表达变化大 | FULLTEXT 查询增强 |
| 场景 | NER + 同义词标准化 | “清凉福利”“送客户”等需要上下文 | 召回与规则加分 |
| 颜色、材质、风格 | NER/词典混合 | 值域半开放 | 先软打分，数据质量足够后再硬过滤 |

实体合并优先级必须固定：

```text
显式规则 > 数据库词典精确命中 > 模型高置信度结果 > 模型低置信度结果
```

模型不得覆盖已经由规则识别出的价格、ID 和强布尔条件。

## 3. 验收指标先于训练

没有统一评测集和服务指标时，不允许开始“凭感觉调模型”。

### 3.1 在线服务 SLO

建议把第一版验收门槛设为：

| 指标 | 建议门槛 | 统计口径 |
|---|---:|---|
| NER batch=1 P50 | ≤ 8 ms | 目标生产 CPU、预热后 |
| NER batch=1 P95 | ≤ 15 ms | Query 最大长度 64 token |
| 搜索接口 P95 | ≤ 150 ms | 不含网络到客户端时间 |
| 搜索接口 P99 | ≤ 300 ms | 混合热词和长尾词 |
| 缓存命中接口 P95 | ≤ 10 ms | L1 命中 |
| API 错误率 | < 0.1% | 排除客户端 4xx |
| 模型异常回退成功率 | 100% | 回退规则链路必须可用 |

这些值是项目目标，不是当前已经达到的结论。必须在真实部署 CPU、JDK 8 和真实 500 万商品库上验证。

### 3.2 NER 离线指标

至少报告：

- 实体级 micro precision / recall / F1；
- 每种实体独立 precision / recall / F1；
- 完整 Query 槽位完全正确率（slot exact match）；
- CATEGORY、BRAND 的召回率；
- 否定条件误识别率；
- OOV 新品牌和长尾商品词召回率；
- 模型置信度的可靠性曲线。

推荐准入条件：

- 关键实体 CATEGORY、BRAND recall 不低于 96%；
- 关键实体 F1 相比规则基线有明确提升；
- Student 相比 Teacher 的总体 F1 下降不超过 1 个百分点；
- INT8 相比 FP32 的总体 F1 下降不超过 0.3 个百分点；
- 任何核心业务切片不能以总体分提高为理由发生明显退化。

具体阈值应在第一批人工金标完成后根据真实难度校准。

### 3.3 搜索端到端指标

NER F1 高不代表商品结果好。还必须评估：

- Recall@50、Recall@200；
- NDCG@10、NDCG@20；
- MRR；
- 零结果率；
- 超预算、无库存、违反硬属性约束的结果比例；
- 首次点击率、搜索后加购率、搜索后转化率；
- Query 改写率和二次搜索率。

上线决策以搜索端到端指标优先，NER 指标用于定位原因。

## 4. 标签体系设计

### 4.1 第一版实体类型

建议只标以下五类模型实体：

| 实体 | 示例 | 输出字段 |
|---|---|---|
| `CATEGORY` | 数码、茶叶、小家电 | `IntentResult.category` |
| `BRAND` | 膳魔师、华为、京东京造 | `IntentResult.brand` |
| `PRODUCT_TYPE` | 保温杯、降噪耳机、香薰礼盒 | `keywords/searchText` |
| `SCENE` | 员工福利、商务送礼、户外团建 | `IntentResult.scenes` |
| `ATTRIBUTE_VALUE` | 316 不锈钢、磨砂黑、北欧风 | `IntentResult.attributes` |

价格、数量、容量、SKU 和布尔条件仍由规则抽取，不进入第一版模型标签。等规则错误成为主要瓶颈后再扩标签。

### 4.2 BIO 标注规则

使用 `BIO`：

```text
B-CATEGORY, I-CATEGORY
B-BRAND, I-BRAND
B-PRODUCT_TYPE, I-PRODUCT_TYPE
B-SCENE, I-SCENE
B-ATTRIBUTE_VALUE, I-ATTRIBUTE_VALUE
O
```

标注约定：

1. 标注用户原文中的实际字符，不标注推断出来但原文不存在的词；
2. 第一版不支持嵌套实体，冲突时优先 `BRAND > PRODUCT_TYPE > CATEGORY > SCENE > ATTRIBUTE_VALUE`；
3. “华为手机”标为 `华为=BRAND`、`手机=PRODUCT_TYPE`，不要整体标为品牌；
4. “适合程序员的福利”中，“程序员”不是场景实体，可由离线扩写映射到办公/健康，但不能伪造原文 span；
5. “不要红色”仍标 `红色=ATTRIBUTE_VALUE`，否定关系由规则或单独属性保存；
6. 英文字母统一做 NFKC 与小写规范化，但必须保留原始字符 offset；
7. 数据处理脚本必须验证 `text[start:end] == entity.text`。

推荐原始标注格式：

```json
{
  "query_id": "q_000001",
  "text": "给员工买50元以内的膳魔师保温杯",
  "intent": "BENEFIT_PROCUREMENT",
  "entities": [
    {"start": 1, "end": 3, "text": "员工", "label": "SCENE"},
    {"start": 10, "end": 13, "text": "膳魔师", "label": "BRAND"},
    {"start": 13, "end": 16, "text": "保温杯", "label": "PRODUCT_TYPE"}
  ]
}
```

这里的价格仍由规则识别，不要求人工标注。

### 4.3 意图分类是否需要同时做

第一版可先不训练意图分类，让 `Rule -> DB` 路由继续由规则决定。出现以下问题时再增加 intent head：

- 商品搜索、售后问答、闲聊无法稳定分流；
- “找同款”“对比”“批量采购”等路由需要不同下游；
- 仅用实体无法区分用户是排除、比较还是购买。

建议意图集合从小开始：

```text
EXACT_ITEM
PRODUCT_SEARCH
BENEFIT_PROCUREMENT
GIFT_SEARCH
COMPARE
NON_SEARCH
UNKNOWN
```

不要把类目名称同时设计成几百个意图；类目属于实体，不属于意图。

## 5. 数据建设

### 5.1 数据来源

按优先级使用：

1. 真实历史搜索 Query；
2. 搜索后点击、加购、购买行为；
3. 客服/采购需求中的脱敏文本；
4. 运营维护的类目、品牌、属性和同义词词典；
5. 大模型离线生成的改写与困难样本。

行为日志只能作为弱监督信号。点击商品的类目不一定就是 Query 中的实体，不能直接当作人工金标。

### 5.2 隐私与数据治理

训练前必须：

- 删除姓名、手机号、邮箱、地址、订单号和企业敏感信息；
- 对用户 ID 做不可逆散列；
- 建立原始数据、脱敏数据、标注数据的访问权限；
- 记录数据来源、时间范围和删除策略；
- 禁止把公司 Query 直接上传到公共模型平台；
- 保存标注规范版本和数据集版本。

### 5.3 第一阶段数据量

建议从以下规模开始，不需要一开始做“亿级蒸馏”：

| 数据 | 建议规模 | 用途 |
|---|---:|---|
| 人工金标训练集 | 10,000～20,000 | 第一版训练 |
| 人工验证集 | 2,000～3,000 | 调参、阈值选择 |
| 人工测试集 | 3,000～5,000 | 最终一次性验收 |
| 规则/Teacher 弱标数据 | 100,000～1,000,000 | 蒸馏与长尾增强 |
| 搜索相关性 judgment | 2,000+ Query | Recall/NDCG 评测 |

如果数据较少，先扩大高质量金标，不要先扩大低质量合成数据。

### 5.4 采样策略

训练集不能只按流量随机抽样。建议组合：

- 40% 高频 Query；
- 25% 中长尾 Query；
- 15% 零结果 Query；
- 10% 多条件长句；
- 5% 拼写错误、中英文混写、型号；
- 5% 否定、比较、歧义和非搜索 Query。

品牌、类目、场景需要做最小样本数保护，避免热门品牌吞掉梯度。

### 5.5 数据切分

- 推荐按时间切分，而不是完全随机切分；
- 相同规范化 Query 必须放在同一分区；
- 同一合成模板产生的数据必须放在同一分区；
- 测试集保留一批训练时未出现的新品牌、新商品类型；
- 测试集冻结后不得参与阈值和超参数选择。

### 5.6 大模型离线标注流程

```text
原始 Query
  → 脱敏/去重/分桶
  → 规则预标
  → Teacher/大模型 JSON 标注
  → 一致性检查
  → 低一致样本进入人工复核
  → 高一致样本作为弱标数据
  → 人工金标独立维护
```

不要把大模型输出直接称为金标。建议对同一 Query 使用两次不同提示或两个模型，只有实体边界和标签一致时才自动收录；冲突样本进入人工标注。

## 6. 训练与调优

### 6.1 离线工程目录

建议增加独立目录，不把 Python 训练依赖放进 Java 服务：

```text
ml/
├── README.md
├── requirements.lock
├── configs/
│   ├── teacher_macbert.yaml
│   ├── student_minirbt.yaml
│   └── distill_minirbt.yaml
├── data/
│   └── README.md              # 不提交真实训练数据
├── src/
│   ├── prepare_dataset.py
│   ├── train_token_classifier.py
│   ├── distill.py
│   ├── evaluate.py
│   ├── export_onnx.py
│   └── compare_runtime.py
└── artifacts/                 # Git LFS/制品库，不直接进普通 Git
```

离线训练使用 Python/PyTorch 不会改变在线 Java 8 约束。生产服务仍只加载 ONNX 文件。

### 6.2 Teacher 训练

Teacher 使用 MacBERT-base 加 token classification head。

首轮超参数搜索范围：

| 参数 | 搜索范围 |
|---|---|
| max sequence length | 32、48、64 |
| learning rate | `1e-5`、`2e-5`、`3e-5`、`5e-5` |
| batch size | 32、64（显存不足时梯度累积） |
| epoch | 3～10，按 dev entity F1 early stop |
| weight decay | 0、0.01 |
| warmup ratio | 0、0.05、0.1 |
| random seed | 至少 3 个 |

选择模型时优先看：

1. dev 实体 F1；
2. CATEGORY/BRAND recall；
3. hard slices；
4. 三个随机种子的均值和波动；
5. 再看单次最高值。

### 6.3 Student 直接微调

MiniRBT 官方说明指出，小模型通常需要比 base 模型更高的学习率和更多迭代。建议单独搜索：

| 参数 | 搜索范围 |
|---|---|
| learning rate | `3e-5`、`5e-5`、`8e-5`、`1e-4` |
| epoch | 5、8、10、15 |
| max length | 32、48、64 |
| dropout | 0.1、0.2 |
| class weight | 无 / 按实体频率平滑加权 |

不要把 Teacher 的最佳学习率原样复制给 Student。

### 6.4 下游任务蒸馏

先做简单、稳定的 logits 蒸馏：

```text
L = α × CrossEntropy(student, gold)
  + β × T² × KL(student_logits / T, teacher_logits / T)
```

首轮搜索：

- `T`：2、4、6；
- `α`：0.3、0.5、0.7；
- `β = 1 - α`；
- gold 数据和弱标数据分开记录 loss；
- 弱标样本可以降低 sample weight；
- 后续只有在 logits 蒸馏不够时再增加 hidden-state/attention loss。

Teacher 和 Student 必须使用兼容的标签集合。Tokenizer 不一致时要通过原始字符 offset 对齐 token，不能按 token 序号硬对齐。

### 6.5 数据增强

优先做贴近线上错误的数据增强：

- 品牌别名：`THERMOS/膳魔师`；
- 单位变体：`500ml/500毫升/0.5L`；
- 口语价格：`五十块以内/人均 50/单价别超 50`；
- 场景改写：`送客户/商务伴手礼/客户答谢`；
- 拼写与空格：`i phone/iphone/苹果手机`；
- 语序变化和条件组合；
- 否定样本：`不要红色`、`不需要定制`；
- 容易混淆的 hard negative。

每种增强都要保留 `augmentation_type`，以便发现某类合成数据导致偏差。

### 6.6 置信度调优

不要设置一个全局 `0.8` 阈值。应在验证集上为不同实体分别选择阈值，例如：

```yaml
confidence:
  category: 0.85
  brand: 0.92
  product-type: 0.75
  scene: 0.78
  attribute-value: 0.82
```

选择目标：

- BRAND 更偏 precision，避免把普通词当品牌导致数据库硬过滤为空；
- CATEGORY 要兼顾 recall，但低置信度时可以只作为软查询词，不直接做硬过滤；
- SCENE 和 PRODUCT_TYPE 可偏 recall，用于全文召回增强；
- 低置信度实体记录到日志并进入主动学习队列。

## 7. ONNX 导出、量化与一致性验证

### 7.1 制品内容

每个模型版本必须作为不可变目录发布：

```text
models/ner/2026-08-15-minirbt-v3/
├── model.fp32.onnx
├── model.int8.onnx
├── vocab.txt
├── tokenizer_config.json
├── labels.json
├── normalization.json
├── thresholds.json
├── metrics.json
├── model-metadata.json
└── SHA256SUMS
```

`model-metadata.json` 至少记录：

- base model 和 commit hash；
- 训练数据版本；
- 代码 commit；
- 标签规范版本；
- 超参数和随机种子；
- ONNX opset；
- 导出工具版本；
- 量化方式；
- 离线指标和目标硬件延迟。

### 7.2 导出示例

以实际安装版本的 CLI 帮助为准：

```bash
optimum-cli export onnx \
  --model ml/artifacts/minirbt-ner/checkpoint-best \
  --task token-classification \
  --opset 17 \
  ml/artifacts/minirbt-ner/onnx-fp32
```

导出时必须启用验证，确认输入名、输出名和动态轴符合 Java 代码预期。

### 7.3 INT8 量化

CPU Transformer 第一轮使用动态 INT8 量化：

```bash
optimum-cli onnxruntime quantize \
  --onnx_model ml/artifacts/minirbt-ner/onnx-fp32 \
  --avx2 \
  -o ml/artifacts/minirbt-ner/onnx-int8
```

量化必须在生产同类 CPU 指令集上测试。AVX2、AVX512、VNNI 的结果不能互相代替。

量化准入条件：

- gold test entity F1 下降 ≤ 0.3 个百分点；
- CATEGORY/BRAND recall 没有明显下降；
- PyTorch 与 ONNX FP32 的 span 输出应完全一致或有明确白名单；
- INT8 P95 明确优于 FP32；
- 模型内存和进程 RSS 符合容量规划。

如果量化后没有加速，不要默认量化一定有效；CPU 指令集和量化/反量化开销都可能使旧硬件变慢。

### 7.4 一致性测试

至少抽取 10,000 条真实 Query，逐条比较：

1. Python tokenizer 的 `input_ids/attention_mask/token_type_ids`；
2. Java tokenizer 的三个输入；
3. PyTorch FP32 logits；
4. ONNX FP32 logits；
5. ONNX INT8 最终 span；
6. 中文、英文、emoji、全角字符、空格、超长 Query 的 offset。

Tokenizer 不一致是 Java 部署 NER 最常见、也最隐蔽的问题之一。

## 8. Java 8 在线接入设计

### 8.1 现有扩展点

项目已有统一接口：

```java
public interface IntentRecognizer {
    IntentResult extract(String rawQuery);
}
```

建议增加：

```text
service/ner/
├── IntentRecognizer.java
├── RuleBasedNerService.java
├── OnnxNerRecognizer.java
├── HybridIntentRecognizer.java
├── BertWordPieceTokenizer.java
├── EntityCanonicalizer.java
├── NerConfidencePolicy.java
└── ModelMetadataValidator.java
```

其中 `HybridIntentRecognizer` 设为 `@Primary`，内部组合规则和模型。

### 8.2 推荐在线流程

```text
normalize(query)
  → 规则抽取 ID/价格/数量/容量/布尔条件
  → 如果规则已经得到 EXACT_ID，直接返回
  → WordPiece tokenizer
  → ONNX Runtime 推理
  → token label 合并为字符 span
  → 置信度过滤
  → 数据库词典标准化
  → 与规则结果按优先级合并
  → IntentResult
```

异常处理：

```text
模型文件缺失 / checksum 错误 / tokenizer 错误 / native library 错误 / 推理异常
  → 记录 model_version 和异常类型
  → 立即回退 RuleBasedNerService
  → 不允许搜索接口 500
```

### 8.3 ONNX Runtime 依赖策略

官方 Java binding 支持 Java 8+，Maven 坐标为：

```xml
<dependency>
    <groupId>com.microsoft.onnxruntime</groupId>
    <artifactId>onnxruntime</artifactId>
    <version>${onnxruntime.version}</version>
</dependency>
```

不要使用 `LATEST`。升级前在真正的 JDK 8 CI 和生产操作系统执行：

```bash
mvn clean test
javap -verbose <某个ORT class> | grep "major version"
java -version
```

同时验证 JNI native library 能在目标 Linux/Windows 镜像加载。版本升级作为独立变更，不要和模型升级绑定在同一次发布中。

### 8.4 Session 和内存管理

- `OrtEnvironment` 和 `OrtSession` 按模型版本创建一次，不要每个请求创建；
- 应用启动时执行 100～500 次 warmup；
- `OnnxTensor`、`OrtSession.Result` 使用 try-with-resources 关闭；
- 预分配或复用定长输入 buffer，避免每次构造多层 Java 数组；
- 默认 `maxLength=64`，真实日志证明不足后再调整；
- 超长 Query 先规则切分，不要无界增加序列长度；
- ORT intra/inter-op thread 数必须压测，不能简单设成全部 CPU 核数；
- 多 Pod 下要防止每个 Session 都创建过多线程造成 CPU oversubscription。

### 8.5 Tokenizer

为了避免 Java 8 引入不确定的 native tokenizer 依赖，第一版建议实现与训练模型完全一致的 `BertTokenizer/WordPiece`：

- `vocab.txt` 与模型制品一起发布；
- 实现 NFKC、大小写、中文字符、标点、WordPiece 和 `[UNK]`；
- 输出原字符 offset mapping；
- 用 Python tokenizer 生成 golden cases；
- Java 单测逐项比较 token、ID、mask 和 offset。

如果后续使用第三方 Java tokenizer，也必须先通过同一套 golden tests。

### 8.6 配置建议

```yaml
aimall:
  ner:
    mode: hybrid               # rule / shadow / hybrid
    model-path: /models/ner/current/model.int8.onnx
    metadata-path: /models/ner/current/model-metadata.json
    vocab-path: /models/ner/current/vocab.txt
    max-length: 64
    warmup-runs: 200
    intra-op-threads: 1
    shadow-sample-rate: 0.10
    fallback-on-error: true
```

模型文件不要覆盖更新。先写入新版本目录、校验 checksum，再原子切换 `current` 指针或配置并滚动重启。

## 9. NER 之后的检索与排序调优

### 9.1 召回漏斗

记录每层候选数：

```text
fulltext_hits
→ structured_filtered
→ hard_rule_filtered
→ reranked
→ returned
```

如果最终无结果，必须知道在哪一层变成 0，不能只记录“没搜到”。

### 9.2 MySQL 召回调优

第一阶段：

- 执行 `sql/search_optimization.sql`；
- 用真实热词执行 `EXPLAIN ANALYZE`；
- 保持 `candidate-limit` 在 100～300 之间做 Recall/latency 对比；
- 检查 `MATCH` 列与 FULLTEXT 索引完全一致；
- 类目、品牌、价格只使用参数化等值/范围过滤；
- 禁止恢复 `%LIKE%` 和无界 `selectList`；
- 建立 query → 规范化实体和 query → response 缓存；
- 缓存 key 加租户、渠道、价格体系、规则版本和模型版本。

如果 MySQL FULLTEXT 的吞吐、分词或相关性无法达标，再把 `ProductMapper.search` 替换为 Elasticsearch/OpenSearch。不要先因为“可能需要”而同时维护两套生产索引。

### 9.3 规则引擎调优

当前手工权重只能作为起点。每次调权重必须：

1. 在冻结的 relevance judgment 上计算 NDCG/Recall；
2. 输出各规则命中率和对排序的贡献；
3. 检查运营主推是否压过相关性；
4. 检查预算贴合是否导致“越贵越靠前”；
5. 保证硬条件绝不被软分数覆盖。

积累足够点击/购买数据后，可以用 LambdaMART/LightGBM 做离线 Learning-to-Rank，再把模型导出为可在 Java 侧执行的形式。第一版不需要上复杂排序模型。

### 9.4 何时增加向量召回

仅当离线错误分析确认以下问题占比较高时增加 ANN：

- 同义词、隐喻和概念型 Query 导致 FULLTEXT Recall@200 不足；
- NER 低置信度且没有可靠结构化实体；
- 规则和 FULLTEXT 零结果但人工确认库内有合适商品。

ANN 应作为独立召回通道，只在困难 Query 或并行预算允许时触发。商品向量离线生成并写入真正的 ANN 服务，不允许回到 JVM 500 万向量暴力扫描。

## 10. 测试与压测

### 10.1 自动化测试分层

| 测试 | 内容 | 每次提交 |
|---|---|---|
| 单元测试 | 正则、NER 后处理、tokenizer、规则引擎 | 必须 |
| Golden test | Python/Java tokenizer 和实体结果一致 | 必须 |
| ONNX parity | PyTorch/FP32/INT8 对比 | 模型发布时 |
| Mapper 集成测试 | MySQL 8 ngram、索引和参数绑定 | 必须 |
| 相关性回归 | Recall/NDCG、关键 Query 集 | 必须 |
| API 集成测试 | 缓存、降级、错误码 | 必须 |
| 性能测试 | NER microbenchmark、端到端压测 | 发布前 |
| 故障测试 | Redis/模型/数据库短时故障 | 发布前 |

### 10.2 压测 Query 组成

压测不能只重复一个热词：

- 40% 热词，模拟 L1/L2 命中；
- 30% 普通已知 Query；
- 15% 长尾新词；
- 5% 商品 ID/SKU；
- 5% 多条件长句；
- 5% 零结果、非法或超长输入。

分别压测：

1. 缓存关闭；
2. 只开 L1；
3. L1 + Redis；
4. rule-only；
5. hybrid NER FP32；
6. hybrid NER INT8；
7. 数据库正常、慢查询、Redis 故障和模型故障。

### 10.3 必须采集的指标

```text
search.request.total
search.cache.hit{level=L1|L2}
search.ner.latency
search.ner.fallback
search.ner.entity.count{type}
search.db.latency{route}
search.db.candidates
search.rule.filtered{rule}
search.result.count
search.zero_result
search.total.latency
model.version
rule.version
dictionary.version
```

日志中不要记录未经脱敏的完整 Query。推荐记录 query hash、受控采样后的脱敏文本和结构化实体。

## 11. 灰度发布

### 11.1 Shadow 阶段

模型只旁路运行，不影响用户结果：

```text
线上结果仍由 RuleBasedNerService 产生
模型输出只写入对比日志
```

观察至少 7 天：

- 模型与规则实体差异；
- 模型额外耗时和 CPU；
- 低置信度比例；
- 高频错误 Query；
- 回退和 native 异常。

### 11.2 Canary 阶段

推荐顺序：

```text
1% → 5% → 10% → 25% → 50% → 100%
```

每阶段至少覆盖一个完整业务高峰。以下任一条件立即回滚：

- 搜索 P95/P99 超过 SLO；
- 零结果率显著增加；
- CATEGORY/BRAND 错误导致召回为空；
- 转化指标显著下降；
- 模型异常或进程 RSS 持续增长；
- JNI/native crash。

回滚只切换 `aimall.ner.mode=rule`，不能依赖重新发版才能恢复搜索。

## 12. 推荐开发计划

### 第 0 周：先建立基线

- [ ] 在真实测试库执行全文索引迁移；
- [ ] 导入接近 500 万规模的数据；
- [ ] 接入 Micrometer/监控；
- [ ] 冻结第一版热词和长尾压测集；
- [ ] 得到当前 rule-only P50/P95/P99、Recall@200、NDCG@20、零结果率；
- [ ] 建立慢查询和召回漏斗日志。

交付物：`baseline-performance.md`、`baseline-relevance.json`、压测脚本和 Grafana 面板。

### 第 1～2 周：标签与数据

- [ ] 冻结 NER 标签规范 V1；
- [ ] 完成脱敏和去重；
- [ ] 标注第一批 3,000～5,000 Query；
- [ ] 计算标注员一致率；
- [ ] 修订边界规则；
- [ ] 扩展到 10,000～20,000 金标；
- [ ] 建立时间切分和 hard slices。

交付物：`annotation-guideline-v1.md`、版本化数据集、数据质量报告。

### 第 3 周：Teacher 和 Student 基线

- [ ] MacBERT Teacher 微调；
- [ ] MiniRBT-H256 直接微调；
- [ ] 三随机种子评测；
- [ ] 输出每实体指标和错误样本；
- [ ] 确认是否需要 RBT3 对照。

交付物：训练配置、checkpoint、`model-evaluation-v1.md`。

### 第 4 周：蒸馏与量化

- [ ] 生成 Teacher logits/弱标数据；
- [ ] 训练蒸馏 Student；
- [ ] 搜索温度和 loss 权重；
- [ ] 导出 ONNX FP32；
- [ ] 生成 INT8；
- [ ] 完成 PyTorch/ONNX parity。

交付物：不可变模型制品目录、checksum、metrics、model card。

### 第 5 周：Java 8 接入

- [ ] 实现 Java WordPiece tokenizer；
- [ ] 实现 `OnnxNerRecognizer`；
- [ ] 实现规则/模型合并；
- [ ] 模型元数据和 checksum 校验；
- [ ] warmup、线程、buffer 调优；
- [ ] 异常回退测试；
- [ ] JDK 8 CI 验证。

交付物：Java 集成代码、golden tests、NER microbenchmark。

### 第 6 周：搜索相关性联调

- [ ] NER 实体映射到数据库条件；
- [ ] 置信度分级：硬过滤/软召回/忽略；
- [ ] 调 candidate limit；
- [ ] 调规则权重；
- [ ] 检查零结果 Query；
- [ ] 重新计算 Recall/NDCG。

交付物：相关性回归报告、冻结 Query 集、规则版本。

### 第 7 周：性能和故障测试

- [ ] 真实 500 万 SKU 并发压测；
- [ ] Redis 故障；
- [ ] 数据库慢查询；
- [ ] 模型缺失/损坏；
- [ ] 高 CPU、低内存场景；
- [ ] 24 小时稳定性测试；
- [ ] 容量和 Pod 数计算。

交付物：`performance-report.md`、容量规划、告警阈值。

### 第 8 周：Shadow 和 Canary

- [ ] Shadow 对比；
- [ ] 修复高频差异；
- [ ] 1% Canary；
- [ ] 分阶段扩量；
- [ ] 验证一键回退；
- [ ] 完成上线复盘。

交付物：上线检查表、回滚手册、实验报告。

## 13. 项目完成定义（Definition of Done）

满足以下全部条件，才算完成第一版：

- [ ] 真实 500 万 SKU 上完成索引与容量验证；
- [ ] 搜索接口达到约定 P95/P99；
- [ ] NER、搜索相关性和业务指标都有冻结评测集；
- [ ] 模型、词典、规则、数据均可版本追踪；
- [ ] Java 8 环境通过编译、集成和稳定性测试；
- [ ] ONNX FP32/INT8 与训练模型通过 parity；
- [ ] 模型失败能够自动回退规则；
- [ ] Redis 失败不会持续拖慢所有请求；
- [ ] 无库存、超预算、强属性冲突商品不会返回；
- [ ] 完成 Shadow、Canary 和回滚演练；
- [ ] 监控能定位 NER、DB、规则、缓存中哪一段变慢；
- [ ] 文档、模型卡、标注规范、压测报告齐全。

## 14. 常见错误

1. 只看 NER F1，不看 Recall@200/NDCG；
2. 用大模型弱标数据直接当测试集；
3. 随机切分造成相同 Query 泄漏到训练和测试；
4. Python 和 Java tokenizer 不一致；
5. 一个置信度阈值用于所有实体；
6. 把低置信度品牌用于数据库硬过滤；
7. 每个请求重新创建 ONNX Session；
8. 在线请求同步写 Redis 或调用大模型；
9. 用缓存命中延迟掩盖冷查询慢；
10. 在 500 万生产表流量高峰直接创建 FULLTEXT 索引；
11. 恢复 MySQL `%LIKE%` 作为兜底；
12. 一开始同时引入 NER、ES、向量库、重排模型，导致无法归因。

## 15. 参考资料

以下资料于 2026-07-21 核对：

- [HFL Chinese MacBERT 模型卡](https://huggingface.co/hfl/chinese-macbert-base)
- [HFL MiniRBT-H256 模型卡](https://huggingface.co/hfl/minirbt-h256)
- [HFL RBT3 模型卡](https://huggingface.co/hfl/rbt3)
- [MiniRBT 官方仓库：结构、参数量与蒸馏建议](https://github.com/iflytek/MiniRBT)
- [MacBERT/中文预训练模型论文](https://arxiv.org/abs/2004.13922)
- [TinyBERT 蒸馏论文](https://arxiv.org/abs/1909.10351)
- [Hugging Face Token Classification 指南](https://huggingface.co/docs/transformers/tasks/token_classification)
- [Hugging Face Optimum ONNX 导出](https://huggingface.co/docs/optimum-onnx/en/onnx/package_reference/export)
- [Hugging Face Optimum ONNX 量化](https://huggingface.co/docs/optimum-onnx/en/onnxruntime/usage_guides/quantization)
- [ONNX Runtime Java：官方说明支持 Java 8+](https://onnxruntime.ai/docs/get-started/with-java.html)
- [ONNX Runtime Transformer 优化](https://onnxruntime.ai/docs/performance/transformers-optimization.html)
- [ONNX Runtime 量化说明](https://onnxruntime.ai/docs/performance/model-optimizations/quantization.html)
- [ONNX Runtime 线程调优](https://onnxruntime.ai/docs/performance/tune-performance/threading.html)
- [ONNX Runtime Maven Central](https://central.sonatype.com/artifact/com.microsoft.onnxruntime/onnxruntime)
