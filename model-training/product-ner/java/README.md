# Java 侧接入（ONNX 进程内推理）

模型训练完导出成 ONNX 之后，**Java 直接加载推理，不需要跑 Python 服务**。

## 1. 依赖
把 `pom-snippet.xml` 的内容合并进你的 `pom.xml`。两个关键依赖：
- `com.microsoft.onnxruntime:onnxruntime` —— 推理引擎
- `ai.djl.huggingface:tokenizers` —— 直接读 `tokenizer.json`，**并且能给出字符 offset**

> ⚠️ 不要自己用 Java 重写 WordPiece 分词。中文 + 英文型号 + 数字混排的 offset 对齐
> 是这个项目最容易出错的地方，Python 侧我已经踩过一次坑（正则 `\b` 把 CJK 当词字符）。
> DJL 的绑定就是 HuggingFace 的 Rust 实现，行为天然一致。

## 2. 部署产物
把 `export_onnx.py` + `make_java_bundle.py` 产出的整个 bundle 目录拷到服务器：

```
E:\ai-search-data\ner-models\ner-v1\
  model.onnx              fp32
  model.int8.onnx         INT8 量化版，体积约 1/4，本项目测试集上 F1 无损
  tokenizer.json          DJL 读这个
  labels.json             id -> BIO 标签
  ner_manifest.json       模型版本 / max_length / tau_accept / 归一化规则 / 一致性报告
  fixture_*.json          跨语言一致性测试用
```

**所有解码参数从 `ner_manifest.json` 读，不要在 Java 里写死。** 换模型只换目录。

## 3. 用法

```java
@Bean(destroyMethod = "close")
public OnnxNerService onnxNerService(@Value("${ner.onnx.bundle-dir}") String dir) throws Exception {
    // CPU 部署建议 model.int8.onnx；GPU 机器用 model.onnx + onnxruntime_gpu
    return new OnnxNerService(Path.of(dir), "model.int8.onnx", 1);
}

@Bean
public HybridNerFacade nerFacade(OnnxNerService model, DictionaryNerService dict) {
    return new HybridNerFacade(model, dict, HybridNerFacade.Mode.DICTIONARY, 0.45);
}
```

```java
List<NerEntity> entities = nerFacade.recognize("华为手机 256G");
// [NerEntity[text=华为, label=BRAND, start=0, end=2, confidence=0.99],
//  NerEntity[text=手机, label=CATEGORY, start=2, end=4, confidence=0.98],
//  NerEntity[text=256G, label=SPEC, start=5, end=9, confidence=0.83]]
```

`start/end` 直接对应**传入的原始 query**，可以拿去做高亮或 ES 过滤。

## 4. 必须跑的一致性测试

```bash
mvn test -Dner.bundle=/path/to/ner-v1
```

`ParityTest` 会拿 Python 生成的 fixture 断言 Java 输出**完全一致**：归一化逐字符一致、
实体的 start/end/label/text/confidence 全部一致。这个测试挂了就不要上线 —— 差一个字符
的归一化差异会让所有 offset 错位，而且线上极难定位。

## 5. 上线节奏
1. `Mode.DICTIONARY` + 影子日志跑 3 天，比对模型与词典的差异；
2. `Mode.HYBRID`，按 query 哈希灰度 5% → 20% → 50%；
3. 全量。词典**永远保留**做兜底，不要删。

`setMode()` 支持热切，出问题 10 秒回滚。

## 6. 性能参考
本仓库 smoke 模型（CPU，单线程）实测：ONNX 单条 p50 0.28ms，比 PyTorch 快约 5 倍。
真实 MacBERT-base 量级预计 CPU 单条 8-20ms，INT8 后 3-8ms —— 都在搜索链路预算内。
以你自己机器上 `verify_onnx.py` 的输出为准。

## 附：`optional-http-client/`
如果你更想让模型跑在独立服务里（比如要用 GPU、或者不想给搜索服务加 200MB 原生库），
那里是基于 HTTP 的版本，带超时、重试、熔断和降级。两条路选一条即可，**推荐 ONNX 进程内**。
