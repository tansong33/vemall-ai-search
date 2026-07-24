#!/usr/bin/env python3
"""Build a *tiny, randomly initialised* encoder + char vocabulary that works offline.

Why this exists: CI (and any air-gapped box) must be able to run the full training →
evaluation → serving path without downloading ``hfl/chinese-macbert-base``. The model
produced here is deliberately useless for accuracy; it only proves the plumbing.

    python scripts/make_smoke_assets.py --data data/samples/gold.jsonl \
        --out artifacts/tiny-encoder
"""
from __future__ import annotations

import argparse
import json
import string
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from nerkit.io_utils import iter_jsonl  # noqa: E402

SPECIALS = ["[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]"]


def build_vocab(texts, extra_chars: str = "") -> list[str]:
    chars = set(string.ascii_lowercase + string.digits + string.punctuation + extra_chars)
    for t in texts:
        chars.update(t.lower())
    chars.discard(" ")
    vocab = SPECIALS + sorted(chars)
    # WordPiece continuation pieces for latin/digit runs (e.g. "256g" -> "256" + "##g")
    vocab += [f"##{c}" for c in sorted(chars) if c.isalnum()]
    return vocab


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", nargs="*", default=[], help="JSONL files to harvest chars from")
    ap.add_argument("--out", required=True)
    ap.add_argument("--hidden-size", type=int, default=64)
    ap.add_argument("--layers", type=int, default=2)
    ap.add_argument("--heads", type=int, default=2)
    ap.add_argument("--max-position", type=int, default=128)
    args = ap.parse_args()

    texts = []
    for path in args.data:
        for row in iter_jsonl(path):
            texts.append(row.get("text", ""))

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    vocab = build_vocab(texts)
    (out / "vocab.txt").write_text("\n".join(vocab) + "\n", encoding="utf-8")

    from transformers import BertConfig, BertForTokenClassification, BertTokenizerFast

    tok = BertTokenizerFast(
        vocab_file=str(out / "vocab.txt"), do_lower_case=True, tokenize_chinese_chars=True
    )
    tok.save_pretrained(str(out))
    cfg = BertConfig(
        vocab_size=len(vocab),
        hidden_size=args.hidden_size,
        num_hidden_layers=args.layers,
        num_attention_heads=args.heads,
        intermediate_size=args.hidden_size * 2,
        max_position_embeddings=args.max_position,
    )
    model = BertForTokenClassification(cfg)
    model.save_pretrained(str(out), safe_serialization=True)
    meta = {"vocab_size": len(vocab), "params": sum(p.numel() for p in model.parameters())}
    (out / "smoke_asset_meta.json").write_text(json.dumps(meta, indent=2), encoding="utf-8")
    print(f"[ok] tiny encoder written to {out} ({meta['params']:,} params, vocab {len(vocab)})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
