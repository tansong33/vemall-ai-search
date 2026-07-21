# NER 模型制品契约

每个不可变版本目录应包含：

```text
ner-minirbt-2026w30-v1/
├── model.fp32.onnx
├── model.int8.onnx              # 可选
├── vocab.txt
├── labels.json
├── thresholds.json
├── model-metadata.json
├── tokenizer-golden.jsonl
├── metrics.json
└── SHA256SUMS
```

`model-metadata.json` 至少记录：模型版本、base model 与 commit、数据版本、标签顺序、max length、normalization、ONNX opset、输入/输出节点名、训练参数、Git commit 和创建时间。

未经公司 NER 数据微调的 MiniRBT 不应放到这个目录冒充 NER 制品：它缺少业务标签对应的训练后分类头。普通 Git 不提交二进制模型，使用公司制品库或 Git LFS，并在 Java 服务启动前校验 SHA-256。
