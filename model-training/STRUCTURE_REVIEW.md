# model-training 结构梳理

> 现状盘点与整理方案。写于 label-studio 标注格式定稿之前，按仓库里**现有**的数据集格式分析。
>
> **执行状态**：已决定以 `product-ner/` 为主干。§6.1 的清理和 §6.3 的 parity 测试迁移
> **已完成**（见 §8）；§6.2 的目录合并和 §6.4 的标签集收敛**待办**。

## 0. 一句话结论

`model-training/` 下面不是"一套系统的两个部分"，而是**两套互相独立、功能重复的完整 NER 流水线**：

- **A 套** = `model-training/` 根目录（`src/data_*.py` + `annotation/` + `data/` + `artifacts/`）
- **B 套** = `model-training/product-ner/`（`scripts/` + `src/nerkit/` + `configs/` + `java/` + `service/`）

两套的**标签集不同、数据集字段不同、ONNX 制品格式不同**，谁也不能直接消费谁的产出。
根 README 第 30-39 行声称二者是"数据治理 vs 模型实验"的分工，但看代码不成立——
两套都各自实现了完整的 转换 → 校验 → 切分 → 训练 → 评测 → 导出 ONNX 链路。

另外你注意到的 Java 部分确实是重复的，而且比"重复"更严重：**它编译不进 backend**（下面 §4）。

---

## 1. 两套流水线分别是什么

### A 套：`model-training/` 根目录

| 项 | 内容 |
|---|---|
| 定位 | 面向**标注团队协作**：多人任务分配、双标比较、冲突仲裁、统一 schema |
| 标签集 | `CATEGORY` `BRAND` `PRODUCT_TYPE` `SCENE` `ATTRIBUTE_VALUE` |
| 数据格式 | `{query_id, text, group_id, source, quality_level, review_status, entities:[{start,end,text,label}]}` |
| Schema | [data/canonical-ner-record.schema.json](model-training/data/canonical-ner-record.schema.json) |
| 切分 | [src/data_split.py](model-training/src/data_split.py) — 对 `group_id` 做 SHA256 哈希分桶，确定性、无依赖 |
| 训练 | [src/train.py](model-training/src/train.py) — 朴素 HF `Trainer` + seqeval，无 CRF、无配置文件 |
| 导出 | [src/export_onnx.py](model-training/src/export_onnx.py) → `model.onnx` + `vocab.txt` + `labels.json`(数组) + `model-metadata.json` + `SHA256SUMS` |
| 依赖 | `data_*.py` **只用标准库**，不装 torch 也能跑（这是它的真实优点） |
| 测试 | `tests/test_label_studio_workflow.py`、`tests/test_doccano_workflow.py` |
| CI | ✅ [.github/workflows/ci-cd.yml:85](.github/workflows/ci-cd.yml:85) |

### B 套：`model-training/product-ner/`

| 项 | 内容 |
|---|---|
| 定位 | 面向**模型工程**：抽样去重、弱监督、防泄漏切分、CRF、实体级指标、量化、一致性验证 |
| 标签集 | `BRAND` `CATEGORY` `MODEL` `SPEC` `COLOR`（+ 停用 `MATERIAL`/`AUDIENCE`，废弃 `PRODUCT`） |
| 数据格式 | `{id, text, entities:[{start,end,label,text}], meta:{annotation_source, brand_field, category_field}}` |
| 规范 | [product-ner/docs/label_spec.md](model-training/product-ner/docs/label_spec.md)（写得相当好，有正反例和边界规则） |
| 切分 | [product-ner/scripts/split_dataset.py](model-training/product-ner/scripts/split_dataset.py) — MinHash+LSH 近重复聚类后按组切分，产出 `leaked_groups` 证明 |
| 训练 | [product-ner/scripts/train.py](model-training/product-ner/scripts/train.py) — 4 套 YAML 配置、可选 CRF、按实体级 micro-F1 选 checkpoint、导出错误样本 |
| 导出 | [product-ner/scripts/export_onnx.py](model-training/product-ner/scripts/export_onnx.py) → `model.onnx` + `model.int8.onnx` + `tokenizer.json` + `vocab.txt` + `labels.json`(`{tags,id2label}`) + `ner_manifest.json` + `fixture_*.json` |
| 依赖 | 需要 torch/transformers/onnx 全家桶 |
| 测试 | `tests/` 9 个文件（对齐、CRF、指标、融合、词典…） |
| CI | ✅ [.github/workflows/ci-cd.yml:90](.github/workflows/ci-cd.yml:90) |

**技术质量上 B 套明显更强。** 它处理了几个真问题：近重复泄漏、WordPiece offset 对齐、
NFKC 归一化改变字符串长度、Python/Java 大小写规则不一致。这些不是纸上谈兵，README §6
列的 5 个坑都有对应测试守着。A 套的 `data_split.py` 按 `group_id` 哈希分桶，
但 **`group_id` 从哪来没人负责生成**——落到 `fallback_group(text)` 就退化成按文本去重，
挡不住"插座3米/插座5米"这类近重复。

---

## 2. 功能重复对照表

| 功能 | A 套（根目录） | B 套（product-ner） | 谁更强 |
|---|---|---|---|
| Label Studio 导出转换 | `src/data_convert_label_studio.py` | `scripts/convert_label_studio.py` | B（带 offset 校验） |
| 标注校验 | `src/data_validate.py` | `scripts/validate_annotations.py` | B（用真 tokenizer 验对齐） |
| 数据集切分 | `src/data_split.py` | `scripts/split_dataset.py` | **B（MinHash 防泄漏）** |
| 评测 | `src/data_evaluate_ner.py` | `scripts/evaluate.py` | **B（model/dict/hybrid 三模式 + 未登录品牌召回）** |
| 训练 | `src/train.py` | `scripts/train.py` | **B（配置化 + CRF + 实体级选优）** |
| ONNX 导出 | `src/export_onnx.py` | `scripts/export_onnx.py` | **B（量化 + 一致性验证）** |
| Label Studio 界面配置 | `annotation/label-studio-config.xml` | `label_studio/labeling_config.xml` | 各有取舍，标签集不同 |
| 标注规范文档 | `annotation/GOLD_QUERY_WORKFLOW.md` | `docs/label_spec.md` | B（具体到正反例） |
| 脱敏样例数据 | `data/examples/*.jsonl` | `data/samples/*.jsonl` | 平 |
| 多人任务分配 | `src/data_assign_label_studio_tasks.py` | ❌ 无 | **A 独有** |
| 双标冲突比较 | `src/data_compare_label_studio.py` | ❌ 无 | **A 独有** |
| 仲裁后合并 | `src/data_merge_label_studio_exports.py` | ❌ 无 | **A 独有** |
| query 日志导入脱敏 | `src/data_import_query_log.py` | ❌ 无 | **A 独有** |
| 商品库抽样去重 | ❌ 无 | `scripts/sample_for_labeling.py` | **B 独有** |
| 词典弱标注 | `src/data_build_annotation_tasks.py`（简易） | `scripts/weak_label.py` | B |
| RaNER 预标注 | ❌ 无 | `scripts/pre_annotate_raner.py` | **B 独有** |
| 导出数据剖析 | ❌ 无 | `scripts/inspect_export.py` | **B 独有** |

**A 套真正不可替代的只有 4 个脚本**：任务分配、冲突比较、合并仲裁、query 日志脱敏导入。
其余 6 个都被 B 套的更强版本覆盖。

---

## 3. 三个真正的冲突（不是风格问题，是不兼容）

### 3.1 标签集互不兼容 —— 最要命的一个

```
A 套：BRAND  CATEGORY  PRODUCT_TYPE  SCENE  ATTRIBUTE_VALUE
B 套：BRAND  CATEGORY  MODEL         SPEC   COLOR
线上词典：BRAND  CATEGORY  ATTRIBUTE  MODIFIER   ← backend/src/main/resources/ner_dict.txt
```

只有 `BRAND` / `CATEGORY` 两个是公共的。三方各说各话：

- A 套的 `SCENE`（"给员工买"、"商务送礼"）在 B 套里根本没有对应标签；
- B 套的 `MODEL`/`SPEC`/`COLOR` 在 A 套里被合并成一个 `ATTRIBUTE_VALUE`；
- 线上真实词典 96,244 条里 **61,506 条是 `ATTRIBUTE`**（占 64%），这个标签两套都没有原样保留。

backend 是防御性地把两套标签都认了：
[ElasticsearchProductSearchRepository.java:129-135](backend/src/main/java/com/tsong/aisearch/repository/elasticsearch/ElasticsearchProductSearchRepository.java:129)
同时 `if` 了 `PRODUCT_TYPE`、`ATTRIBUTE_VALUE`、`MODEL`、`SPEC`、`COLOR`。
能跑，但这是"两个 AI 各写一半、后面有人来兜底"的痕迹，不是设计。

**标注一开工，这个必须先定死**——标错了返工的是人工标注成本，不是代码。

### 3.2 ONNX 制品格式不同（但比想象中好）

backend 通过 [application.yml:34-45](backend/src/main/resources/application.yml:34) 要：
`model.onnx` + `vocab.txt` + `labels.json`，输出节点名 `logits`。

实测两套都能喂进去：

- A 套导出正好是这个形状 ✅
- B 套导出**也兼容**：它的 `labels.json` 同时写了 `tags` 和 `id2label` 两个 key，
  而 backend 的 `loadLabels` 认 `id2label`（[OnnxNerModelClient.java:217](backend/src/main/java/com/tsong/aisearch/service/ner/OnnxNerModelClient.java:217)）；
  `vocab.txt` 也在拷贝清单里；graph 第一个输出就叫 `logits`；
  `token_type_ids` backend 是按需可选的，B 套不导出也不影响 ✅

**但阈值来源分叉了**：B 套 README 明确要求"所有解码参数从 `ner_manifest.json` 读，
不要在 Java 里写死"，而 backend 写死在 `NER_ONNX_CONFIDENCE:0.75`，
完全不读 `ner_manifest.json`。B 套自己的 `verify_onnx.py` 注释里记着这正是它抓到过的真 bug
（PyTorch 侧应用了阈值、ONNX 侧没有，26% 样本结果不同）。换模型时阈值不跟着走，同一个坑会再踩一次。

### 3.3 Java 代码重复，且**编译不进 backend**

见下节。

---

## 4. `product-ner/java/` —— 你问的重点

这里有两套 Java，加起来 10 个文件：

```
product-ner/java/src/main/java/com/aisearch/ner/     ← 进程内 ONNX 推理（推荐方案）
  OnnxNerService.java  BioDecoder.java  TextNormalizer.java  HybridNerFacade.java  NerEntity.java
product-ner/java/src/test/java/com/aisearch/ner/ParityTest.java
product-ner/java/optional-http-client/                ← HTTP 调 Python 服务（备选方案）
  PythonNerClient.java  HybridNerService.java  NerProperties.java  PyNerResponse.java  application-ner.yml
```

### 和 backend 的对应关系

| product-ner/java (`com.aisearch.ner`) | backend (`com.tsong.aisearch.service.ner`) |
|---|---|
| `OnnxNerService` | `OnnxNerModelClient` |
| `BioDecoder` | （内联在 `OnnxNerModelClient.decode`） |
| `TextNormalizer` | （内联） |
| `HybridNerFacade` | `HybridNerRecognizer` |
| `NerEntity` | `model/dto/NerEntity` |
| —— | `BertWordPieceTokenizer`（backend 独有，手写分词） |

**它不是"可以直接拿去用"的代码，是编译不过的：**

1. `NerEntity` 是 **`record`** → 需要 Java 16+，backend 是 **Java 8**（[pom.xml:21](backend/pom.xml:21)）
2. `OnnxNerService` import `jakarta.annotation.PreDestroy` → Spring Boot 3，backend 是 **2.7 + `javax.annotation`**
3. 依赖 `ai.djl.huggingface:tokenizers` 读 `tokenizer.json` → backend pom **没有这个依赖**，
   它用自己手写的 `BertWordPieceTokenizer` 读 `vocab.txt`
4. `pom-snippet.xml` 钉 onnxruntime `1.24.0`，backend 用 `1.26.0`

### 为什么这比"重复"更糟

`ParityTest` 的价值在于用 Python 生成的 fixture 断言 Java 输出逐字符一致。
但它断言的是 `com.aisearch.ner.OnnxNerService`（DJL 分词器），
**而线上跑的是 `com.tsong.aisearch.service.ner.OnnxNerModelClient`（手写 WordPiece）**。

也就是说：这个测试就算全绿，也**证明不了线上路径的 offset 是对的**。
而 B 套 README 自己反复强调"不要自己用 Java 重写 WordPiece，这是最容易出错的地方"——
backend 恰恰就是手写的。这是当前最大的隐性风险：offset 错位在线上极难定位。

### `optional-http-client/` + `service/` + `Dockerfile` + `docker-compose.ner.yml`

这一组是**第三条路线**：把模型跑成独立 Python HTTP 服务。它直接违背根 README 第 3 行
和主 README"线上推理完全运行在 Java 进程内。Python 只负责离线…不需要部署 Python Web 服务"。

主 `compose*.yml` 里**没有任何地方引用它**，`docker-compose.ner.yml` 是孤立文件。
顺带：`Dockerfile:15` 装 `torch==2.13.0` —— 这个版本号需要核实，PyTorch 从 1.13 直接跳到 2.0，
`2.13.0` 大概率是编造的，镜像会构建失败。

**结论：`java/`、`service/`、`Dockerfile`、`docker-compose.ner.yml` 是三条并行方案的参考实现，
一条都没接进去。**

---

## 5. 确定的死代码

| 路径 | 为什么 |
|---|---|
| `src/data_assign_doccano_tasks.py` `data_compare_doccano.py` `data_convert_doccano.py` `doccano_common.py` | 根 README 第 96 行：团队已选 Label Studio，Doccano "只保留为历史数据兼容"。有没有历史 Doccano 数据？没有就是 4 个文件 + 1 个测试文件的净负担 |
| `tests/test_doccano_workflow.py` | 同上 |
| `annotation/DOCCANO_WORKFLOW.md` | 同上 |
| `data/examples/doccano-export-user0*.example.jsonl` | 同上 |
| `product-ner/service/` `Dockerfile` `.dockerignore` `docker-compose.ner.yml` | 与既定架构冲突，无人引用（§4） |
| `product-ner/java/optional-http-client/` | 同上，且是"备选方案的备选" |
| `product-ner/tests/test_api.py` | 测的是上面那个不部署的 FastAPI |

---

## 6. 整理方案

### 6.1 无争议、可以立刻做的

1. **删 Doccano 整条支线**（确认无历史数据后）—— 减 5 个 py + 1 个 md + 2 个样例
2. **删 `product-ner/service/` + `Dockerfile` + `docker-compose.ner.yml` + `optional-http-client/`**
   —— Python 推理服务与既定架构冲突，留着只会让下一个人以为要部署它
3. **`product-ner/java/` 从 model-training 移走**：要么删，要么合进 `backend/`（见 6.3）
4. **修 `product-ner/Dockerfile` 的 torch 版本**（如果 2 不执行的话）

### 6.2 需要你拍板：哪套做主干

**推荐方案 —— 以 B 套（`product-ner/`）为主干，把 A 套的 4 个协作脚本并进去：**

```
model-training/
├── README.md                  ← 唯一入口，说清一条链路
├── configs/                   ← B 套
├── docs/                      ← B 套（label_spec / model_selection / runbook）
│                                 + 从 A 套并入 GOLD_QUERY_WORKFLOW.md（数据分级 gold/silver/bronze）
├── label_studio/
│   ├── labeling_config.xml         ← 标签集定稿后重写
│   ├── adjudication_config.xml     ← 从 A 套 annotation/ 迁入
│   └── project_settings.md
├── scripts/
│   ├── （B 套 14 个脚本原样保留）
│   ├── assign_tasks.py             ← 由 A 套 data_assign_label_studio_tasks.py 改造
│   ├── compare_annotations.py      ← 由 A 套 data_compare_label_studio.py 改造
│   ├── merge_exports.py            ← 由 A 套 data_merge_label_studio_exports.py 改造
│   └── import_query_log.py         ← 由 A 套 data_import_query_log.py 改造（脱敏逻辑有用）
├── src/nerkit/                ← B 套核心库
├── data/  artifacts/  tests/  Makefile
└── （删除根 src/ 、annotation/ 、原 data/examples/）
```

理由：B 套的技术资产（MinHash 切分、offset 对齐、CRF、三模式评测、ONNX 一致性验证）
重写成本远高于 A 套的 4 个协作脚本——那 4 个只用标准库操作 JSON，迁移是小工作量。

**代价**：A 套的 `canonical-ner-record.schema.json` 要改造成 B 套记录格式的 schema
（B 套目前没有正式 JSON Schema，这个是 A 套值得留下的东西）。

### 6.3 `java/` 怎么处理

两个选择：

- **（推荐）删掉 `product-ner/java/`，把 parity 测试搬进 backend。**
  保留 `export_onnx.py` 产出的 `fixture_*.json`，在 `backend/src/test/java/.../ner/` 下
  新写一个针对 **`OnnxNerModelClient` + `BertWordPieceTokenizer`** 的 parity 测试。
  这样测的才是线上真正跑的那条路径。
- **（备选）把 backend 换成 DJL tokenizer。**
  彻底解决手写 WordPiece 的 offset 风险，但要引入 ~200MB 原生库，
  且 `record`/`jakarta` 那几处仍需降级到 Java 8 写法。

无论选哪个，backend 都应改成**从 `ner_manifest.json` 读阈值和 max_length**，
而不是写死在 `application.yml`（§3.2）。

### 6.4 标签集（等产品定，但建议先给出方案）

以线上词典的真实分布为锚（BRAND 27k / CATEGORY 7k / ATTRIBUTE 61k），
建议在 B 套 5 标签基础上收敛：

| 标签 | 说明 | 来源 |
|---|---|---|
| `BRAND` | 品牌 | 两套 + 词典都有，无争议 |
| `CATEGORY` | 品类中心词 | 同上（A 套的 `PRODUCT_TYPE` 与此高度重叠，建议合并） |
| `MODEL` | 型号系列 | B 套 |
| `SPEC` | 规格参数 | B 套；对应词典 `ATTRIBUTE` 的大头 |
| `COLOR` | 颜色 | B 套；也可并入 SPEC 看 ES 是否单独建字段 |
| `SCENE` | 使用场景（"送礼"、"办公"） | A 套独有；**取决于 ES 里有没有对应可过滤字段**，没有就不要标 |

判断准则用 B 套 `docs/label_spec.md` 里那条就够了：**每个标签必须对应一个真实的 ES 过滤字段**，
标了但下游用不上的标签，就是白白消耗标注人力。

---

## 7. 建议动手顺序

1. ~~确认无历史 Doccano 数据 → 删 Doccano 支线~~ ✅
2. ~~删 Python 推理服务相关~~ ✅
3. **定标签集（产品 + ES 字段对齐）** ← 下一步，卡住标注开工
4. 按 6.2 合并目录，改写唯一的 README
5. ~~处理 `java/`~~ ✅ / backend 改读 `ner_manifest.json` ← 待办
6. Label Studio 格式定稿后，只改 `scripts/convert_label_studio.py` 一个入口

---

## 8. 已执行的改动

### 删除（`git rm`，可从历史恢复）

| 类别 | 文件 |
|---|---|
| Doccano 支线 | `src/data_assign_doccano_tasks.py`、`src/data_compare_doccano.py`、`src/data_convert_doccano.py`、`src/doccano_common.py`、`tests/test_doccano_workflow.py`、`annotation/DOCCANO_WORKFLOW.md`、`data/examples/doccano-export-user0{1,2}.example.jsonl` |
| Python 推理服务 | `product-ner/service/`、`product-ner/Dockerfile`、`product-ner/.dockerignore`、`product-ner/docker-compose.ner.yml`、`product-ner/tests/test_api.py` |
| 重复 Java | `product-ner/java/`（含 `optional-http-client/`、`ParityTest.java`、`pom-snippet.xml`） |

### 新增

- `backend/src/test/java/com/tsong/aisearch/service/ner/NerParityTest.java`
  —— 针对**真实线上路径**（`OnnxNerModelClient` + `BertWordPieceTokenizer`）的一致性测试。
  用 `mvn test -Dner.bundle=<bundle目录>` 运行；不传该参数时整个类跳过，CI 不受影响。
- `product-ner/docs/java_integration.md` —— 替代已删除的 `java/README.md`，
  按 backend 的**真实**配置项（`NER_ONNX_*` 环境变量）重写交付契约。

### 修改

- `product-ner/Makefile`：删 `serve` target
- `product-ner/requirements.txt`、`requirements-lock-cpu.txt`：删 fastapi/uvicorn/pydantic/httpx
- `.github/workflows/ci-cd.yml`：同上，不再安装只为 `test_api.py` 存在的依赖
- `src/data_build_annotation_tasks.py`：删 `--format doccano` 分支
- 各 README / workflow 文档：清理指向已删除文件的链接

### `NerParityTest` 的四条断言

| 测试 | 状态 | 说明 |
|---|---|---|
| `endToEndDecodingMatchesPythonFixture` | 需要 bundle 才能验证 | 核心验收项 |
| `offsetsIndexTheOriginalQuery` | 需要 bundle | 高亮/过滤的前提 |
| `tokenizerOffsetsNeverEscapeTheInput` | 需要 bundle | 盯 `toLowerCase(Locale.ROOT)` 的 offset 漂移 |
| `rawAndNormalisedFormsTokeniseIdentically` | **预期失败** | backend 缺等长归一化，见下 |

最后一条是**故意留红**的：Python 侧训练前跑 `nerkit.text_norm.normalize_text`
（全角→半角、零宽→空格、只小写 A-Z、严格等长），backend 侧
`OnnxNerModelClient.predict` 直接把原始 query 交给 WordPiece，**没有任何归一化**。
两端不是同一个函数，全角输入（`华为Ｍａｔｅ６０`）会命中 `[UNK]` 导致实体丢失。
修法是在 backend 加一个等长的 `TextNormalizer` —— 这属于改动线上推理行为，
没有在本次清理范围内动，留给你决定。
