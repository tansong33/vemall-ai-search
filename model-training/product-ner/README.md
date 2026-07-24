# 中文商品搜索 NER — 训练到 ONNX 交付

从零训练一个中文商品搜索命名实体识别模型，最终导出 **ONNX 给 Java 直接加载**。

```
数据 → 标注 → 训练 → 评估 → 导出 ONNX → 一致性验证 → Java 加载
```

---

## 0. 五分钟自检（不下载任何模型，验证全链路可跑）

```bash
pip install -r requirements.txt
make test      # 63 个单元测试
make smoke     # 造样例数据 → 训练微型模型 → 评估 → 导 ONNX → 验证一致性 → 打包 zip
```

`make smoke` 跑完会得到 `dist/ner-java-bundle-smoke.zip`，就是 Java 要的东西的**完整形态**
（只是模型是随机初始化的微型网络）。先跑通它，再换真数据。

---

## 1. 需要准备什么数据

### 必需

| 数据 | 从哪来 | 用途 | 最少量 |
|---|---|---|---|
| **商品标题 + 结构化 brand/category** | `export.json`（你的 ES 导出，约 109 万条） | 抽样送标 + 弱监督预标注 | 全量即可，脚本会抽样 |
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

### 可选（能省不少标注时间）

阿里达摩院的电商 NER 模型可以当**预标注老师**，比词典准得多：
```bash
# 单独的 venv，它会拉旧版依赖
pip install "modelscope>=1.9,<2" adaseq
python scripts/pre_annotate_raner.py --input data/raw/batch1.jsonl \
    --out-label-studio data/label_studio/import_batch1_raner.json
```
产出是 **silver（银标）**，必须人工过一遍才能当 gold。详见 `docs/model_selection.md`。

### 数据量建议

| 阶段 | 人工标注量 | 说明 |
|---|---|---|
| 冷启动 | 1,500 - 2,000 条 | 能得到第一个可用模型 |
| 第二批 | +2,000 条 | 用主动学习挑低置信度/未登录词样本 |
| test 集 | 800 - 1,000 条**独立冻结** | 每个标签至少 100 个实体才有统计意义 |
| 稳定期 | 每周 +500 条 | 跟上新品牌新品类 |

有了 RaNER 或词典预标注，一条标题人工修正大约 5-10 秒，2000 条约 3-5 小时。

---

## 2. 算力

租 GPU 就够，不需要多卡：

| 配置 | 训练 5,000 条 × 10 epoch | 备注 |
|---|---|---|
| RTX 4090 / A10 (24GB) | **约 3-6 分钟** | 完全够用，推荐 |
| A100 40GB | 约 2-4 分钟 | 过剩 |
| 纯 CPU | 约 1-2 小时 | 也能跑，调试可用 |

这个任务数据量小、序列短（64 token），瓶颈从来不是算力。**把钱花在标注上，不是显卡上。**

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

### 3.2 摸清你的数据

```bash
python scripts/inspect_export.py --input D:/ai-search/export.json --limit 200000 \
    --out reports/export_profile.json
```

**重点看 `weak_label_feasibility.brand_verbatim_in_title_pct`**：结构化 brand 字段在标题里
原样出现的比例。低于 70% 说明得先做品牌别名表，否则弱监督生成不出 offset。

脚本会自动探测字段名；探测错了用 `--field title=xxx --field brand=yyy` 覆盖。

### 3.3 抽样 + 预标注

```bash
python scripts/sample_for_labeling.py --input D:/ai-search/export.json \
    --n 2000 --max-per-template 2 --out data/raw/batch1.jsonl

python scripts/weak_label.py --input data/raw/batch1.jsonl \
    --dict data/dict/brand.tsv --dict data/dict/category.tsv \
    --out-jsonl data/silver/v1/batch1.jsonl \
    --out-label-studio data/label_studio/import_batch1.json
```

抽样不是均匀随机：商品库里 40% 是模板近重复（「插座3米」「插座5米」），均匀抽会浪费标注人力
并虚高评估分数。脚本会去重 + 限制每个模板家族的条数 + 按类目分层。

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

python scripts/split_dataset.py --input data/gold/v1/batch1.jsonl \
    --outdir data/processed/v1 --ratios 0.8 0.1 0.1 --gold-only-test
# split_report.json 里 leaked_groups 必须是 []
```

切分是**按组**不是按行：近重复商品会跨 train/test 泄漏，用 MinHash+LSH 聚类后整组划分。
不这么做，F1 会虚高好几个点。

### 3.6 训练

```bash
python scripts/train.py --config configs/train_base.yaml

# 想跑对照实验
python scripts/train.py --config configs/train_large.yaml    # MacBERT-large
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

把 `dist/ner-java-bundle.zip` 解压到服务器，按 `java/README.md` 接入。
Java 侧必须跑 `ParityTest` —— 它用 Python 生成的 fixture 断言两端输出**逐字符一致**。

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
| Java ParityTest | 全绿 | 同上 |
| CPU 单条 p99 | ≤ 30ms | 搜索链路预算 |

任一不达标 → 留在影子模式继续补数据，不要动线上模式开关。

---

## 5. 目录说明

| 路径 | 内容 |
|---|---|
| `models/pretrained/` | **预训练模型放这里**，配置已指向 |
| `data/` | `raw`(待标) / `silver`(弱标) / `gold`(人工) / `processed`(切分后) / `dict`(词典) / `samples`(样例) |
| `scripts/` | 全部命令行工具，每个都有 `--help` |
| `src/nerkit/` | 核心库：offset 对齐、标签、CRF、指标、词典、融合、ONNX 推理 |
| `java/` | **ONNX 进程内推理的 Java 实现 + 一致性测试** |
| `configs/` | base / large / fast / smoke 四套训练配置 |
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
- `java/README.md` — Java 接入与一致性测试
