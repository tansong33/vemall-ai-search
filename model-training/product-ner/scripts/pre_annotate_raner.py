#!/usr/bin/env python3
"""Optional pre-annotator using Alibaba DAMO's RaNER e-commerce Chinese NER.

Why this exists: `damo/nlp_raner_named-entity-recognition_chinese-base-ecom(-50cls)` is a
ready-made Chinese *e-commerce* NER model that already emits 品牌 / 产品_核心产品 /
材质_面料 / 款式 with character start/end offsets. Used as a teacher it produces far better
pre-annotations than our dictionary + regex, which cuts human labelling time
substantially — especially for BRAND on titles whose brand is not in the 95k dictionary.

IMPORTANT — it is a labelling-time tool only, NOT the deployed model:
  * it needs `modelscope` (+ `adaseq`), which pins an older torch/transformers stack and
    must live in a SEPARATE virtualenv from the serving stack;
  * its label inventory is fixed and does not match the ES filter fields, so the mapping
    below is lossy and must be reviewed by a human in Label Studio;
  * verify the individual model card's licence before commercial use — the AdaSeq
    toolkit is Apache-2.0, but each ModelScope model card carries its own terms.

    # separate env
    pip install "modelscope>=1.9,<2" adaseq
    python scripts/pre_annotate_raner.py --input data/raw/batch1.jsonl \
        --out-label-studio data/label_studio/import_batch1_raner.json
"""
from __future__ import annotations

import argparse
import sys
from collections import Counter
from pathlib import Path
from typing import Dict, List

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from nerkit.io_utils import iter_jsonl, write_json, write_jsonl
from nerkit.labels import Span, resolve_overlaps

# RaNER ecom type -> our v1 schema. Anything unmapped is dropped rather than guessed.
DEFAULT_TYPE_MAP: Dict[str, str] = {
    "品牌": "BRAND",
    "产品": "CATEGORY",
    "产品_核心产品": "CATEGORY",
    "产品_修饰产品": "CATEGORY",
    "型号": "MODEL",
    "系列": "MODEL",
    "规格": "SPEC",
    "尺寸": "SPEC",
    "容量": "SPEC",
    "数量": "SPEC",
    "颜色": "COLOR",
    "款式_颜色": "COLOR",
    "材质": "MATERIAL",
    "材质_面料": "MATERIAL",
    "人群": "AUDIENCE",
    "适用人群": "AUDIENCE",
}

DEFAULT_MODEL_ID = "damo/nlp_raner_named-entity-recognition_chinese-base-ecom-50cls"


def build_pipeline(model_id: str):
    try:
        from modelscope.pipelines import pipeline
        from modelscope.utils.constant import Tasks
    except ImportError as exc:  # pragma: no cover - optional dependency
        raise SystemExit(
            "modelscope is not installed. This script is optional and must run in its own "
            "virtualenv:\n    pip install \"modelscope>=1.9,<2\" adaseq\n"
            f"(original error: {exc})"
        )
    return pipeline(Tasks.named_entity_recognition, model_id)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True, help="JSONL with a 'text' field")
    ap.add_argument("--model-id", default=DEFAULT_MODEL_ID)
    ap.add_argument("--out-jsonl", default="")
    ap.add_argument("--out-label-studio", default="")
    ap.add_argument("--labels", default="BRAND,CATEGORY,MODEL,SPEC,COLOR")
    ap.add_argument("--min-score", type=float, default=0.0)
    ap.add_argument("--batch-size", type=int, default=16)
    args = ap.parse_args()

    keep = {x.strip().upper() for x in args.labels.split(",") if x.strip()}
    ner = build_pipeline(args.model_id)
    stats: Counter = Counter()
    rows, tasks = [], []

    for row in iter_jsonl(args.input):
        text = row["text"]
        try:
            out = ner(text)
        except Exception as exc:  # pragma: no cover - remote model failure
            stats["failed"] += 1
            print(f"[warn] {text!r}: {exc}")
            continue
        spans: List[Span] = []
        for item in out.get("output", []):
            raw_type = str(item.get("type", ""))
            mapped = DEFAULT_TYPE_MAP.get(raw_type) or DEFAULT_TYPE_MAP.get(raw_type.split("_")[0])
            stats[f"raner::{raw_type}"] += 1
            if not mapped or mapped not in keep:
                stats["unmapped"] += 1
                continue
            start, end = int(item["start"]), int(item["end"])
            if not (0 <= start < end <= len(text)):
                stats["bad_offset"] += 1
                continue
            spans.append(Span(start, end, mapped, text[start:end],
                              float(item.get("prob", 0.9)), "raner"))
        spans = resolve_overlaps(spans)
        stats["examples"] += 1
        stats["entities"] += len(spans)
        rows.append({"id": row.get("id", ""), "text": text,
                     "entities": [s.to_dict() for s in spans],
                     "meta": {**row.get("meta", {}), "annotation_source": "weak",
                              "weak_version": f"raner:{args.model_id}"}})
        tasks.append({
            "data": {"text": text, "meta_id": row.get("id", ""),
                     "brand_field": row.get("meta", {}).get("brand_field", ""),
                     "category_field": row.get("meta", {}).get("category_field", "")},
            "predictions": [{
                "model_version": f"raner-{Path(args.model_id).name}",
                "score": 0.9,
                "result": [
                    {"id": f"raner_{i}", "from_name": "label", "to_name": "text",
                     "type": "labels",
                     "value": {"start": s.start, "end": s.end, "text": s.text,
                               "labels": [s.label]}}
                    for i, s in enumerate(spans)
                ],
            }],
        })

    if args.out_jsonl:
        write_jsonl(args.out_jsonl, rows)
    if args.out_label_studio:
        write_json(args.out_label_studio, tasks)
    print(f"[ok] {stats['examples']} examples, {stats['entities']} entities, "
          f"{stats['unmapped']} unmapped RaNER types dropped")
    for k, v in sorted(stats.items()):
        if k.startswith("raner::"):
            print(f"     {k[7:]:<16} {v}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
