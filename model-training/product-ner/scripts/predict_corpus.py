#!/usr/bin/env python3
"""批量预测：语料 -> canonical JSONL，供人工复审和主动学习挑样本。

这是「训完的模型 -> 回到 Label Studio 人工修订 -> 重新投入训练」这条回流链路的第一环。
predict.py 打印的是 HTTP API 的形状（query / took_ms / schema_version），那是给接口
对齐用的，不能直接喂给 export_label_studio.py；本脚本产出的是 canonical 形状。

    # 1) 用训好的模型跑一批未标注语料
    python scripts/predict_corpus.py --model artifacts/ner-v1/best \\
        --dict data/dict/brand.tsv --dict data/dict/category.tsv \\
        --input data/raw/batch2.jsonl --out data/pred/v1/batch2.jsonl \\
        --uncertain-out data/pred/v1/batch2_uncertain.jsonl

    # 2) 只把「模型没把握」的送人工，这才是主动学习
    python scripts/export_label_studio.py \\
        --input data/pred/v1/batch2_uncertain.jsonl \\
        --out data/label_studio/import_batch2_review.json

产出的 annotation_source 一律是 ``prediction``：模型自己的输出没有经过人工确认，
既不是 gold 也不该混进 silver 的统计口径，更不允许进冻结 test。

挑样本的三个信号（`--uncertain-out` 命中任一即选中）：

* 有实体但最低置信度低于 ``--tau-uncertain`` —— 模型在边界或标签上犹豫；
* 一个实体都没抽到 —— 未登录词的高发区，正是模型相对词典的价值所在；
* ``--require-labels`` 指定的标签一个都没命中 —— 例如强制每条都要有 CATEGORY。
"""
from __future__ import annotations

import argparse
import sys
from collections import Counter
from pathlib import Path
from typing import Any, Dict, Iterator, List, Sequence

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

try:
    from _common import stream_json_records  # type: ignore[import-not-found]
except ModuleNotFoundError:  # pragma: no cover - package-style test import
    from scripts._common import stream_json_records

from nerkit.dictionary import DictionaryNer  # noqa: E402
from nerkit.fusion import FusionPolicy  # noqa: E402
from nerkit.io_utils import build_manifest, write_json  # noqa: E402
from nerkit.predictor import NerPredictor  # noqa: E402


def iter_batches(
    records: Iterator[Dict[str, Any]], size: int
) -> Iterator[List[Dict[str, Any]]]:
    batch: List[Dict[str, Any]] = []
    for record in records:
        batch.append(record)
        if len(batch) >= size:
            yield batch
            batch = []
    if batch:
        yield batch


def is_uncertain(
    entities: Sequence[Dict[str, Any]],
    tau: float,
    require_labels: Sequence[str],
) -> str:
    """返回选中原因，空串表示模型有把握、不必送人工。"""
    if not entities:
        return "no_entity"
    if require_labels:
        found = {str(e["label"]) for e in entities}
        missing = [label for label in require_labels if label not in found]
        if missing:
            return f"missing:{','.join(missing)}"
    lowest = min(float(e.get("confidence", 1.0)) for e in entities)
    if lowest < tau:
        return "low_confidence"
    return ""


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--input", required=True, help="JSONL / JSON 数组 / ES dump")
    ap.add_argument("--out", required=True, help="canonical JSONL（全部预测）")
    ap.add_argument("--uncertain-out", default="",
                    help="canonical JSONL（只含需要人工复审的，主动学习用）")
    ap.add_argument("--model", default="", help="checkpoint 目录（…/best）")
    ap.add_argument("--dict", action="append", default=[], help="TSV 词典，可重复")
    ap.add_argument("--mode", default="hybrid", choices=["model", "dictionary", "hybrid"])
    ap.add_argument("--tau-accept", type=float, default=0.60)
    ap.add_argument("--tau-fallback", type=float, default=0.45)
    ap.add_argument("--tau-uncertain", type=float, default=0.75,
                    help="最低实体置信度低于此值就送人工复审")
    ap.add_argument("--require-labels", default="",
                    help="逗号分隔；缺少其中任一标签的样本一律送复审，如 CATEGORY")
    ap.add_argument("--batch-size", type=int, default=64)
    ap.add_argument("--limit", type=int, default=0, help="只跑前 N 条，0 表示全量")
    ap.add_argument("--device", default="cpu")
    ap.add_argument("--threads", type=int, default=1)
    ap.add_argument("--report", default="")
    args = ap.parse_args()

    require_labels = [x.strip().upper() for x in args.require_labels.split(",") if x.strip()]

    dictionary = DictionaryNer.from_files(args.dict) if args.dict else None
    policy = FusionPolicy(
        mode=args.mode, tau_accept=args.tau_accept, tau_fallback=args.tau_fallback
    )
    if args.model:
        predictor = NerPredictor.from_pretrained(
            args.model, dictionary=dictionary, policy=policy,
            device=args.device, torch_threads=args.threads,
        )
        model_version = Path(args.model).name
    elif dictionary is not None:
        predictor = NerPredictor.dictionary_only(dictionary)
        model_version = "dictionary-only"
    else:
        ap.error("至少要给 --model 或 --dict")

    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    uncertain_path = Path(args.uncertain_out) if args.uncertain_out else None
    if uncertain_path:
        uncertain_path.parent.mkdir(parents=True, exist_ok=True)

    stats: Counter = Counter()
    records = stream_json_records(args.input, limit=args.limit or None)

    out_handle = out_path.open("w", encoding="utf-8", newline="\n")
    uncertain_handle = (
        uncertain_path.open("w", encoding="utf-8", newline="\n") if uncertain_path else None
    )
    try:
        import json

        for batch in iter_batches(records, args.batch_size):
            texts = [str(row.get("text") or row.get("title") or "") for row in batch]
            keep = [i for i, text in enumerate(texts) if text.strip()]
            stats["skipped_empty_text"] += len(batch) - len(keep)
            if not keep:
                continue
            results = predictor.predict([texts[i] for i in keep])

            for offset, index in enumerate(keep):
                row, text = batch[index], texts[index]
                entities = results[offset]["entities"]
                reason = is_uncertain(entities, args.tau_uncertain, require_labels)

                meta = dict(row.get("meta") or {})
                meta.update({
                    "annotation_source": "prediction",
                    "model_version": model_version,
                    "predict_mode": args.mode,
                })
                if reason:
                    meta["review_reason"] = reason
                # split_group 决定切分分组，缺了会按行切进而泄漏；SPU 优先
                if "split_group" not in meta:
                    meta["split_group"] = str(
                        row.get("spu_id") or meta.get("spu_id") or row.get("id") or ""
                    )

                out_row = {
                    "id": str(row.get("id") or row.get("sku_id") or ""),
                    "text": text,
                    "entities": entities,
                    "meta": meta,
                }
                line = json.dumps(out_row, ensure_ascii=False) + "\n"
                out_handle.write(line)
                stats["predicted"] += 1
                stats["entities"] += len(entities)
                for entity in entities:
                    stats[f"label::{entity['label']}"] += 1
                if reason:
                    stats["uncertain"] += 1
                    stats[f"reason::{reason.split(':')[0]}"] += 1
                    if uncertain_handle:
                        uncertain_handle.write(line)
    finally:
        out_handle.close()
        if uncertain_handle:
            uncertain_handle.close()

    predicted = stats["predicted"]
    report = {
        **build_manifest({"script": "predict_corpus"}),
        "model_version": model_version,
        "mode": args.mode,
        "tau_uncertain": args.tau_uncertain,
        "counts": dict(stats),
        "entities_per_example": round(stats["entities"] / max(predicted, 1), 3),
        "uncertain_rate": round(stats["uncertain"] / max(predicted, 1), 4),
    }
    if args.report:
        write_json(args.report, report)

    print(f"[ok] predicted {predicted:,} -> {out_path}")
    print(f"     entities/example={report['entities_per_example']}, "
          f"uncertain={stats['uncertain']:,} ({report['uncertain_rate']:.1%})")
    if uncertain_path:
        print(f"     review queue -> {uncertain_path}")
    for key in sorted(stats):
        if key.startswith("reason::"):
            print(f"     {key[8:]:<16} {stats[key]:,}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
