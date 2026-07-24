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
# ---- (1) 摸清数据 ----
python scripts/inspect_export.py --input D:/ai-search/export.json \
    --limit 200000 --out reports/export_profile.json
# 重点看 weak_label_feasibility.brand_verbatim_in_title_pct
# 低于 70% 就必须先做品牌别名表，否则结构化字段生成不了 offset

# ---- (2) 抽样 ----
python scripts/sample_for_labeling.py --input D:/ai-search/export.json \
    --n 2000 --max-per-template 2 --out data/raw/batch1.jsonl --report reports/sample1.json

# ---- (3) 预标注（二选一或都跑，后者更准）----
python scripts/weak_label.py --input data/raw/batch1.jsonl \
    --dict data/dict/brand.tsv --dict data/dict/category.tsv \
    --out-jsonl data/silver/v1/batch1.jsonl \
    --out-label-studio data/label_studio/import_batch1.json
# 可选：阿里 RaNER 电商模型当老师（独立 venv）
python scripts/pre_annotate_raner.py --input data/raw/batch1.jsonl \
    --out-label-studio data/label_studio/import_batch1_raner.json

# ---- (4) Label Studio ----
# 导入 import_batch1.json -> 人工修正 -> 导出 JSON 到
# data/label_studio/export_batch1_20260801.json

# ---- (5) 校验（错误必须清零才能进下一步）----
python scripts/validate_annotations.py \
    --input data/label_studio/export_batch1_20260801.json \
    --tokenizer hfl/chinese-macbert-base --report reports/validate_batch1.json

# ---- (6) 转换 + 切分 ----
python scripts/convert_label_studio.py \
    --input data/label_studio/export_batch1_20260801.json \
    --out data/gold/v1/batch1.jsonl --annotation-source gold
python scripts/split_dataset.py --input data/gold/v1/batch1.jsonl \
    --outdir data/processed/v1 --ratios 0.8 0.1 0.1 --gold-only-test
# split_report.json 里 leaked_groups 必须是 []

# ---- (7) 训练 ----
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

## 3. 主动学习（第二批开始）
优先挑这三类样本送标：
1. **低置信度**：`predict.py --jsonl` 输出里 `confidence < 0.6` 的实体所在样本；
2. **模型/词典分歧**：影子日志 `ner_shadow_diff` 中 `dict_n != model_n` 的 query；
3. **未登录词**：模型标出 BRAND 但 `dictionary.contains()` 为 false 的样本。

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
