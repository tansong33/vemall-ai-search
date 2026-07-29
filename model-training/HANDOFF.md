# model-training 交接文档

> 覆盖 2026-07-27 / 07-28 两天对 `model-training/` 的重构与设计定稿。
> 读这份文档不需要看聊天记录，但需要先读 [`product-ner/README.md`](product-ner/README.md)
> 了解流水线本身长什么样。

**一句话现状**：目录已收敛成单一主干，16 标签已经落到数据链路；真实 CDSGoods
导出已完成严格导入和 20k 全库抽样。当前不再卡导出，下一门禁是用修正后的 prompt
重跑 200–500 条并人工复审，合格后才扩到 20k LLM 银标。

## 0. 2026-07-28 真实导出接入更新（后文历史数字以本节为准）

- 本地 export-kit 是标准 ES Bulk **NDJSON**，147 个连续分片、734,972 个商品、
  1,469,944 行、约 1.45 GiB；全部写向 `products_v2`，action `_id == sku_id`。
- [`product-ner/scripts/import_cdsgoods.py`](product-ner/scripts/import_cdsgoods.py) 已全量跑通：
  输出 709,545 条 canonical，默认去掉 25,364 条同 SPU 同标题重复、36 条明确测试商品、
  26 条 markup/零宽脏标题和 1 条过短标题。
- canonical、词典、报告和 20k 抽样均已生成在 `product-ner/data/`、`reports/`（都被忽略）；
  20k 抽样来自全库稳定哈希，不是导出文件前 N 条，覆盖 2,296 个类目层。
- 品牌候选在标题原样命中率 98.98%；类目层级候选只有 32.44%。因此品牌可高置信弱标，
  类目仍必须逐标题 exact，数据库归档类目绝不能直接当 span。
- `spec_json/attr_json` 有“颜色=型号”等真实错配，默认不进入 canonical；显式开启后也只作
  LLM hint/审计，不能直接映射标签。
- 旧 200 条 DeepSeek 样本格式与 offset 正常，但 CATEGORY 多标、规格误作 MODEL，
  不能直接扩产。prompt、CATEGORY 单例门禁、数字规则和融合优先级已修，待新 key 环境下
  从 canonical 随机样本重跑 200–500 条。
- 主训练配置的 `max_length` 从过时的 64 调到暂定 96；下载真实 tokenizer 后必须运行
  `scripts/profile_token_lengths.py` 再决定 96/128。
- 根 `.gitignore` 已加入 `model-training/cdsgoods-export-kit-*/`。该目录只作本地输入，
  禁止 `git add -f`；本轮没有执行任何 `git add`。

---

## 1. 最容易被误解的一条：`brand_name` 是空的，但品牌数据不缺

ES 索引 `products_v2` 里 `brand_name` 和 `category_name` 普遍是空字符串，
而 `brand_id`、`category_id` 有值。第一反应是「主数据缺失，得靠 NER 抽」——**这是错的**。

数据库分析（`cdsgoods` @ 192.168.101.150）显示主数据完整：

| 主数据表 | 行数 | 关键字段 |
|---|---|---|
| `pro_platform_brand` | 21,775 | `cn_name` 100%、`en_name` 54.2%、**`aliases` 98.7%** |
| `pro_platform_class` | 10,965 | `name` 100%，三级树靠 `parent_id` 自关联 |

**空字段的原因是导出 SQL 没做 join，不是数据没有。** 已验证 `pro_sku.class_id` =
`pro_spu.class_id` = ES 的 `category_id`，且在 `pro_platform_class` 里查得到（count=1）。

这一条改变了整个训练数据策略，见第 3 节。

### 1.1 导出主表用 `pro_sku` 不是 `pro_spu`

| 字段 | `pro_spu` | `pro_sku` |
|---|---|---|
| `class_id` | 86.8% | **97.7%** |
| `brand_id` | 67.2% | **86.4%** |

ES 本来就是 SKU 级（`_id` = `sku_id`）。SKU 做主表、SPU 做 fallback，覆盖率最高。
682 万 SKU 经 `on_state='1' AND audit_state='1' AND is_deleted='0'` 过滤后约 109 万有效商品。

### 1.2 关联字段的一个坑

数据库分析文档 3.2 那张关联汇总表**写错了**：它写 `pro_spu.class_id → pro_platform_class.uuid`。
`uuid` 是 `bigint`，拿 `'C0025C0335C04747'` 去比会被 MySQL 隐式转成 0。
**正确的是 `pro_platform_class.id varchar(20)`** —— `parent_id` 注释是「父分类编码」，
指向的也是 `id`，自关联树因此自洽。

### 1.3 不要 join 的表

| 表 | 原因 |
|---|---|
| `pro_spu_detail` | 946 行，`avg_score`/`sell_num`/好评率**全 NULL**。ES 里 `rating:0` 就是它造成的 |
| `pro_supplier_sku_desc` | 800 万行，只有 88.5% 能映射回平台商品；`param`、`comment` 全空；`app_desc` 是 HTML 图文，对 NER 是纯噪声 |
| `pro_class_brand_rel` | 是「哪些类目**可以**用哪些品牌」的约束，不是商品实际品牌。**但留着做 NER 消歧有用** |
| `pro_attr_template` | `class_id` **0% 全空**，只能走 `pro_class_attr_mapping` 中间表 |
| `pro_multi_spec_detail` | 单独导出做词典，不 join 进商品，见 3.2 |

### 1.4 导出的安全边界

游标翻页（`sk.id > ?` + `ORDER BY sk.id` + `LIMIT`），不要用 `OFFSET`。
批次 **5,000**（不是 20,000）—— `spec_json`/`attr_json`/`pro_attr_json` 三个 TEXT 列
在 109 万行上是几个 GB。约 218 批，走从库、业务低峰、每批带
`/*+ MAX_EXECUTION_TIME(60000) */`。

**跑之前必须先验两条**（各几秒）：

```sql
-- ① 执行计划出现 "Using filesort" 或 "Using temporary" 就停下
EXPLAIN SELECT ... LIMIT 100;
-- ② 类目 id 唯一性。两个数不等就会成倍放大行数，109 万变几百万
SELECT COUNT(*), COUNT(DISTINCT id) FROM pro_platform_class;
```

完整 SQL 见本文档末尾附录。

---

## 2. 标签集：16 个，三方分工

标签定义的唯一事实来源是 [`product-ner/configs/labels_v1.yaml`](product-ner/configs/labels_v1.yaml)，
文件头列了改标签必须同步的六处。

```
BRAND CATEGORY MODEL SPEC CAPACITY SIZE WEIGHT PACKAGE_COMBINATION
COLOR MATERIAL FLAVOR APPEARANCE SCENE AUDIENCE FUNCTION MODIFIER
```

**关键认识：16 个标签里真正需要模型的只有 6 个左右。**

| 产出方 | 标签 | 依据 |
|---|---|---|
| 规则（`nerkit/patterns.py` 单位表） | CAPACITY / SIZE / WEIGHT / SPEC / PACKAGE_COMBINATION | 「数字+单位」是确定性模式，单位类别直接决定标签 |
| 词典（`pro_multi_spec_detail` 导出） | COLOR / MATERIAL / FLAVOR / APPEARANCE / AUDIENCE | 近似闭集枚举值 |
| 模型 | BRAND / CATEGORY / MODEL / SCENE / FUNCTION / MODIFIER | 生成性，未登录词召回的价值全在这里 |

验收硬门槛（`hard_gate_labels`）只对 **BRAND / CATEGORY / MODEL** 设。
16 个标签 × 每类 100 个实体 = 1,600 个实体的冻结 test，FLAVOR/APPEARANCE 这类
在商品标题里根本凑不够统计显著性，硬卡只会逼出无意义返工。

### 2.1 两个命名决定

- 用 **`SPEC`** 而不是标签表里的 `SPECS` —— 对齐 Java 已有的 `NerFieldMapping`，
  改 Java 要连带动 `search_ner_dict_entry` 的 CHECK 约束和现有词典数据。
- 用 **`PACKAGE_COMBINATION`**（下划线）作为「包装组合」的标签名。

### 2.2 优先级分三档，MODIFIER 必须垫底

`labels_v1.yaml` 的 `priority` 段和 `nerkit/dictionary.py` 的 `DEFAULT_PRIORITY`
必须逐字一致（`fusion.py` 从后者 import）。**Java 的 `DictionaryNerRecognizer.priorities`
也必须一致** —— 两端裁决顺序不同，同一条 query 在 Python 评测和线上会得到不同实体。

MODIFIER 给 10 分垫底，因为标签规范里它就是「无法归类到客观可测量标签」的兜底类；
不压到最低它会把该归 FUNCTION 的词全吃掉。

---

## 3. 训练数据怎么来（这是最大的设计变更）

原方案假设「没有结构化标注可用」，所以要人工标 2,000 条 + 在开源语料上中间训练。
第 1 节推翻了这个假设。现在的三路合并：

```
734,972 条 Bulk 商品 → 709,545 条 canonical
   ├─ ① DB join（免费）      brand 86.4% / class 97.7% → 在标题里定位 → BRAND/CATEGORY span
   ├─ ② 规则（免费）          nerkit/patterns.py 单位表 → 5 个测量标签
   └─ ③ LLM 银标（几十~几百元） scripts/llm_annotate.py → 11 个语义标签
                                    ↓
                          fusion.py 按 priority 裁决
                                    ↓
              人工金标 1,500–2,000 条起步（冻结 test 800–1,000，双标+仲裁）
```

canonical 全量只用于抽样、词典和覆盖分析；LLM 银标先做 20k，质量和学习曲线仍有收益
再扩到 50k。**不要把 70 万条全发给 LLM，也不要直接全塞进当前内存预编码训练器。**

### 3.1 铁律

- 银标 `annotation_source` 永远写 `silver`，**绝不进冻结 test** —— 那样评的是教师的
  口径而不是业务口径。
- 切分按 `split_group = spu_id`。同一 SPU 下的 SKU 标题高度近重复，按行切分会直接泄漏。
  商品库约 40% 是模板近重复（「插座3米」「插座5米」），不按组切 F1 虚高好几个点。
- 冻结 test 必须双人标注 + 仲裁。

### 3.2 词典种子：被低估的金矿

| 来源 | 目标标签 | 量级 |
|---|---|---|
| `pro_platform_brand` 的 `cn_name` + `en_name` + `aliases` 展开 | BRAND | 21,775 × ~3 ≈ **6 万条** |
| `pro_platform_class.name` | CATEGORY | 10,965 |
| `pro_multi_spec_detail.name`（注释举例就是「红色」「64GB」） | COLOR/CAPACITY/SIZE/MATERIAL | 788,156 行去重后几万 |

比线上现有的 96,234 条大，而且带别名和规范值。
`aliases` 字段直接解决 HUAWEI/华为 的归一化问题 —— 它就是
`search_ner_dict_entry.canonical_value` 要的东西。

---

## 4. 已完成的改动

两轮删除的历史统计仍是 **-3,640 行 / +743 行**；接入真实导出后又增加了 importer、
token 长度画像和回归测试。当前机器可运行的 90 个非 CRF 测试全绿（缺 PyTorch，独立
CRF 测试未在本轮重复执行）。
`scripts/` 3,540 行 21 个、`src/nerkit/` 2,274 行 18 个、`tests/` 759 行 9 个。

### 4.1 第一轮：RaNER 上线路径 + 废标签残留（-1,147）

| 删除 | 依据 |
|---|---|
| `train_raner.py` / `export_raner_onnx.py` / `evaluate_raner.py` | RaNER 不上线：依赖 modelscope+adaseq 会把 torch 拉回旧版；且 Java 手写的 `BertWordPieceTokenizer` 只吃 WordPiece，AdaSeq 路线本来就接不进去 |
| `configs/train_large.yaml` + `download_pretrained.py` 的 large 条目 | MacBERT-large ~324M 让 CPU 延迟涨 3 倍，在扁平短实体任务上收益不足 1 个 F1 点 |
| `model-training/annotation/`、顶层 `data/`、`artifacts/labels.example.json`、`configs/mappings/legacy-project.tsv` | 承载的是已作废的第三套标签（PRODUCT_TYPE/ATTRIBUTE_VALUE） |

同时：仲裁配置移到 [`product-ner/label_studio/adjudication_config.xml`](product-ner/label_studio/adjudication_config.xml)
并按 16 标签重建（快捷键与标注界面保持一致，仲裁人切过来肌肉记忆不能变）；
`download_pretrained.py` **补上 rbt6**（延迟兜底第一档，与 base 同词表）。

### 4.2 第二轮：整条外部语料轨道（-2,897）

`ner_data.py`(664)、`distill_raner.py`(405)、`prepare_ner_data.py`(251)、
`pre_annotate_raner.py`、`fetch_ner_data.py`、`test_ner_pipeline.py`、
`data/licenses/`、`configs/mappings/`、`build_release_manifest.py`。

**为什么第二轮能删而第一轮不能：数据来源变了，不是改主意。** 外部语料轨道存在的
理由是「在开源数据上中间训练」，而那是建立在没有结构化标注可用的假设上的。
第 1 节的 join 发现 + LLM 银标之后，2019 年那 4-6k 条外域开源数据的边际价值归零。

另外 `prepare_ner_data.py` 的防泄漏切分和 `split_dataset.py` **本来就功能重复**，
是上一次合并没清干净的。

`build_release_manifest.py` 一并删除：它要求的每一个输入（`crf.json`、
`data-report.json`、`training-run.json`、`--label-mapping`）都随上面那批消失了，
它是 RaNER 形状的。**等真有 v1 bundle 要发布时，照实际产物写个新的小门禁脚本更干净**
—— 要保留的门禁语义是：F1 门槛、相对基线回退上限、冻结 test 一致性、制品 SHA-256。

### 4.3 功能变更

**`patterns.py` 按单位类拆分**。原来一个 `SPEC_PATTERNS` 笼统盖住全部数字+单位，
现在是 `_UNIT_LABEL_LATIN` / `_UNIT_LABEL_CJK` 两张映射表，改归属只动一处。

裸 `g`/`G` 是唯一靠大小写区分的一组（`500g` 是重量，`512G` 是容量），
用**不带 `re.I`** 的独立模式处理，置信度压到 0.55 交给融合层裁决；右边界保证
`256GB` 不会被误当成裸 `G`。`test_bare_g_is_disambiguated_by_case` 守着这个行为。

**`train.py` 新增 `--init-from`**，与 `--resume` 互斥：

- `--resume` 是断点续训，恢复权重 + optimizer + 调度 + 步数
- `--init-from` 是中间训练，**只取权重，其余全部重置**；分类头 tag 数不一致时
  **自动只迁移 encoder**。开源语料/银标预热学到的是「中文商品标题长什么样」，
  那在 encoder 里；标签体系是我们自己的，硬加载分类头只会把预热成果冲掉。
- `init_from` 写进 manifest：外部语料的许可状态会顺着产物传导，必须留痕。

**新增 [`scripts/llm_annotate.py`](product-ner/scripts/llm_annotate.py)**，见第 5 节。

---

## 5. LLM 银标怎么跑

`llm_annotate.py` 走 **OpenAI 兼容协议**，不绑任何家的 SDK —— 百炼、DeepSeek 都提供
兼容端点，换模型只改 `--model` 和 `--base-url`。

### 5.1 三个刻意的设计

1. **不让 LLM 输出字符 offset。** 大模型数中文字符位置极不可靠。它只输出原文片段，
   offset 由脚本 `str.find` 回找。**回找不到的比例（`relocate_miss_rate`）就是 prompt
   质量的仪表盘** —— 模型一旦开始改写原文这个数字立刻涨，默认超过 5% 直接非零退出。
2. **不让 LLM 标那 5 个测量标签。** prompt 里明写不标，脚本还会防御性丢弃
   （`dropped_out_of_scope`），再由 `patterns.py` 重新标。收益是三重的：prompt 短
   三分之一、成本同比下降、模型不在不擅长的地方出错。
3. **固定前缀放最前**，让上下文缓存能命中 —— 这是成本的主要杠杆之一。

### 5.2 成本

旧 200 条 `deepseek-v4-flash` 实测为 16,511 输入 + 25,337 输出 token，约
82.555/126.685 token/标题。按 DeepSeek 当前输入未命中 ¥1/M、命中 ¥0.02/M、
输出 ¥2/M 计算：

| 数量 | 预计费用 | 旧脚本串行时间 | 4 路并发理论值 |
|---:|---:|---:|---:|
| 20,000 | ¥5.1–6.72 | 约 3.1 小时 | 约 47 分钟 |
| 50,000 | ¥12.75–16.80 | 约 7.8 小时 | 约 1.9 小时 |
| 709,545 | ¥181–238 | 约 4.6 天 | 约 27.5 小时 |

费用区间由固定 prompt 的缓存命中决定，修正后的 prompt/输出长度会略变。代码已支持
`--concurrency`、429/5xx 重试和 `--resume`。

重要：DeepSeek 原生没有已公开的 Batch API，百炼也明确不支持
`deepseek-v4-flash` Batch。若要 Batch 半价，应先用同一 200 条验证百炼
`qwen3.7-flash`；不能把通用 `--emit-batch-file` 直接上传给 DeepSeek V4。

### 5.3 命令

```bash
export DEEPSEEK_API_KEY=...   # 只在环境变量中设置，不写进仓库或命令参数

# ① 从全库稳定哈希样本复验 prompt。永远先做这一步
python scripts/llm_annotate.py \
    --input data/raw/cdsgoods-20260728/sample_20k.jsonl \
    --out data/silver/v1/cdsgoods_deepseek_review200.jsonl \
    --model deepseek-v4-flash --base-url https://api.deepseek.com \
    --api-key-env DEEPSEEK_API_KEY --limit 200 --batch-size 20 \
    --report reports/deepseek_v4_flash_review200.json

# ② 人工复审通过后扩到 20k；中断后同一命令追加 --resume
python scripts/llm_annotate.py \
    --input data/raw/cdsgoods-20260728/sample_20k.jsonl \
    --out data/silver/v1/cdsgoods_deepseek_20k.jsonl \
    --model deepseek-v4-flash --base-url https://api.deepseek.com \
    --api-key-env DEEPSEEK_API_KEY --batch-size 20 --concurrency 4 \
    --report reports/deepseek_v4_flash_20k.json

# ③ 只有供应商支持清单中的 Batch 模型才能 emit/upload/from-output。
# 百炼 qwen3.7-flash 可作为低价 bake-off；先用同一 gold 200 条对比质量。
```

输入仍支持 JSONL、JSON 数组、ES dump，但生产路径固定先走 importer canonical，避免
Bulk 两行错位、控制字符和无用字段进入 prompt。

### 5.4 选型：先做 bake-off，别直接拍板

200 条人工金标至少对比 `deepseek-v4-flash` 与 `qwen3.7-flash`，
量 strict span F1 和每千条成本。成本几块钱，但决定首批 20k 是否值得扩产。

旧 DeepSeek 200 条已经证明 JSON/offset 稳定不等于语义正确：CATEGORY 和 MODEL
仍可能系统性错标。必须看分标签 precision/recall，不能只看 `relocate_miss_rate`。

---

## 6. 怎么运行

### 6.0 闭环状态

2026-07-28 用 `make smoke` 全链路实跑验证过一次，七步全通：

| 步骤 | 结果 |
|---|---|
| `make_sample_data` | 188 条样例 / 633 个实体 |
| `make_smoke_assets` | 微型 encoder 96,066 参数 |
| `split_dataset` | train/validation/test 按组切分，无泄漏 |
| `train` | 4.3s，best micro-F1 0.9375，按实体级 F1 早停 |
| `evaluate --mode hybrid` | OOV BRAND recall 1.000，p50 1.02ms |
| `export_onnx --quantize` | parity max\|logit diff\| 3.81e-06，tag agreement 1.0 |
| `verify_onnx` | **实体级 micro-F1 delta = 0.0**（fp32 与 int8 都是），门禁通过 |
| `make_java_bundle` | `dist/ner-java-bundle-smoke.zip` 0.5 MB，8 个文件齐全 |

跑的是随机初始化的微型模型和合成样例，所以那些数值不代表真实性能 —— 它证明的是
**管道通、契约成立、门禁能拦**。换真数据只改输入，不改任何一步。

### 6.1 本地自检（不下载任何模型）

```bash
cd model-training/product-ner
pip install -r requirements.txt
python -m pytest tests -q     # 当前共 90 个左右；需安装 torch/transformers
make smoke                    # 造数据→训练微型模型→评估→导 ONNX→验证→打 zip
```

Windows 上 python 需要用 conda 环境 `ai-search`（3.11），系统默认的 3.14 没装 pytest。

### 6.2 云 GPU

**先说一句可能省钱的话：这个规模不需要租 GPU。** 2,000-50,000 条 × 暂定 96 token 的
macbert-base，CPU 上 1-2 小时能跑完 10 个 epoch。租 GPU 的价值是跑多组对照实验时
省等待时间，不是「跑不动」。要租的话一张 4090/A10（24G）足够，别买 A100。

```bash
# 本地：先下预训练模型，云上机器往往连不上 huggingface
HF_ENDPOINT=https://hf-mirror.com python scripts/download_pretrained.py

# 传上去（权重和数据都不进 git）
rsync -avz --exclude '.git' --exclude 'artifacts' \
      model-training/product-ner/ user@gpu-box:~/product-ner/

# 云上：torch 要按机器的 CUDA 版本单独装
pip install torch --index-url https://download.pytorch.org/whl/cu124
pip install -r requirements.txt

python scripts/train.py --config configs/train_base.yaml
python scripts/train.py --config configs/train_fast.yaml           # rbt3 延迟对照
python scripts/train.py --config configs/train_base.yaml \
    --init-from artifacts/silver-warmup/best                       # 银标预热后金标续训
make evaluate     # 三种模式都要跑，重点看未登录品牌召回而不是 micro-F1
make onnx         # 导出 + 一致性验证 + 打包给 Java
```

`requirements-lock-cpu.txt` 是 CPU 验证过的固定组合，**GPU 机器上别用**（torch 版本
对不上 CUDA）。跑完把 `artifacts/` 拉回来再关机，那些不在 git 里。

---

## 7. 未完成 / 阻塞

### 7.0 回流链路已补齐（2026-07-28）

「模型预测 → 人工修订 → 重新训练」的第一环原本是缺的：`predict.py` 输出的是 HTTP API
的形状（`query` / `took_ms` / `schema_version`），喂不进 `export_label_studio.py`，
而且没有 `--input`、不透传 `id`。新增 `scripts/predict_corpus.py` 补上，同时把
HANDOFF 早前列为缺口的「主动学习挑样本」一并解决（低置信度 / 零实体 / 缺必需标签）。

四环已用真实脚本串跑验证：predict_corpus → export_label_studio → convert_label_studio
→ split_dataset，见 `docs/runbook.md` 第 3 节。

### 7.1 阻塞在数据侧（不在代码）

| 项 | 说明 |
|---|---|
| **商品导出与导入（已完成）** | 147 分片 / 734,972 条已验证并导入；报告见 `reports/cdsgoods_import_20260728.json`（本地忽略文件） |
| **新 prompt 复验未跑** | 旧 key 已出现在聊天中，必须撤销轮换；设置新 `DEEPSEEK_API_KEY` 后从 canonical 随机样本重跑 200–500 条 |
| **真实 query 日志没拿到** | 已知分布：绝大部分 ≤6 字短句 + 一部分复制来的商品标题（比价场景）+ 少量长句。需要 200-500 条脱敏样本 + 零结果样本 |
| **零结果率/召回率没有基线** | 目标写的是「降 50%」「提升 15-20%」，没基线无法验收。建议先补埋点或改成绝对值门槛 |

### 7.2 阻塞在规范侧

**`docs/label_spec.md` 已在本轮从旧 5 标签补齐为 v1.1 / 16 标签。** 仍需要产品负责人
确认真实业务边界，尤其 CATEGORY 单例、FUNCTION/MODIFIER 和组合商品；确认前新 200 条
只作复验，不可直接扩产。

真实标题暴露的规范缺口（用这条当反例集的起点）：

```
捷科（JETECH）GTH8-250- 螺丝刀一字起子可敲击软柄贯通螺丝批改锥六角刀杆铬钒镍钢-8x250mm 10英寸
```

1. `捷科（JETECH）` 中英双写 —— 目前 prompt 里定的是拆成两个 BRAND、括号不含在内
2. `螺丝刀/一字起子/螺丝批/改锥/六角刀杆` 五个近义品类词 —— 目前定的是只标最前面最规范的
3. `8x250mm` 和 `10英寸` 是同一物理量的两种表达 —— 目前两个都标 SIZE
4. `可敲击/贯通/软柄` —— FUNCTION、APPEARANCE 还是 MODIFIER？`软柄`同时沾 MATERIAL
5. **这是商品标题不是查询**，而真实查询大部分是 6 字以内

以上 1-3 是我在 prompt 里替标注团队做的临时决定，**需要产品负责人确认后写进 label_spec**。

### 7.3 Java 侧（限定在接口范围内）

用户明确：**只改接口相关的，其余有分工，不要动。**

1. `NerFieldMapping` 补 6 个标签映射：CAPACITY / SIZE / WEIGHT /
   PACKAGE_COMBINATION / FLAVOR / APPEARANCE（按 COLOR/MATERIAL 先例进 `attributes`，
   权重待定），清理 7 个死映射（ATTRIBUTE/PRODUCT/SERIES/STYLE/REGION/PERSON/ORGANIZATION）
2. `DictionaryNerRecognizer.priorities` 对齐 `nerkit/dictionary.py` 的 `DEFAULT_PRIORITY`
3. `search_ner_dict_entry` 的 `ck_ner_dict_entity_type` CHECK 约束加上新标签

**已撤回的一条：DJL 换 tokenizer 不做。** 曾建议用 `ai.djl.huggingface:tokenizers`
替换手写的 `BertWordPieceTokenizer`（256 行）以解除「只能用 bert-base-chinese 词表家族」
的选型锁定。但那是实现替换不是接口变更，牵动 `OnnxNerModelClient` 内部和整套
parity fixture，且不阻塞 v1。**留作 v2 想换编码器时的前置工作。**

### 7.4 也撤回过的一条：Java 的两个 RaNER 类不删

`CrfViterbiDecoder.java` 和 `RaNerLabelMapper.java` 曾在删除清单里，查了调用点后撤回：
两者都接在 449 行的 `OnnxNerModelClient` 上（`RaNerLabelMapper` 还是构造函数依赖），
`NerParityTest` 也在用。CRF 解码器是懒加载的（`crfPath` 不存在就不初始化），运行时零成本。
**为省 238 行承担这个回归风险不划算。**

---

## 8. 踩过的坑

前五条是原有的（`product-ner/README.md` 第 6 节有详细版），后三条是这两天新增的：

1. Python 正则的 `\b` 把中文当词字符 —— `华为Mate60` 在 `M` 前没有词边界
2. 全局 NFKC 归一化会改变字符串长度，所有已存 offset 作废
3. Python 和 Java 的小写规则在少数码点上不一致
4. WordPiece 不是一字一 token，`[UNK]` 可能覆盖多个字符
5. 按行切分数据会泄漏，必须按组
6. **`brand_name` 的「100% 非空」是假象** —— 统计的是 `IS NOT NULL`，空字符串也算。
   同一张表 `brand_id` 只有 67.2%，两个数字不可能同时为真
7. **`pro_platform_class.id` 是 `MUL` 不是 `UNI`** —— 若有重复值，三级自 join 会成倍
   放大行数。导出前必须验
8. **ONNX Runtime 默认开满线程会和业务线程抢核** —— p99 在压测时会突然劣化。
   对策是 `intraOpNumThreads=1` + 独立线程池限流，不是加 CPU。**尚未验证现状怎么配的**

### 8.1 两个陈旧配置互相掩护，测试全绿而链路是断的

清理之后做完整性复查时发现的，两处都在「LLM 银标 → 送标 → 双标比较 → 仲裁合并」这条
主路径上，而**当时 97 个测试全绿**。

- `nerkit/label_studio.py` 的 `NER_LABELS` 硬编码着废弃的 5 标签集，16 个现役标签里
  **13 个会在 `entities_from_annotation` 被判为 `unsupported label`**；
- `llm_annotate` 自己拼 task，发 `data.id`，而消费者要 `data.query_id` ——
  任务导入 Label Studio 正常，走到仲裁才炸。

两个错误互相掩护：`test_label_studio.py` 的 fixture 用的正是那套废弃标签，所以它测的是
陈旧契约，恰好和陈旧的 `NER_LABELS` 对上。**没有任何用例把生产者的输出喂给消费者**，
这条缝就一直没人踩到。

修法：`NER_LABELS` 改为从 `dictionary.DEFAULT_PRIORITY` 派生（让需要人工对齐的地方
少一处而不是多一处）；task 构造统一到 `label_studio.build_task()`；新增
`tests/test_label_studio_contract.py` 专门守这条缝，含「16 个标签逐个过一遍消费者校验」
和「`DEFAULT_PRIORITY` 与 `labels_v1.yaml` 逐字一致」。

**教训：跨脚本契约必须有一个用例真正跨过去。** 只测生产者输出「长得对」、只测消费者
能吃「手写的样例」，两边都绿也拦不住形状漂移。

### 8.2 测试的导入环境和真实运行环境不一致

`python scripts/foo.py` 会把 `scripts/` 自动放进 `sys.path`，脚本之间因此可以
`from _common import ...`。但 `conftest.py` 只放了仓库根和 `src/`，测试里 import 同一个
脚本就 `ModuleNotFoundError`。`llm_annotate.py` 用 try/except 绕过了，`weak_label.py`
没有 —— 于是「能不能被测试 import」取决于脚本作者当时有没有加那个 try。
已在 conftest 补上 `scripts/`，让两个环境一致。

### 8.3 torch 升级会静默换掉 ONNX 导出器

torch 2.9 起 `torch.onnx.export` 的 `dynamo` 默认变成 `True`，2.13 起该路径还要求额外
安装 `onnxscript`（不在 `requirements.txt` 里）。结果是 `make smoke` 在导出这一步直接
`ModuleNotFoundError`，闭环断在倒数第二环。

但**装上 onnxscript 不是正确的修法**。Java 侧依赖的是
`input_ids`/`attention_mask` → `logits`/`tag_ids`/`confidence` 这组确切的图节点名，
而 `verify_onnx.py` 的实体级 0 差异门禁和 backend 的 `NerParityTest` 都是在 legacy 图上
验过的。换导出器必须重新验证，不能作为一次 `pip install -U` 的副作用发生。

修法：`export_onnx.py` 显式传 `dynamo=args.dynamo`（默认 `False`），提供 `--dynamo`
opt-in，并把 `onnx_exporter` 和 `torch_version` 写进 `ner_manifest.json` ——
将来 parity 对不上时，第一个要问的就是两边是不是同一条导出路径。

---

## 附录：商品导出 SQL

```sql
SELECT /*+ MAX_EXECUTION_TIME(60000) */
    sk.id                        AS sku_id,
    sk.spu_id,
    sk.pro_name                  AS title,
    sp.subtitle,
    sp.query_keywords,
    COALESCE(NULLIF(sk.brand_id,''), NULLIF(sp.brand_id,'')) AS brand_id,
    b.cn_name                    AS brand_name,
    b.en_name                    AS brand_en_name,
    b.aliases                    AS brand_aliases,
    COALESCE(NULLIF(sk.class_id,''), NULLIF(sp.class_id,'')) AS class_id,
    c3.name AS class_l3, c2.name AS class_l2, c1.name AS class_l1,
    sk.spec_json,                -- 100%，弱监督主力信号
    sk.attr_json,                -- 70.1%
    sp.pro_attr_json,            -- 67.6%
    sk.sale_unit,                -- 95.8%，量词全集，可替换 patterns.py 里手工枚举的那张子表
    sk.weight, sp.origin_place,
    sk.sales_price, sk.market_price, sp.search_weight,
    (st.total_stock_num - COALESCE(st.locked_stock_num,0)) > 0 AS in_stock
FROM pro_sku sk
JOIN      pro_spu            sp ON sk.spu_id = sp.id
LEFT JOIN pro_platform_brand b  ON COALESCE(NULLIF(sk.brand_id,''), sp.brand_id) = b.id
LEFT JOIN pro_platform_class c3 ON COALESCE(NULLIF(sk.class_id,''), sp.class_id) = c3.id
LEFT JOIN pro_platform_class c2 ON c3.parent_id = c2.id
LEFT JOIN pro_platform_class c1 ON c2.parent_id = c1.id
LEFT JOIN pro_sku_stock      st ON sk.id = st.sku_id
WHERE sk.is_deleted='0' AND sk.on_state='1' AND sk.audit_state='1'
  AND sp.is_deleted='0' AND sp.on_state='1'
  AND sk.id > ?              -- 上一批返回的最后一个 sku_id
ORDER BY sk.id
LIMIT 5000;
```

另需单独导出三份词典种子（都是小表，一次拉完）：`pro_platform_brand` 全量
（`cn_name` + `en_name` + `aliases` 展开，`canonical_value` 统一取 `cn_name`）、
`pro_platform_class` 全量、`pro_multi_spec_detail.name` 去重。
