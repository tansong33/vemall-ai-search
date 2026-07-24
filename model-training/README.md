# NER 模型训练工作区

这个目录与 Java 在线服务完全解耦。Python 只负责 Label Studio 数据准备、训练、评测和 ONNX 导出；主 Compose 不部署 Python 模型推理服务，Java 后端直接加载审核过的 ONNX 制品。

## 目录边界

- `annotation/`：Label Studio 配置、多人标注和仲裁流程。
- `data/`：统一 NER schema、脱敏样例以及本地数据说明。
- `src/data_*.py`：导入、分配、合并、校验、切分和评测工具。
- `src/train.py`：Hugging Face token-classification 训练入口。
- `src/export_onnx.py`：导出给 Java ONNX Runtime 的制品。
- `artifacts/`：模型制品契约，不提交真实大模型。
- `product-ner/`：完整商品 NER 子项目，包含抽样、弱标、训练、实体级评测、ONNX
  一致性验证、Java parity 代码和独立测试；其 `service/` 只用于开发验收，不进入主部署。

真实数据、Hugging Face 缓存和模型统一放到 `E:\ai-search-data\ner`，不要放到 C 盘。

## 环境

```powershell
cd E:\ai-search\model-training
python -m venv E:\ai-search-data\ner\.venv
E:\ai-search-data\ner\.venv\Scripts\Activate.ps1
$env:HF_HOME='E:\ai-search-data\huggingface-cache'
pip install -r requirements.txt
```

本机没有 NVIDIA GPU，完整 Base 模型训练建议在 GPU 机器执行；本机适合数据校验、少量 smoke training 和 ONNX CPU 推理验收。

## 两套入口如何分工

- 根目录 `src/data_*.py` 负责团队数据治理：多人任务分配、冲突仲裁、统一 schema
  和历史 Doccano 兼容。
- `product-ner/` 负责生产级模型实验与交付：模板去重抽样、弱监督、按组切分、
  CRF/非 CRF 对比、未登录品牌召回评测、ONNX 量化和 Python/Java 一致性验证。

需要训练正式商品 NER 模型时从 `product-ner/README.md` 开始；只做标注数据清洗或
多人合并时继续使用本目录的 `src/data_*.py`。两者都把真实数据和模型放到
`E:\ai-search-data\ner`，Git 仅保留代码、schema、配置和脱敏小样例。

## 训练与导出

```powershell
python src/train.py `
  --train E:\ai-search-data\ner\datasets\v1\train.jsonl `
  --validation E:\ai-search-data\ner\datasets\v1\validation.jsonl `
  --base-model hfl/chinese-macbert-base `
  --output-dir E:\ai-search-data\ner\models\ner-v1

python src/export_onnx.py `
  --checkpoint E:\ai-search-data\ner\models\ner-v1 `
  --output-dir E:\ai-search-data\ner\models\ner-v1-onnx `
  --model-version ner-v1
```

把导出目录挂载到 Java 容器的 `/app/models/ner`，并设置：

```text
NER_ONNX_ENABLED=true
NER_MODE=hybrid
NER_MODEL_VERSION=ner-v1
```

## 现在即可运行的闭环

所有 `src/data_*.py` 只用 Python 标准库，先不安装 PyTorch 也能执行：

```powershell
python src/data_validate.py data/examples/ner_gold.example.jsonl
python src/data_split.py data/examples/ner_gold.example.jsonl --output-dir data/splits-demo
python src/data_evaluate_ner.py `
  --gold data/examples/ner_gold.example.jsonl `
  --predictions data/examples/ner_predictions.example.jsonl
python src/data_build_annotation_tasks.py `
  --queries data/examples/raw_queries.example.jsonl `
  --dictionary data/examples/dictionaries.example.json `
  --output target/label-studio-tasks.json `
  --format label-studio
```

真实数据不要提交 Git。建议放到公司受控对象存储，Git 里只保留 schema、脱敏样例、脚本和不可逆的版本摘要。

收到第 6 项 query 日志 CSV 后，先转换成统一 JSONL。若输入含内部 query/session ID，必须在当前终端设置只用于本批次的哈希盐（不要写入文件或 Git）：

```powershell
$env:QUERY_HASH_SALT='<从公司密钥系统临时注入>'
python src/data_import_query_log.py `
  --input integration-data/queries/query-log.csv `
  --output data/raw/query-log.jsonl
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
python src/data_assign_label_studio_tasks.py `
  --input target/label-studio-tasks.json `
  --output-dir data/annotation-tasks/batch-001 `
  --annotators user01,user02,user03 `
  --overlap-ratio 0.20

python src/data_compare_label_studio.py `
  --input user01=user01-export.json `
  --input user02=user02-export.json `
  --conflicts-output data/reports/conflicts.jsonl `
  --adjudication-output data/annotation-tasks/adjudication.json `
  --summary-output data/reports/summary.json

python src/data_merge_label_studio_exports.py `
  --input user01-export.json `
  --input user02-export.json `
  --adjudication adjudication-final.json `
  --approved-by product-lead `
  --output merged-final.json

python src/data_convert_label_studio.py `
  --input merged-final.json `
  --output data/gold/ner-gold-v1.jsonl `
  --dataset-version ner-gold-v1
python src/data_validate.py data/gold/ner-gold-v1.jsonl
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

训练团队最终交付的目录契约见 [`artifacts/README.md`](artifacts/README.md)。当前 Java 默认关闭 ONNX 并使用词典识别；制品验收完成后设置 `NER_ONNX_ENABLED=true`、`NER_MODE=hybrid` 即可启用 Java ONNX 推理和词典兜底。
