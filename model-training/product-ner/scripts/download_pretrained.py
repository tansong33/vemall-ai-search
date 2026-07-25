#!/usr/bin/env python3
"""Download the pretrained encoder into models/pretrained/<name>/ — the place every
config already points at, so training never reaches for the network again.

    python scripts/download_pretrained.py                 # the recommended default
    python scripts/download_pretrained.py --model hfl/chinese-macbert-large
    python scripts/download_pretrained.py --all           # main + fallback + large
    HF_ENDPOINT=https://hf-mirror.com python scripts/download_pretrained.py   # 国内加速

A model without a fast tokenizer is rejected on the spot: character offsets are the
whole product here, and slow tokenizers cannot produce them.
"""
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

RECOMMENDED = "hfl/chinese-macbert-base"
PRESETS = {
    "main": "hfl/chinese-macbert-base",       # ~102M, Apache-2.0 — the default
    "fallback": "hfl/rbt3",                   # ~38M, 3 layers — when latency matters
    "large": "hfl/chinese-macbert-large",     # ~324M — worth trying on rented GPUs
    "roberta": "hfl/chinese-roberta-wwm-ext",  # ~102M — the ablation baseline
}


def download(model_id: str, root: Path, revision: str | None = None) -> Path:
    from huggingface_hub import snapshot_download

    target = root / model_id.split("/")[-1]
    target.mkdir(parents=True, exist_ok=True)
    print(f"[..] {model_id} -> {target}  (endpoint: {os.getenv('HF_ENDPOINT', 'huggingface.co')})")
    snapshot_download(
        repo_id=model_id,
        revision=revision,
        local_dir=str(target),
        allow_patterns=["*.json", "*.txt", "*.safetensors", "*.model", "*.bin"],
        ignore_patterns=["*.h5", "*.msgpack", "*.ot", "flax*", "tf_*"],
    )
    verify(target, model_id)
    size = sum(f.stat().st_size for f in target.rglob("*") if f.is_file()) / 1e6
    print(f"[ok] {model_id}  {size:.0f} MB  -> {target}")
    return target


def verify(path: Path, model_id: str) -> None:
    from transformers import AutoConfig, AutoTokenizer

    tok = AutoTokenizer.from_pretrained(str(path), use_fast=True)
    if not tok.is_fast:
        raise SystemExit(
            f"{model_id} has no fast tokenizer -> no offset_mapping -> unusable here. "
            "Pick a BERT-family checkpoint (see docs/model_selection.md)."
        )
    probe = tok("华为Mate60 256G", return_offsets_mapping=True)
    assert probe["offset_mapping"], "tokenizer returned no offsets"
    cfg = AutoConfig.from_pretrained(str(path))
    print(f"     layers={cfg.num_hidden_layers} hidden={cfg.hidden_size} "
          f"vocab={cfg.vocab_size} max_pos={cfg.max_position_embeddings} fast_tokenizer=OK")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default=RECOMMENDED)
    ap.add_argument("--preset", choices=sorted(PRESETS), default=None)
    ap.add_argument("--all", action="store_true", help="main + fallback + large")
    ap.add_argument("--out", default="models/pretrained")
    ap.add_argument("--revision", default=None, help="pin a commit sha for reproducibility")
    args = ap.parse_args()

    root = Path(args.out)
    root.mkdir(parents=True, exist_ok=True)
    if args.all:
        for key in ("main", "fallback", "large"):
            download(PRESETS[key], root)
    elif args.preset:
        download(PRESETS[args.preset], root, args.revision)
    else:
        download(args.model, root, args.revision)
    print(f"\n[next] configs already point at {root}/<name>; run:\n"
          f"       python scripts/train.py --config configs/train_base.yaml")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
