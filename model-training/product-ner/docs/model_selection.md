# 预训练模型选型（2026-07 查证）

## 候选对比

| 模型 ID | 参数量 | 许可证 | 中文能力 | 最大长度 | 显存(微调,bs32) | CPU 单条延迟* | 适合短查询 |
|---|---|---|---|---|---|---|---|
| **hfl/chinese-macbert-base** | ~102M | Apache-2.0 | 强，MLM-as-correction 预训练 | 512 | ~4-6GB | ~10-18ms | ✅ **首选** |
| hfl/chinese-roberta-wwm-ext | ~102M | Apache-2.0 | 强，全词掩码 | 512 | ~4-6GB | ~10-18ms | ✅ 备选主模型 |
| hfl/rbt3 | ~38M | Apache-2.0 | 中（3 层蒸馏） | 512 | ~1.5GB | ~3-5ms | ✅ **轻量兜底** |
| hfl/rbt6 | ~60M | Apache-2.0 | 中上（6 层） | 512 | ~2.5GB | ~5-9ms | ✅ 折中 |
| google-bert/bert-base-chinese | ~102M | Apache-2.0 | 中（无 wwm） | 512 | ~4-6GB | ~10-18ms | ⚠️ 已被上面几个全面超越 |
| nghuyong/ernie-3.0-base-zh | ~118M | 见模型卡 | 强 | 512 | ~5-7GB | ~12-20ms | ⚠️ 社区转换自 PaddleNLP，长期维护有风险 |
| IDEA-CCNL/Erlangshen-DeBERTa-v2-97M | 97M | Apache-2.0 | 强 | 512 | ~5GB | ~15-25ms | ❌ **排除** |
| Alibaba-NLP/gte-multilingual-mlm-base | 306M | Apache-2.0 | 强（75 语言） | 8192 | ~12GB+ | ~40-60ms | ❌ 过重 |
| answerdotai/ModernBERT-base | 149M | Apache-2.0 | ❌ 英文+代码 | 8192 | — | — | ❌ 不支持中文 |

\* 序列长度 32、单线程、消费级 CPU 的量级估计；以 `scripts/evaluate.py` 在你机器上的实测为准。

## 关键排除理由（不是偏好，是硬约束）

**Erlangshen-DeBERTa-v2 系列被排除**：官方模型卡的调用示例强制 `use_fast=False`。没有 fast tokenizer 就拿不到 `offset_mapping`，而本项目的整个契约建立在**字符级 offset** 上（Java 要用它做高亮和过滤）。手工还原 DeBERTa SentencePiece 的字符对齐既脆弱又难测。`src/nerkit/alignment.py` 直接拒绝 slow tokenizer，`train.py` 启动时就会报错。

**ModernBERT 被排除**：预训练语料是 2 万亿 token 的英文与代码，中文只有个人用 CCI3-HQ 训练 1 epoch 的社区复现版，没有生产背书。

**gte-multilingual 被排除**：306M 参数、8192 上下文，对平均 14 字符（本仓库样例实测 p50=14、p99=31）的商品查询是纯浪费；且需要 `trust_remote_code`，给线上服务引入额外供应链风险。

**不选 large**：MacBERT-large 约 324M 参数、检查点 1.3GB，CPU 延迟增至 3 倍以上，而在扁平短实体任务上相对 base 的收益通常 <1 个 F1 点。数据量（首批几千条标注）也撑不起 large。

## 最终决定
- **主模型**：`hfl/chinese-macbert-base`
- **轻量备用**：`hfl/rbt3`（CPU 吃紧或需要提高 QPS 时，配置里换一行即可，`configs/train_base.yaml` 的 `model.encoder`）
- **升级路径**：`hfl/rbt6` → `chinese-roberta-wwm-ext` → 蒸馏（用 MacBERT 当 teacher 蒸到 rbt3）

## 阿里系模型的正确用法（重要）

阿里达摩院的 **RaNER 电商中文 NER**（`damo/nlp_raner_named-entity-recognition_chinese-base-ecom` /
`-ecom-50cls`，AdaSeq 工具箱 Apache-2.0）是一个**开箱即用的电商领域 NER**，输出直接带
`start/end/span`，类型包括 `品牌`、`产品_核心产品`、`材质_面料`、`款式` 等 —— 和本项目的标签体系高度重合。

**但它不适合直接上线**：
1. 依赖 `modelscope` + `adaseq`，会把 torch/transformers 拉回旧版本，和服务栈冲突；
2. 标签体系固定（细粒度版 50 类），和你的 ES 字段对不齐，必须映射且有损；
3. RaNER 方法本身依赖外部检索增强上下文，线上单条查询场景发挥不出优势；
4. 单个模型卡的授权条款需要逐个确认（工具箱是 Apache-2.0 不等于权重也是）。

**正确用法：当预标注老师**。`scripts/pre_annotate_raner.py` 已实现完整流程 ——
在独立 venv 里跑 RaNER → 映射到 v1 标签 → 生成 Label Studio 预标注任务。
这能显著降低首批人工标注成本，尤其是词典覆盖不到的品牌。产出仍然是 **silver**，
必须经人工修正才能进 gold。

阿里的 StructBERT（`damo/nlp_structbert_*`）主要以 ModelScope pipeline 形式分发，
同样有依赖栈问题，且没有证据表明它在本任务上优于 MacBERT，因此不作为骨干候选。
