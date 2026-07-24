.PHONY: install model test smoke train evaluate onnx bundle serve clean all
PY ?= python3
CONFIG ?= configs/train_base.yaml
RUN ?= artifacts/ner-v1

install:
	$(PY) -m pip install -r requirements.txt

model:                      ## 下载预训练模型到 models/pretrained/
	$(PY) scripts/download_pretrained.py

test:
	$(PY) -m pytest tests -q

## 离线全链路自检：微型模型 -> 训练 -> 评估 -> ONNX -> Java bundle（不下载任何东西）
smoke:
	$(PY) scripts/make_sample_data.py --out-dir data
	$(PY) scripts/make_smoke_assets.py --data data/samples/gold_sample.jsonl --out artifacts/tiny-encoder
	$(PY) scripts/split_dataset.py --input data/samples/gold_sample.jsonl --outdir data/processed/smoke
	$(PY) scripts/train.py --config configs/train_smoke.yaml
	$(PY) scripts/evaluate.py --model artifacts/ner-smoke/best --data data/processed/smoke/test.jsonl \
		--dict data/dict/brand.tsv --dict data/dict/category.tsv --mode hybrid
	$(PY) scripts/export_onnx.py --model artifacts/ner-smoke/best --out artifacts/ner-smoke/onnx --quantize
	$(PY) scripts/verify_onnx.py --model artifacts/ner-smoke/best --bundle artifacts/ner-smoke/onnx \
		--data data/processed/smoke/test.jsonl
	$(PY) scripts/make_java_bundle.py --bundle artifacts/ner-smoke/onnx \
		--data data/processed/smoke/test.jsonl --zip dist/ner-java-bundle-smoke.zip

train:
	$(PY) scripts/train.py --config $(CONFIG)

evaluate:
	$(PY) scripts/evaluate.py --model $(RUN)/best --data data/processed/v1/test.jsonl \
		--dict data/dict/brand.tsv --dict data/dict/category.tsv --mode model \
		--report reports/eval_model.json --errors-out reports/errors_model.json

## 训练完执行这一条，产出可直接给 Java 的 zip
onnx:
	$(PY) scripts/export_onnx.py --model $(RUN)/best --out $(RUN)/onnx --quantize
	$(PY) scripts/verify_onnx.py --model $(RUN)/best --bundle $(RUN)/onnx \
		--data data/processed/v1/test.jsonl
	$(PY) scripts/make_java_bundle.py --bundle $(RUN)/onnx --data data/processed/v1/test.jsonl \
		--zip dist/ner-java-bundle.zip

serve:
	NER_MODEL_DIR=$(RUN)/best NER_DICT_FILES=data/dict/brand.tsv,data/dict/category.tsv \
		$(PY) -m uvicorn service.app:app --host 0.0.0.0 --port 8000

all: train evaluate onnx

clean:
	rm -rf artifacts/ner-smoke artifacts/tiny-encoder data/processed/smoke dist reports/*.json
