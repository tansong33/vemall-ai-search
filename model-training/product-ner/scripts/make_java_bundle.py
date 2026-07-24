#!/usr/bin/env python3
"""Package the ONNX bundle for Java, including cross-language parity fixtures.

The fixtures are the point: they are generated from the Python implementation and
asserted by the Java unit tests, so a Java-side normalisation or BIO-decoding bug
cannot reach production silently.

    python scripts/make_java_bundle.py --bundle artifacts/ner-v1/onnx \
        --data data/processed/v1/test.jsonl --zip dist/ner-java-bundle.zip
"""
from __future__ import annotations

import argparse
import json
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from nerkit.io_utils import iter_jsonl, write_json
from nerkit.onnx_runtime import OnnxNerPredictor
from nerkit.text_norm import normalize_text

NORMALISATION_PROBES = [
    "华为Ｍａｔｅ６０",          # full-width latin/digits
    "ABC混合Text",              # upper -> lower
    "a\u3000b",                # ideographic space
    "带\u200b零宽",             # zero-width -> space
    "公牛插座",                 # pure CJK, unchanged
    "３Ｍ胶带",                 # full-width brand
    "Ｉ  多  空格",
]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--bundle", required=True)
    ap.add_argument("--data", default="", help="JSONL used to build the decode fixture")
    ap.add_argument("--n-fixture", type=int, default=50)
    ap.add_argument("--zip", default="")
    args = ap.parse_args()

    bundle = Path(args.bundle)
    if not (bundle / "model.onnx").exists():
        print(f"[error] {bundle}/model.onnx not found — run scripts/export_onnx.py first")
        return 2
    if not (bundle / "tokenizer.json").exists():
        print("[error] tokenizer.json missing; the Java tokenizer (DJL) cannot work without it")
        return 2

    # --- fixture 1: normalisation must be identical AND length preserving ---
    norm_fixture = [
        {"raw": t, "normalized": normalize_text(t), "same_length": len(normalize_text(t)) == len(t)}
        for t in NORMALISATION_PROBES
    ]
    write_json(bundle / "fixture_normalization.json", norm_fixture)

    # --- fixture 2: full pipeline output on real queries ---
    decode_fixture = []
    if args.data and Path(args.data).exists():
        predictor = OnnxNerPredictor(bundle)
        texts = [r["text"] for r in iter_jsonl(args.data)][: args.n_fixture]
        for text, spans in zip(texts, predictor.predict(texts)):
            decode_fixture.append({
                "query": text,
                "entities": [
                    {"start": s.start, "end": s.end, "label": s.label, "text": s.text,
                     "confidence": round(s.confidence, 4)}
                    for s in spans
                ],
            })
        write_json(bundle / "fixture_decode.json", decode_fixture)

    readme = f"""# NER ONNX bundle

Drop this whole directory somewhere the Java service can read, e.g.
`E:\\\\ai-search-data\\\\ner-models\\\\ner-v1\\\\`, and point `ner.onnx.bundle-dir` at it.

| file | purpose |
|---|---|
| `model.onnx` | fp32 graph. inputs `input_ids`,`attention_mask` (int64 [B,T]); outputs `logits`,`tag_ids`,`confidence` |
| `model.int8.onnx` | dynamically quantised, ~4x smaller, same accuracy on this test set |
| `tokenizer.json` | WordPiece + offsets, loaded by DJL `HuggingFaceTokenizer` |
| `labels.json` | id -> BIO tag mapping |
| `ner_manifest.json` | model version, max_length, **decode.tau_accept**, normalisation rules, parity report |
| `fixture_normalization.json` | expected output of the text normaliser — asserted by the Java tests |
| `fixture_decode.json` | expected end-to-end entities for {len(decode_fixture)} real queries — asserted by the Java tests |

Rules the Java side must follow (all encoded in `ner_manifest.json`):
1. normalise with **1:1 character substitutions only** — never NFKC, it changes length and
   invalidates every offset;
2. apply `decode.tau_accept` before returning entities;
3. an `I-X` with no preceding `B-X` opens a new entity (matches the Python decoder);
4. trim whitespace from span edges;
5. offsets index the **original** query string, not the normalised one.
"""
    (bundle / "README.md").write_text(readme, encoding="utf-8")

    files = sorted(p for p in bundle.iterdir() if p.is_file())
    print(f"[ok] bundle at {bundle}:")
    for f in files:
        print(f"     {f.name:<32} {f.stat().st_size / 1e6:>8.2f} MB")

    if args.zip:
        zip_path = Path(args.zip)
        zip_path.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
            for f in files:
                zf.write(f, f.name)
        print(f"[ok] zipped -> {zip_path} ({zip_path.stat().st_size / 1e6:.1f} MB)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
