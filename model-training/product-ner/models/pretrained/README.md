# 预训练模型放这里

配置文件里的 `model.encoder` 已经指向本目录，训练时不会再联网。

```bash
python scripts/download_pretrained.py            # 下载推荐模型 hfl/chinese-macbert-base
python scripts/download_pretrained.py --all      # 主模型 + 轻量兜底 + large
HF_ENDPOINT=https://hf-mirror.com python scripts/download_pretrained.py   # 国内走镜像
```

下载后目录长这样：

```
models/pretrained/
  chinese-macbert-base/     <- configs/train_base.yaml 用这个
    config.json
    model.safetensors
    tokenizer.json          <- 必须有；没有它就没有 offset，整条链路作废
    vocab.txt
  rbt6/                     <- 延迟兜底第一档，与 base 同词表
  rbt3/                     <- configs/train_fast.yaml 用这个，延迟吃紧时的最后一档
```

手动下载（无网络的训练机）：在有网机器上跑上面的命令，然后整个目录拷过去即可。
