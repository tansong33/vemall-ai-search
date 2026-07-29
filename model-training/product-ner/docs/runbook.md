# 运行手册：标注 → 训练 → 上线

## 0. 数据分层（不可混用）
```
data/
  raw/        抽样出来待标注的原始文本
  silver/     弱标注 / RaNER 预标注，只能进 train
  gold/       人工标注并复核，可进 train/val/test
  processed/  切分后的 train/validation/test（由 split_dataset.py 产出）
```
规则：
- **test 集只能来自 gold，且必须双标 + 仲裁**。任何自动标签都不得作为最终真值。
- 每个批次单独目录 `data/gold/v1/batch3.jsonl`，**不覆盖**旧文件。
- `split_dataset.py --gold-only-test` 会强制把 silver 数据限制在 train。

## 1. 第一批标注（建议规模）
| 阶段 | 数量 | 目的 |
|---|---|---|
| 冷启动 | 1,500-2,000 条 | 跑通流程，得到第一个能用的 baseline |
| 第二批 | +2,000 条（主动学习挑选） | 补低置信度 / 未登录品牌 |
| 稳定期 | 每周 +500 条 | 跟上新品牌、新品类 |
| test 集 | 800-1,000 条，独立冻结 | 每个标签 ≥100 个实体才有统计意义 |

标签分布目标：BRAND / CATEGORY 各 ≥1,500 个实体，MODEL / SPEC / COLOR 各 ≥500。
若某标签在首批不足 300，说明抽样有问题，回到 `sample_for_labeling.py` 调 `--max-per-category`。

## 2. 完整操作步骤

```bash
# ---- (1) 严格导入 ES Bulk NDJSON；只输出训练需要的最小字段 ----
python scripts/import_cdsgoods.py \
    --input ../cdsgoods-export-kit-20260728 \
    --out data/raw/cdsgoods-20260728/products.jsonl \
    --brand-dict data/raw/cdsgoods-20260728/brand.tsv \
    --category-dict data/raw/cdsgoods-20260728/category.tsv \
    --report reports/cdsgoods_import_20260728.json
# report 必须显示 147 files、734972 documents、products_v2，且无结构错误。
# 默认保留所有渠道；业务确认后可加：
# --channel-allowlist JDVOP,COLIPU,JD,DELI

# ---- (2) 抽样 ----
python scripts/sample_for_labeling.py \
    --input data/raw/cdsgoods-20260728/products.jsonl \
    --n 20000 --max-per-template 2 --max-per-spu 3 \
    --out data/raw/cdsgoods-20260728/sample_20k.jsonl \
    --report reports/cdsgoods_sample_20k.json

# ---- (3) 先复验 200 条 LLM；人工抽查通过后去掉 --limit 扩到 20k ----
python scripts/llm_annotate.py \
    --input data/raw/cdsgoods-20260728/sample_20k.jsonl \
    --out data/silver/v1/cdsgoods_deepseek_review200.jsonl \
    --out-label-studio data/label_studio/import_deepseek_review200.json \
    --model deepseek-v4-flash --base-url https://api.deepseek.com \
    --api-key-env DEEPSEEK_API_KEY --limit 200 --batch-size 20 \
    --report reports/deepseek_v4_flash_review200.json
# 复验门槛：batch_parse_fail/no_result/missing_batch=0、offset 全有效；
# CATEGORY/MODEL/SCENE 必须人工复审，relocate_miss 低不代表语义标签就正确。
#
# 通过后去掉 --limit，输出改为 cdsgoods_deepseek_20k.jsonl，并加
# --concurrency 4。中断后给同一命令加 --resume。
#
# deepseek-v4-flash 在 DeepSeek 原生和百炼均不支持 Batch。需要 Batch 半价时，
# 先对 qwen3.7-flash 做同一批 200 条质量对照，再使用 --emit-batch-file。

# 词典弱标：和 LLM 交叉验证，不一致的样本优先送人工
python scripts/weak_label.py \
    --input data/raw/cdsgoods-20260728/sample_20k.jsonl \
    --dict data/raw/cdsgoods-20260728/brand.tsv \
    --dict data/raw/cdsgoods-20260728/category.tsv \
    --out-jsonl data/silver/v1/cdsgoods_20k_dict.jsonl

# ---- (4) Label Studio ----
# 如果跑 LLM 时忘了 --out-label-studio，只做本地格式转换，不会再次调用模型：
python scripts/export_label_studio.py \
    --input data/silver/v1/cdsgoods_deepseek_review200.jsonl \
    --out data/label_studio/import_deepseek_review200.json
#
# 导入 import_deepseek_review200.json -> 逐条确认/修正预测 -> 导出 JSON 到
# data/label_studio/export_deepseek_review200.json

# ---- (5) 校验（错误必须清零才能进下一步）----
python scripts/validate_annotations.py \
    --input data/label_studio/export_batch1_20260801.json \
    --tokenizer hfl/chinese-macbert-base --report reports/validate_batch1.json

# ---- (6) 转换 + 切分 ----
python scripts/convert_label_studio.py \
    --input data/label_studio/export_deepseek_review200.json \
    --out data/silver/v1/cdsgoods_deepseek_review200_human.jsonl \
    --annotation-source silver
python scripts/compare_annotations.py \
    --pred data/silver/v1/cdsgoods_deepseek_review200.jsonl \
    --reference data/silver/v1/cdsgoods_deepseek_review200_human.jsonl \
    --report reports/deepseek_review200_human_compare.json
#
# 单人复查的 200 条用于校准 prompt，仍是 silver；只有双标 + 仲裁的数据才能写入 gold。
python scripts/split_dataset.py \
    --input data/silver/v1/cdsgoods_deepseek_20k.jsonl \
    --input data/gold/v1/batch1.jsonl \
    --outdir data/processed/v1 --ratios 0.8 0.1 0.1 --gold-only-test
# split_report.json 里 leaked_groups 必须是 []

# ---- (7) 用真实 tokenizer 画像确认 96 是否足够，再训练 ----
python scripts/profile_token_lengths.py \
    --input data/raw/cdsgoods-20260728/products.jsonl \
    --tokenizer models/pretrained/chinese-macbert-base \
    --report reports/cdsgoods_macbert_lengths.json
python scripts/train.py --config configs/train_base.yaml

# ---- (8) 评估（三种模式都要跑，用于决策）----
python scripts/evaluate.py --model artifacts/ner-v1/best --data data/processed/v1/test.jsonl \
    --dict data/dict/brand.tsv --dict data/dict/category.tsv --mode model \
    --report reports/eval_model.json --errors-out reports/errors_model.json
python scripts/evaluate.py --data data/processed/v1/test.jsonl \
    --dict data/dict/brand.tsv --dict data/dict/category.tsv --mode dictionary \
    --report reports/eval_dict.json      # 现状基线
python scripts/evaluate.py --model artifacts/ner-v1/best --data data/processed/v1/test.jsonl \
    --dict data/dict/brand.tsv --dict data/dict/category.tsv --mode hybrid \
    --report reports/eval_hybrid.json
```

注意：

- 结构化品牌原样命中标题时可做高置信弱标；类目也必须原样命中，不能把数据库归档类目硬塞进标题。
- `spec_json/attr_json` 存在错配，只能作为显式开启的 LLM hint，不能无条件映射为标签。
- canonical 全量用于本地抽样和词典构建，不代表要把 70 万条都发给 LLM 或全部加入训练。
- `meta.split_group=spu_id` 必须一路保留；同一 SPU 内品牌/类目偶尔冲突，所以仍按 SKU 标题逐条 exact 匹配。
- API key 只通过环境变量注入，不写进脚本、`.env`、命令参数或报告；泄露后立即撤销轮换。

## 3. 回流：模型预测 → 人工修订 → 重新训练（第二批开始）

第一版模型训完之后，标注就不该再靠盲抽了 —— 让模型自己指出它哪里不会。
整条回流四步，每步都产出 canonical JSONL，接口是统一的：

```bash
# ---- (1) 用当前模型跑一批未标注语料，同时挑出「模型没把握」的 ----
python scripts/predict_corpus.py --model artifacts/ner-v1/best \
    --dict data/dict/brand.tsv --dict data/dict/category.tsv --mode hybrid \
    --input data/raw/batch2.jsonl \
    --out data/pred/v1/batch2.jsonl \
    --uncertain-out data/pred/v1/batch2_review.jsonl \
    --tau-uncertain 0.75 --require-labels CATEGORY \
    --report reports/predict_batch2.json

# ---- (2) 只把复审队列送进 Label Studio（全量送等于没做主动学习）----
python scripts/export_label_studio.py \
    --input data/pred/v1/batch2_review.jsonl \
    --out data/label_studio/import_batch2_review.json

# ---- (3) 人在界面里逐条改。改完 Export -> JSON（不是 JSON-MIN）----
python scripts/validate_annotations.py \
    --input data/label_studio/export_batch2_review.json \
    --tokenizer models/pretrained/chinese-macbert-base
# 错误必须清零

python scripts/convert_label_studio.py \
    --input data/label_studio/export_batch2_review.json \
    --out data/gold/v1/batch2.jsonl --annotation-source gold

# ---- (4) 并进金标全集，重新切分，续训 ----
cat data/gold/v1/*.jsonl > data/gold/v1/all.jsonl
python scripts/split_dataset.py --input data/gold/v1/all.jsonl \
    --outdir data/processed/v1 --ratios 0.8 0.1 0.1 --gold-only-test
python scripts/train.py --config configs/train_base.yaml \
    --init-from artifacts/ner-v1/best
```

`predict_corpus.py` 选样本的三个信号，命中任一即入队：

| 信号 | 含义 |
|---|---|
| `low_confidence` | 最低实体置信度 < `--tau-uncertain`。取**最低**不取平均，一条里有一个没把握就该看 |
| `no_entity` | 一个实体都没抽到。未登录词高发区，模型相对词典的价值就在这里 |
| `missing:X` | `--require-labels` 指定的标签没命中，例如强制每条都要有 CATEGORY |

另外两类线上信号也值得送标，但要从 Java 侧的影子日志取：
`ner_shadow_diff` 里 `dict_n != model_n` 的分歧 query，以及模型标出 BRAND
而词典 `contains()` 为 false 的未登录词。

三条铁律：

- **模型预测的 `annotation_source` 是 `prediction`**，既不是 gold 也不是 silver。
  人工确认之前不得计入任何训练统计口径，更不许进冻结 test。
- **冻结 test 集不参与回流**。拿模型预测过、又被同一批人复审过的样本当 test，
  测的是「模型能不能复现自己」。
- **`--init-from` 不是 `--resume`**。续训要的是重置优化器和调度重新退火，
  `--resume` 会把上一轮的学习率状态带过来。

真实线上 query 日志优先于商品标题 —— 用户怎么搜，模型就该在什么分布上训练。

## 3.5 导出 ONNX 交给 Java
```bash
make onnx RUN=artifacts/ner-v1
```
三步：导出 → 在真实测试集上验证实体级输出与 PyTorch **0 差异** → 打包成 zip。
`verify_onnx.py` 返回非 0 就不要发布。Java 侧还要跑 `mvn test -Dner.bundle=...`
的一致性测试，两边都绿了才算通过。

## 4. 重训与发布
1. 新批次 gold 合并进 `data/gold/v1/`，重跑 (6)(7)(8)；
2. 新模型必须在**同一个冻结 test 集**上对比旧模型，micro-F1 与每标签 F1 都不得下降；
3. 通过后把 `artifacts/ner-v1/best` 拷到 `E:\ai-search-data\ner-models\ner-v1.<日期>\best`，
   改 compose 的挂载指向，滚动重启；
4. Java 侧先 shadow 一周再动 `ner.mode`。

## 5. 排查
| 现象 | 排查点 |
|---|---|
| `/health` 显示 degraded | 检查 `NER_MODEL_DIR` 下是否有 `ner_config.json` + `head.pt` |
| 实体 offset 对不上前端高亮 | 确认 Java 传的是**原始** query，没有先做 trim/全角转换 |
| F1 高但线上体感差 | test 集大概率有泄漏，查 `split_report.json` |
| 某标签 F1 突然掉 | 看 `errors_epoch*.json`，多半是标注规范漂移 |
