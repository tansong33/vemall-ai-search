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

## 标注工具

第一阶段推荐自建 **Label Studio**：它能做文本 span/NER、导入预标结果，也便于把规则或模型接成 ML backend。配置文件是 [`annotation/label-studio-config.xml`](annotation/label-studio-config.xml)。如果团队只想快速做纯文本序列标注，doccano 也够用，生成任务时传 `--format doccano`。

建议项目设置：

1. 标签仅建 `CATEGORY/BRAND/PRODUCT_TYPE/SCENE/ATTRIBUTE_VALUE`；
2. 训练集可单人标注 + 10% 抽审，dev/test 必须双人独立标注并仲裁；
3. 多人冲突样本先仲裁为一份 ground-truth，再运行转换脚本；
4. AI/规则的预标只是提示，不自动成为金标；
5. 每周冻结一个数据版本，如 `ner-gold-2026w30-v1`。

将 Label Studio JSON 导出转换为项目统一 JSONL：

```powershell
python ml/src/data_convert_label_studio.py `
  --input export.json `
  --output ner_gold.jsonl `
  --require-ground-truth
python ml/src/data_validate.py ner_gold.jsonl
```

## 数据等级

| 等级 | 产生方式 | 可进入 train | 可进入 dev/test |
|---|---|---:|---:|
| Gold | 两位标注员独立标注，产品负责人仲裁 | 是 | 是 |
| Silver | 规则/AI 预标后，一位人工完整复核 | 是，建议降权 | 否 |
| Bronze | AI 自动生成或自动标注，只有程序校验 | 仅实验性使用 | 否 |

不要把 AI 输出直接命名为 gold。模型评分只允许在从未用于提示调优、阈值选择或训练的 gold test 上报告。

更完整的流程、角色和抽检规则见 [`annotation/GOLD_QUERY_WORKFLOW.md`](annotation/GOLD_QUERY_WORKFLOW.md)。

## 模型制品入口

训练团队最终交付的目录契约见 [`artifacts/README.md`](artifacts/README.md)。当前 Java 默认 `aimall.ner.mode=rule`、`model-provider=stub`；可用 `fixture + shadow` 验证模型链路，但 fixture 不是准确率基线。
