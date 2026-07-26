# 交付给 Java 后端

线上推理跑在 **Spring Boot 进程内**，没有 Python 推理服务。
训练侧的交付物就是 `make_java_bundle.py` 产出的 bundle 目录。

> 本文替代了原先的 `product-ner/java/`。那份 Java 实现是独立于 backend 的第二套代码
> （`com.aisearch.ner`，Java 16 `record` + `jakarta` + DJL tokenizer），**编译不进
> backend**（Java 8 + `javax` + 自带 WordPiece），已删除。真正的实现在
> `backend/src/main/java/com/tsong/aisearch/service/ner/`。

---

## 1. 线上实际使用的类

| 类 | 职责 |
|---|---|
| `OnnxNerModelClient` | 加载 ONNX session、跑推理、softmax + BIO/BIOES 解码 |
| `BertWordPieceTokenizer` | 自带的 WordPiece 实现，读 `vocab.txt`，输出字符级 offset |
| `DictionaryNerRecognizer` | Aho-Corasick 词典识别，模型不可用时兜底 |
| `HybridNerRecognizer` | 按 `NER_MODE` 融合模型与词典结果 |

## 2. 部署产物与配置映射

把 bundle 目录整个拷到服务器（例如挂载到容器 `/app/models/ner`）：

```
model.onnx                 fp32 图；输入 input_ids / attention_mask (int64 [B,T])
                           输出 logits [B,T,C] —— backend 只读 logits，自己做 softmax
model.int8.onnx            INT8 量化版，CPU 部署建议用这个
vocab.txt                  backend 的 BertWordPieceTokenizer 读这个
labels.json                含 id2label；backend 的 loadLabels 认 id2label / 数组 / 数字键对象
ner_manifest.json          模型版本、max_length、decode.tau_accept、归一化规则、一致性报告
fixture_normalization.json 归一化 fixture —— 被 NerParityTest 断言
fixture_decode.json        端到端实体 fixture —— 被 NerParityTest 断言
```

对应 `application.yml` / 环境变量：

| bundle 里的东西 | 环境变量 | 备注 |
|---|---|---|
| `model.int8.onnx` | `NER_ONNX_MODEL` | 默认 `/app/models/ner/model.onnx` |
| `vocab.txt` | `NER_ONNX_VOCAB` | |
| `labels.json` | `NER_ONNX_LABELS` | |
| `ner_manifest.json` → `max_length` | `NER_ONNX_MAX_LENGTH` | 默认 64 |
| `ner_manifest.json` → `decode.tau_accept` | `NER_ONNX_CONFIDENCE` | 默认 0.75 |
| `ner_manifest.json` → `model_version` | `NER_MODEL_VERSION` | |
| — | `NER_ONNX_ENABLED` | 制品验收通过后才置 `true` |
| — | `NER_MODE` | `dictionary` / `model` / `hybrid` |

> ⚠️ **backend 目前不读 `ner_manifest.json`**，上表后三项要**人工抄进环境变量**。
> 这正是 `verify_onnx.py` 抓到过的那类 bug 的温床（阈值两端不一致 → 26% 样本结果不同）。
> 换模型时如果只换目录没改 `NER_ONNX_CONFIDENCE`，阈值就静默沿用旧值。
> 长期方案是让 `OnnxNerModelClient` 直接读 manifest，见 `STRUCTURE_REVIEW.md` §6.3。

## 3. 必须跑的一致性测试

```bash
cd backend && mvn test -Dner.bundle=/path/to/ner-v1/onnx
```

`NerParityTest`（`backend/src/test/java/com/tsong/aisearch/service/ner/`）用 Python 生成的
fixture 断言 **线上真正跑的那条路径** 输出一致。没有 `-Dner.bundle` 时整个测试类自动跳过，
所以本地和 CI 不会因为缺 bundle 变红。

它覆盖三件事：

1. **归一化逐字符一致，且不改变长度** —— 长度一变，所有已存 offset 作废；
2. **端到端实体的 start/end/label/text 与 Python 一致** —— 这是真正的验收项；
3. **offset 索引的是原始 query** —— `query.substring(start, end)` 必须等于实体文本。

### 已知的两处两端差异（测试会直接暴露）

| 位置 | Python (`nerkit.text_norm`) | backend | 后果 |
|---|---|---|---|
| 全角字符 | `Ｍａｔｅ６０` → `mate60` | 不处理，原样进 WordPiece | 全角输入命中 `[UNK]`，实体丢失 |
| 零宽字符 | `​` → 空格 | 不处理 | 同上 |
| 小写规则 | 只小写 `A-Z`（刻意限定） | `toLowerCase(Locale.ROOT)` 整段 | U+0130 之类码点会小写成 2 个字符，**offset 错位** |

backend 侧**完全没有归一化步骤**：`OnnxNerModelClient.predict` 直接把原始 query 交给
`BertWordPieceTokenizer.encode`。如果训练数据经过 `normalize_text` 而线上不做，
两端就不是同一个函数。`NerParityTest.rawAndNormalisedFormsTokeniseIdentically` 就是用来卡住这一点的
—— 它现在**预期会失败**，直到 backend 补上等长归一化。这是有意为之：一个红着的测试
比一个不存在的测试安全得多。

## 4. 上线节奏

1. `NER_MODE=dictionary` + 影子日志跑 3 天，比对模型与词典的差异；
2. `NER_MODE=hybrid`，按 query 哈希灰度 5% → 20% → 50%；
3. 全量。**词典永远保留做兜底，不要删** —— 96,244 条线上词典是模型异常时的安全网。

任一验收指标不达标（见 `README.md` §4）就留在影子模式继续补数据，不要动模式开关。

## 5. 性能参考

ONNX + INT8 在普通 CPU 上单条几毫秒，MacBERT-base 量级预计 8-20ms（INT8 后 3-8ms），
都在搜索链路预算（p99 ≤ 30ms）内。以你自己机器上 `verify_onnx.py` 的输出为准。
