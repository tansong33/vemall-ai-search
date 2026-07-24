#!/usr/bin/env python3
"""Generate the sample corpus shipped with the repo (offsets correct by construction).

Everything under data/samples/ comes from here, so the repo stays runnable end-to-end
without production data. Real work replaces these files with export.json output.

    python scripts/make_sample_data.py --out-dir data
"""
from __future__ import annotations

import argparse
import json
import random
import sys
from pathlib import Path
from typing import Dict, List, Tuple

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from nerkit.io_utils import write_json, write_jsonl

BRANDS = ["公牛", "华为", "小米", "美的", "罗技", "飞利浦", "苏泊尔", "格力", "海尔", "3M",
          "雷蛇", "安踏", "九阳", "西门子", "松下"]
OOV_BRANDS = ["致仕", "翎羽", "沐光", "斐乐诺", "青云上"]  # deliberately absent from the dictionary
CATEGORIES = ["插座", "手机", "电视", "电饭煲", "鼠标", "剃须刀", "保温杯", "数据线", "排插",
              "空调", "洗衣机", "键盘", "耳机", "台灯", "电水壶"]
MODELS = ["GN-B303", "Mate60 Pro", "MX Master 3S", "K380", "X9-500", "SR-2000", "P40", "GN-403"]
SPECS = ["3米", "5孔", "256G", "12GB+512GB", "65英寸", "500ml", "3000W", "1.5米", "2400mAh", "4K"]
COLORS = ["黑色", "白色", "星光色", "深空灰", "银色", "红色", "蓝色"]
NOISE = ["官方旗舰店", "正品", "包邮", "家用", "新款", "旗舰", "2026款", "满减"]


def build(segments: List[Tuple[str, str | None]], sep: str = " ") -> Tuple[str, List[Dict]]:
    """Concatenate segments and emit exact character offsets."""
    parts, ents, cursor = [], [], 0
    for i, (txt, label) in enumerate(segments):
        if i and sep:
            parts.append(sep)
            cursor += len(sep)
        parts.append(txt)
        if label:
            ents.append({"start": cursor, "end": cursor + len(txt), "label": label, "text": txt})
        cursor += len(txt)
    return "".join(parts), ents


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out-dir", default="data")
    ap.add_argument("--n", type=int, default=180)
    ap.add_argument("--seed", type=int, default=7)
    args = ap.parse_args()
    rng = random.Random(args.seed)
    out = Path(args.out_dir)

    rows: List[Dict] = []
    for i in range(args.n):
        brand = rng.choice(BRANDS if rng.random() > 0.12 else OOV_BRANDS)
        cat = rng.choice(CATEGORIES)
        segs: List[Tuple[str, str | None]] = [(brand, "BRAND"), (cat, "CATEGORY")]
        if rng.random() < 0.45:
            segs.append((rng.choice(MODELS), "MODEL"))
        if rng.random() < 0.60:
            segs.append((rng.choice(SPECS), "SPEC"))
        if rng.random() < 0.40:
            segs.append((rng.choice(COLORS), "COLOR"))
        if rng.random() < 0.35:
            segs.append((rng.choice(NOISE), None))
        rng.shuffle(segs[2:])
        sep = " " if rng.random() < 0.7 else ""
        text, ents = build(segs, sep)
        rows.append({
            "id": f"sample-{i:04d}",
            "text": text,
            "entities": ents,
            "meta": {"annotation_source": "gold", "brand_field": brand, "category_field": cat},
        })

    # A few hand-written edge cases the generator cannot produce.
    edge: List[Tuple[str, List[Dict]]] = [
        build([("公牛", "BRAND"), ("插座", "CATEGORY")], sep=""),
        build([("华为", "BRAND"), ("手机", "CATEGORY"), ("256G", "SPEC")]),
        build([("小米", "BRAND"), ("电视", "CATEGORY"), ("65英寸", "SPEC"), ("4K", "SPEC")], sep=""),
        build([("苹果", "BRAND"), ("手机", "CATEGORY")], sep=""),          # brand/fruit ambiguity
        build([("红苹果", None), ("水果", "CATEGORY")], sep=""),           # same chars, NOT a brand
        build([("充电器", "CATEGORY")], sep=""),                            # no brand at all
        build([("包邮", None)], sep=""),                                    # no entity at all
        build([("3M", "BRAND"), ("胶带", "CATEGORY")], sep=""),            # latin-digit brand
    ]
    for j, (text, ents) in enumerate(edge):
        rows.append({"id": f"edge-{j:02d}", "text": text, "entities": ents,
                     "meta": {"annotation_source": "gold", "brand_field": "", "category_field": ""}})

    write_jsonl(out / "samples" / "gold_sample.jsonl", rows)

    # ---- export.json look-alike (Elasticsearch dump shape, JSON array with _source) ----
    export = [
        {"_index": "products", "_id": r["id"],
         "_source": {"title": r["text"], "brand": r["meta"]["brand_field"],
                     "category": r["meta"]["category_field"],
                     "price": round(rng.uniform(9.9, 4999), 2), "sales": rng.randint(0, 50000)}}
        for r in rows
    ]
    write_json(out / "samples" / "export.sample.json", export, indent=1)

    # ---- dictionaries (mirror of the Java dictionary export format) ----
    dict_dir = out / "dict"
    dict_dir.mkdir(parents=True, exist_ok=True)
    (dict_dir / "brand.tsv").write_text(
        "# term\tlabel\tweight — mirrors the Java dictionary export\n"
        + "\n".join(f"{b}\tBRAND\t1.0" for b in BRANDS) + "\n", encoding="utf-8")
    (dict_dir / "category.tsv").write_text(
        "\n".join(f"{c}\tCATEGORY\t1.0" for c in CATEGORIES + ["水果", "充电器", "胶带"]) + "\n",
        encoding="utf-8")

    # ---- Label Studio: import tasks (with pre-annotations) and a realistic export ----
    ls_import, ls_export = [], []
    for k, r in enumerate(rows[:8]):
        result = [
            {"id": f"pre_{n}", "from_name": "label", "to_name": "text", "type": "labels",
             "value": {"start": e["start"], "end": e["end"], "text": e["text"],
                       "labels": [e["label"]]}}
            for n, e in enumerate(r["entities"])
        ]
        data = {"text": r["text"], "meta_id": r["id"],
                "brand_field": r["meta"]["brand_field"],
                "category_field": r["meta"]["category_field"]}
        ls_import.append({"data": data,
                          "predictions": [{"model_version": "weak-v1", "score": 0.82,
                                           "result": result}]})
        ls_export.append({
            "id": 100 + k, "data": data,
            "annotations": [{
                "id": 900 + k, "completed_by": 1, "was_cancelled": False, "skipped": False,
                "lead_time": 6.4, "created_at": "2026-07-20T09:15:00.000000Z",
                "result": result + [{"from_name": "quality", "to_name": "text", "type": "choices",
                                     "value": {"choices": ["ok"]}}],
            }],
            "predictions": [{"model_version": "weak-v1", "score": 0.82, "result": result}],
        })
    write_json(out / "samples" / "label_studio_import.json", ls_import)
    write_json(out / "samples" / "label_studio_export.json", ls_export)

    n_ent = sum(len(r["entities"]) for r in rows)
    print(f"[ok] {len(rows)} examples, {n_ent} entities -> {out/'samples'}")
    print(f"     export.sample.json, gold_sample.jsonl, label_studio_import/export.json, dict/*.tsv")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
