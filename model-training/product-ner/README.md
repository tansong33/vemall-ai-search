# 中文商品搜索 NER — 训练到 ONNX 交付

从零训练一个中文商品搜索命名实体识别模型，最终导出 **ONNX 给 Java 直接加载**。

```
数据 → 标注 → 训练 → 评估 → 导出 ONNX → 一致性验证 → Java 加载
```

---

## 0. 五分钟自检（不下载任何模型，验证全链路可跑）

```bash
pip install -r requirements.txt
make test      # 单元测试
make smoke     # 造样例数据 → 训练微型模型 → 评估 → 导 ONNX → 验证一致性 → 打包 zip
```

`make smoke` 跑完会得到 `dist/ner-java-bundle-smoke.zip`，就是 Java 要的东西的**完整形态**
（只是模型是随机初始化的微型网络）。先跑通它，再换真数据。

---

## 1. 需要准备什么数据

### 必需

| 数据 | 从哪来 | 用途 | 最少量 |
|---|---|---|---|
| **商品标题 + 结构化 brand/category** | `cdsgoods-export-kit-*/products-*.ndjson`（当前 734,972 条） | 先导入 canonical JSONL，再抽样送标 | 全量本地扫描，不全量调用 LLM |
| **现有词典** | 你 Java 后端那 95,901 条去重词条 | 预标注 + 线上兜底 + 计算未登录词召回 | 全量 |

词典导出成 TSV 放 `data/dict/`，格式（`data/dict/brand.tsv` 里有样例）：
```
公牛	BRAND	1.0
插座	CATEGORY	1.0
```

### 强烈建议

| 数据 | 为什么重要 |
|---|---|
| **线上真实搜索 query 日志** | 用户搜「华为 256g」不搜「华为Mate60Pro 12GB+512GB 官方旗舰店正品包邮」。<br>只用商品标题训练，模型会在真实短查询上表现明显变差。**能拿到日志就一定要用。** |

有日志的话，把它转成同样的 JSONL（`{"id":..., "text":"查询词"}`）混进抽样，建议占比 30-50%。

### 训练数据主要靠 LLM 银标，不靠人工堆量

人工从零标 2,000 条要 15-20 小时，而这个量级本身就是模型的天花板。正确做法是让
LLM 大规模产出银标，人工金标只用于 dev/test 和校准 LLM：

```bash
python scripts/llm_annotate.py --input data/raw/batch1.jsonl \
    --out data/silver/v1/batch1.jsonl \
    --model deepseek-v4-flash --base-url https://api.deepseek.com \
    --api-key-env DEEPSEEK_API_KEY --limit 200
```

产出一律是 **silver**，`annotation_source` 写死为 `silver`，**绝不进冻结 test** ——
那样评的是教师的口径而不是业务口径。API key 只放环境变量；若曾贴进聊天、日志或命令，
先撤销并重新生成。详见 3.3。

### 数据量建议

| 阶段 | 数量 | 说明 |
|---|---|---|
| 冷启动 | 1,500 - 2,000 条 | 能得到第一个可用模型 |
| 第二批 | +2,000 条 | 用主动学习挑低置信度/未登录词样本 |
| test 集 | 800 - 1,000 条**独立冻结** | 每个标签至少 100 个实体才有统计意义 |
| 稳定期 | 每周 +500 条 | 跟上新品牌新品类 |
| LLM silver | 先 20,000 条，最多先扩到 50,000 条 | 看学习曲线再决定，不标全库 |

有了 RaNER 或词典预标注，一条标题人工修正大约 5-10 秒，2000 条约 3-5 小时。

---

## 2. 算力

租 GPU 就够，不需要多卡：

| 配置 | 训练 5,000 条 × 10 epoch | 备注 |
|---|---|---|
| RTX 4090 / A10 (24GB) | **约 3-6 分钟** | 完全够用，推荐 |
| A100 40GB | 约 2-4 分钟 | 过剩 |
| 纯 CPU | 约 1-2 小时 | 也能跑，调试可用 |

这个任务的精选训练集仍然不大。当前标题字符长度 p99 是 79，主配置暂用 96 token；
最终值要用真实 tokenizer 画像确认。**把钱花在数据质量上，不是盲目扩大训练量或显卡。**

推理侧不需要 GPU：ONNX + INT8 在普通 CPU 上单条几毫秒。

---

## 3. 完整训练流程

### 3.1 下载预训练模型（放进 `models/pretrained/`）

```bash
python scripts/download_pretrained.py                # hfl/chinese-macbert-base，配置已指向这里
python scripts/download_pretrained.py --all          # 主模型 + 轻量版 + large
HF_ENDPOINT=https://hf-mirror.com python scripts/download_pretrained.py   # 国内加速
```

脚本会**拒绝没有 fast tokenizer 的模型** —— 没有它就拿不到字符 offset，整个项目的前提就没了。
选型理由见 `docs/model_selection.md`（含阿里系模型的评估）。

### 3.2 导入分片并摸清数据

```bash
python scripts/import_cdsgoods.py \
    --input ../cdsgoods-export-kit-20260728 \
    --out data/raw/cdsgoods-20260728/products.jsonl \
    --brand-dict data/raw/cdsgoods-20260728/brand.tsv \
    --category-dict data/raw/cdsgoods-20260728/category.tsv \
    --report reports/cdsgoods_import_20260728.json
```

导出文件是 Elasticsearch Bulk **NDJSON**：一行 action、一行 document，不是普通 JSONL。
导入器会流式验证 147 个连续分片、`_index`、`_id == sku_id`，等长替换标题里的
换行/tab，并只输出 NER 需要的 `text + meta`。供应商、店铺、图片、价格、库存等字段不会
进入训练文件；原始 export-kit 已被根目录 `.gitignore` 忽略，禁止 `git add -f`。

数据库品牌只有在标题原样出现时才形成 `brand_field`。数据库类目是归档类目，不是真实
标题 span，也只有原样出现时才形成 `category_field`。`spec_json/attr_json` 默认不进入
canonical；如需给 LLM 做低置信提示，可显式加 `--include-attribute-candidates`，但不得
直接转换成训练标签。

下载预训练模型后，再用真实 tokenizer 检查截断率：

```bash
python scripts/profile_token_lengths.py \
    --input data/raw/cdsgoods-20260728/products.jsonl \
    --tokenizer models/pretrained/chinese-macbert-base \
    --report reports/cdsgoods_macbert_lengths.json
```

### 3.3 抽样 + 预标注

```bash
python scripts/sample_for_labeling.py \
    --input data/raw/cdsgoods-20260728/products.jsonl \
    --n 20000 --max-per-template 2 --max-per-spu 3 \
    --out data/raw/cdsgoods-20260728/sample_20k.jsonl \
    --report reports/cdsgoods_sample_20k.json

python scripts/weak_label.py \
    --input data/raw/cdsgoods-20260728/sample_20k.jsonl \
    --dict data/raw/cdsgoods-20260728/brand.tsv \
    --dict data/raw/cdsgoods-20260728/category.tsv \
    --out-jsonl data/silver/v1/cdsgoods_20k.jsonl \
    --out-label-studio data/label_studio/import_cdsgoods_20k.json

# 先用新的全库随机样本复验 200 条。报告必须无 parse/no_result，
# CATEGORY 每条最多一个，并人工抽查 CATEGORY/MODEL/SCENE。
python scripts/llm_annotate.py \
    --input data/raw/cdsgoods-20260728/sample_20k.jsonl \
    --out data/silver/v1/cdsgoods_deepseek_review200.jsonl \
    --out-label-studio data/label_studio/import_deepseek_review200.json \
    --model deepseek-v4-flash --base-url https://api.deepseek.com \
    --api-key-env DEEPSEEK_API_KEY --limit 200 --batch-size 20 \
    --report reports/deepseek_v4_flash_review200.json

# 人工复审通过后才扩到 20k；4 路并发是保守起点。
python scripts/llm_annotate.py \
    --input data/raw/cdsgoods-20260728/sample_20k.jsonl \
    --out data/silver/v1/cdsgoods_deepseek_20k.jsonl \
    --out-label-studio data/label_studio/import_deepseek_20k.json \
    --model deepseek-v4-flash --base-url https://api.deepseek.com \
    --api-key-env DEEPSEEK_API_KEY --batch-size 20 --concurrency 4 \
    --report reports/deepseek_v4_flash_20k.json
# 中断后用同一条命令追加 --resume；脚本会校验已完成输出与输入前缀 ID。
```

抽样不是均匀随机：商品库里 40% 是模板近重复（「插座3米」「插座5米」），均匀抽会浪费标注人力
并虚高评估分数。脚本用稳定哈希做全库无顺序偏差抽样，再去重、限制模板/SPU 条数并按类目分层。
不要把 709,545 条 canonical 全部送 LLM；先做 200 条 prompt 验证，再按 2 万条起跑。
DeepSeek 原生和百炼当前都不支持 `deepseek-v4-flash` Batch；`--emit-batch-file`
只能用于供应商明确列入 Batch 支持清单的模型（例如百炼 `qwen3.7-flash`）。

### 3.4 人工标注（Label Studio）

```bash
pip install label-studio          # 单独 venv
label-studio start
```
1. 新建项目 → Settings → Labeling Interface → Code → 粘贴 `label_studio/labeling_config.xml`
2. Import → 上传 `data/label_studio/import_batch1.json`（带预标注）
3. 标注规范：`docs/label_spec.md`，贴到项目 Instructions 里
4. Export → **JSON**（不要 JSON-MIN）→ 存到 `data/label_studio/export_batch1.json`

详细设置见 `label_studio/project_settings.md`。

### 3.5 校验 → 转换 → 无泄漏切分

```bash
python scripts/validate_annotations.py --input data/label_studio/export_batch1.json \
    --tokenizer models/pretrained/chinese-macbert-base
# 错误必须清零才能继续

python scripts/convert_label_studio.py --input data/label_studio/export_batch1.json \
    --out data/gold/v1/batch1.jsonl --annotation-source gold

python scripts/split_dataset.py \
    --input data/silver/v1/cdsgoods_deepseek_20k.jsonl \
    --input data/gold/v1/batch1.jsonl \
    --outdir data/processed/v1 --ratios 0.8 0.1 0.1 --gold-only-test
# split_report.json 里 leaked_groups 必须是 []
```

切分是**按组**不是按行：近重复商品会跨 train/test 泄漏，用 MinHash+LSH 聚类后整组划分。
不这么做，F1 会虚高好几个点。

### 3.6 训练

```bash
python scripts/train.py --config configs/train_base.yaml

# 想跑对照实验
python scripts/train.py --config configs/train_fast.yaml     # RBT3，极低延迟
python scripts/train.py --config configs/train_base.yaml --set model.use_crf=true
python scripts/train.py --config configs/train_base.yaml --resume artifacts/ner-v1/last
```

按**实体级 micro-F1** 选最优 checkpoint（不是 loss，也不是 token 准确率），存到
`artifacts/ner-v1/best`。每轮打印每个标签的 P/R/F1，错误样本写到 `errors_epoch*.json`。

### 3.7 评估（三种模式都要跑）

```bash
# 模型
python scripts/evaluate.py --model artifacts/ner-v1/best --data data/processed/v1/test.jsonl \
    --dict data/dict/brand.tsv --dict data/dict/category.tsv --mode model \
    --report reports/eval_model.json --errors-out reports/errors_model.json
# 现状基线（你现在线上的行为）
python scripts/evaluate.py --data data/processed/v1/test.jsonl \
    --dict data/dict/brand.tsv --dict data/dict/category.tsv --mode dictionary \
    --report reports/eval_dict.json
# 融合
python scripts/evaluate.py --model artifacts/ner-v1/best --data data/processed/v1/test.jsonl \
    --dict data/dict/brand.tsv --dict data/dict/category.tsv --mode hybrid \
    --report reports/eval_hybrid.json
```

**必须对比三者**。本仓库 smoke 数据上的实测结果说明了为什么：

| | micro F1 | 未登录品牌召回 |
|---|---|---|
| 词典基线 | 0.923 | **0.000** |
| 模型 | 0.839 | **1.000** |

词典在已知词上更准，但未登录品牌**一个都识别不出来**。只看 micro-F1 会得出「不需要模型」的
错误结论 —— 未登录词召回才是模型的价值所在。

### 3.8 导出 ONNX 并验证

```bash
make onnx        # 等价于下面三条
```
```bash
python scripts/export_onnx.py --model artifacts/ner-v1/best --out artifacts/ner-v1/onnx --quantize
python scripts/verify_onnx.py --model artifacts/ner-v1/best --bundle artifacts/ner-v1/onnx \
    --data data/processed/v1/test.jsonl
python scripts/make_java_bundle.py --bundle artifacts/ner-v1/onnx \
    --data data/processed/v1/test.jsonl --zip dist/ner-java-bundle.zip
```

`verify_onnx.py` 不只比张量，它在**真实测试集上比实体级输出**并要求 0 差异。
（开发过程中它抓到过一个真 bug：PyTorch 路径应用了置信度阈值而 ONNX 路径没有，
26% 的样本结果不同。阈值现在写进 `ner_manifest.json` 成为契约的一部分。）

导出的图有三个输出，Java 侧不用再做 softmax/argmax：
```
输入: input_ids [B,T] int64,  attention_mask [B,T] int64
输出: logits [B,T,C] float32,  tag_ids [B,T] int64,  confidence [B,T] float32
```

### 3.9 交给 Java

把 `dist/ner-java-bundle.zip` 解压到服务器，按 `docs/java_integration.md` 接入。
线上推理跑在 Spring Boot 进程内，实现在
`ai-search-server/src/main/java/cn/vetech/ai/search/server/service/ner/`。

Java 侧必须跑 parity 测试 —— 它用 Python 生成的 fixture 断言两端输出**逐字符一致**：

```bash
cd ../.. && mvn -q -B -pl ai-search-server \
    -Dner.bundle=/path/to/ner-v1/onnx test
```

---

## 4. 上线验收标准

在**冻结的 test 集**上：

| 指标 | 门槛 | 理由 |
|---|---|---|
| micro F1 | ≥ 0.92 | 不低于现有词典 |
| BRAND F1 | ≥ 0.95 | 品牌过滤错了用户直接跳出 |
| CATEGORY F1 | ≥ 0.93 | 主过滤维度 |
| **未登录品牌召回** | **≥ 0.70** | 模型相对词典的唯一硬价值 |
| 边界错误占比 | ≤ 5% | 高了说明标注规范不清晰 |
| ONNX 与 PyTorch 实体级差异 | **= 0** | 差异不为零不许上线 |
| backend `NerParityTest` | 全绿 | 同上 |
| CPU 单条 p99 | ≤ 30ms | 搜索链路预算 |

任一不达标 → 留在影子模式继续补数据，不要动线上模式开关。

---

## 5. 目录说明

| 路径 | 内容 |
|---|---|
| `models/pretrained/` | **预训练模型放这里**，配置已指向 |
| `data/` | `raw`(待标) / `silver`(LLM 与规则产出) / `gold`(人工仲裁) / `processed`(切分后) / `dict`(词典) / `samples`(样例) |
| `scripts/` | 全部命令行工具，每个都有 `--help` |
| `src/nerkit/` | 核心库：offset 对齐、标签、CRF、指标、词典、融合、ONNX 推理 |
| `configs/` | `labels_v1.yaml`(标签集与优先级) + base / fast / smoke 训练配置 |
| `docs/` | 标签规范、模型选型、运行手册 |
| `artifacts/` | 训练产物（checkpoint、ONNX bundle） |
| `dist/` | 给 Java 的 zip |

## 6. 几个已经踩过的坑（都已修复并有测试守着）

1. **Python 正则的 `\b` 把中文当词字符** —— `华为Mate60` 在 `M` 前没有词边界，
   型号识别规则会静默失效。已改用 ASCII 前后瞻。
2. **全局 NFKC 归一化会改变字符串长度** —— `㍿` 会展开成多个字符，所有已存 offset 作废。
   归一化改为严格 1:1，有测试断言等长。
3. **Python 和 Java 的小写规则不一致** —— 少数码点上 `str.lower()` 与
   `Character.toLowerCase()` 结果不同，甚至会变成两个字符。两端都限定只小写 ASCII。
4. **WordPiece 不是一字一 token** —— `256G` 可能是一个 token，`[UNK]` 可能覆盖多个字符。
   对齐层显式处理跨边界情况，有 `boundary_policy` 开关。
5. **按行切分数据会泄漏** —— 近重复商品跨 train/test，F1 虚高。改为按组切分。

## 7. 相关文档
- `docs/model_selection.md` — 预训练模型对比（含阿里达摩院 RaNER / StructBERT / GTE 的评估）
- `docs/label_spec.md` — 实体标签规范，正反例与边界规则
- `docs/runbook.md` — 标注、主动学习、重训、发布的完整操作手册
- `docs/java_integration.md` — 交付给 backend 的产物契约、环境变量映射与一致性测试
