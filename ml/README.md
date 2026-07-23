# NER 数据与评测工作区

这个目录与 Java 8 在线服务解耦。这里可以使用 Python 做标注准备、训练和 ONNX 导出，线上只消费审核过的模型制品。

## 现在即可运行的闭环

所有 `ml/src/data_*.py` 只用 Python 标准库，先不安装 PyTorch 也能执行：

```powershell
python ml/src/data_validate.py ml/data/examples/ner_gold.example.jsonl
python ml/src/data_split.py ml/data/examples/ner_gold.example.jsonl --output-dir ml/data/splits-demo
python ml/src/data_evaluate_ner.py `
  --gold ml/data/examples/ner_gold.example.jsonl `
  --predictions ml/data/examples/ner_predictions.example.jsonl
python ml/src/data_build_annotation_tasks.py `
  --queries ml/data/examples/raw_queries.example.jsonl `
  --dictionary ml/data/examples/dictionaries.example.json `
  --output target/label-studio-tasks.json `
  --format label-studio
```

真实数据不要提交 Git。建议放到公司受控对象存储，Git 里只保留 schema、脱敏样例、脚本和不可逆的版本摘要。

收到第 6 项 query 日志 CSV 后，先转换成统一 JSONL。若输入含内部 query/session ID，必须在当前终端设置只用于本批次的哈希盐（不要写入文件或 Git）：

```powershell
$env:QUERY_HASH_SALT='<从公司密钥系统临时注入>'
python ml/src/data_import_query_log.py `
  --input integration-data/queries/query-log.csv `
  --output ml/data/raw/query-log.jsonl
```

默认读取 `query/query_id/session_id/frequency/result_count` 列；列名不同时使用脚本参数覆盖。转换后再运行词典预标、人工复核、校验和按 group 切分。

## 标注工具

当前团队选择 **Label Studio** 做纯文本序列标注。完整的建项目、8 人任务分配、双标比较、冲突仲裁、合并和 Gold/Silver 转换命令见 [`annotation/LABEL_STUDIO_WORKFLOW.md`](annotation/LABEL_STUDIO_WORKFLOW.md)。主配置是 [`annotation/label-studio-config.xml`](annotation/label-studio-config.xml)，仲裁配置是 [`annotation/label-studio-adjudication-config.xml`](annotation/label-studio-adjudication-config.xml)。Doccano 工具只保留为历史数据兼容。

建议项目设置：

1. 标签仅建 `CATEGORY/BRAND/PRODUCT_TYPE/SCENE/ATTRIBUTE_VALUE`；
2. 训练集可单人标注 + 10% 抽审，dev/test 必须双人独立标注并仲裁；
3. 多人冲突样本先仲裁为一份 ground-truth，再运行转换脚本；
4. AI/规则的预标只是提示，不自动成为金标；
5. 每周冻结一个数据版本，如 `ner-gold-2026w30-v1`。

Label Studio 多人任务拆分、冲突比较、合并和最终格式转换：

```powershell
python ml/src/data_assign_label_studio_tasks.py `
  --input target/label-studio-tasks.json `
  --output-dir ml/data/annotation-tasks/batch-001 `
  --annotators user01,user02,user03 `
  --overlap-ratio 0.20

python ml/src/data_compare_label_studio.py `
  --input user01=user01-export.json `
  --input user02=user02-export.json `
  --conflicts-output ml/data/reports/conflicts.jsonl `
  --adjudication-output ml/data/annotation-tasks/adjudication.json `
  --summary-output ml/data/reports/summary.json

python ml/src/data_merge_label_studio_exports.py `
  --input user01-export.json `
  --input user02-export.json `
  --adjudication adjudication-final.json `
  --approved-by product-lead `
  --output merged-final.json

python ml/src/data_convert_label_studio.py `
  --input merged-final.json `
  --output ml/data/gold/ner-gold-v1.jsonl `
  --dataset-version ner-gold-v1
python ml/src/data_validate.py ml/data/gold/ner-gold-v1.jsonl
```

已有 Doccano 导出仍可使用 `data_convert_doccano.py` 转换，但新批次不要混用两种平台。

## 数据等级

| 等级 | 产生方式 | 可进入 train | 可进入 dev/test |
|---|---|---:|---:|
| Gold | 两位标注员结果完全一致，或冲突经产品负责人仲裁 | 是 | 是 |
| Silver | 规则/AI 预标后，一位人工完整复核 | 是，建议降权 | 否 |
| Bronze | AI 自动生成或自动标注，只有程序校验 | 仅实验性使用 | 否 |

不要把 AI 输出直接命名为 gold。模型评分只允许在从未用于提示调优、阈值选择或训练的 gold test 上报告。

更完整的流程、角色和抽检规则见 [`annotation/GOLD_QUERY_WORKFLOW.md`](annotation/GOLD_QUERY_WORKFLOW.md)。

## 模型制品入口

训练团队最终交付的目录契约见 [`artifacts/README.md`](artifacts/README.md)。当前 Java 默认 `aimall.ner.mode=rule`、`model-provider=stub`；可用 `fixture + shadow` 验证模型链路，但 fixture 不是准确率基线。
