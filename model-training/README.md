# NER 模型训练

本目录是与 Java 在线服务解耦的离线训练工作区，负责产出经验证的 ONNX 模型制品。

## 环境

建议使用 Python 3.11，并把虚拟环境、数据、模型权重和训练产物放在仓库外。

```bash
python -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip
python -m pip install -r requirements.txt
```

Windows PowerShell 使用 `.venv\Scripts\Activate.ps1` 激活环境。

## 唯一主干

所有数据处理、标注协作、训练、评测和导出入口都在
[`product-ner/`](product-ner/)；完整流程与 RaNER 使用说明见
[`product-ner/README.md`](product-ner/README.md)。

## 常用命令

以下命令在 `model-training/product-ner/` 下执行：

```bash
python scripts/convert_label_studio.py \
  --input data/label_studio/export_batch1.json \
  --out data/gold/v1/batch1.jsonl

python scripts/train.py --config configs/train_base.yaml

python scripts/evaluate.py \
  --model artifacts/ner-v1/best --data data/processed/v1/test.jsonl \
  --mode model --report reports/eval_model.json

python scripts/export_onnx.py \
  --model artifacts/ner-v1/best --out artifacts/ner-v1/onnx --quantize
```

## 数据等级

| 等级 | 产生方式 | 可进入 train | 可进入 dev/test |
|---|---|---:|---:|
| Gold | 两位标注员完全一致，或冲突经负责人仲裁 | 是 | 是 |
| Silver | 规则或 AI 预标后，由一位人工完整复核 | 是，建议降权 | 否 |
| Bronze | AI 自动生成或自动标注，仅通过程序校验 | 仅实验使用 | 否 |

真实数据不得提交 Git。外部数据必须先通过
`product-ner/data/licenses/` 中的许可门禁，Gold 测试集必须独立冻结。

## 制品交付

训练团队交付给 Java 服务的文件、标签映射和校验要求见
[`artifacts/README.md`](artifacts/README.md)。
