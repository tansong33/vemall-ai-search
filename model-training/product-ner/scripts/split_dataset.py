#!/usr/bin/env python3
"""Leakage-free train/validation/test split.

A product catalogue is full of near-duplicates ("公牛插座3米黑色" vs "公牛插座5米黑色").
Splitting row-by-row puts variants of the same product on both sides and inflates F1 by
several points. Rows are therefore grouped first, and *groups* are split:

  1. exact key   — normalised text
  2. template key — digits -> '#', latin runs -> '@'  (catches spec variants)
  3. near-dup    — MinHash + LSH over character 4-grams, Jaccard >= --near-dup-threshold
  4. optional    — an explicit business key (SPU/model) via --group-field

Groups are then packed greedily (largest first) into the split with the largest deficit,
which keeps label proportions close to target. The report proves no group crosses splits.

    python scripts/split_dataset.py --input data/gold/v1/all.jsonl \
        --outdir data/processed/v1 --ratios 0.8 0.1 0.1
"""
from __future__ import annotations

import argparse
import random
import re
from collections import Counter, defaultdict
from typing import Dict, Iterable, List, Tuple

from _common import parse_kv  # noqa: F401

from nerkit.io_utils import build_manifest, iter_jsonl, write_json, write_jsonl
from nerkit.seeding import set_seed, stable_hash
from nerkit.text_norm import normalize_text

_MERSENNE = (1 << 61) - 1


class UnionFind:
    def __init__(self, n: int) -> None:
        self.parent = list(range(n))

    def find(self, x: int) -> int:
        while self.parent[x] != x:
            self.parent[x] = self.parent[self.parent[x]]
            x = self.parent[x]
        return x

    def union(self, a: int, b: int) -> None:
        ra, rb = self.find(a), self.find(b)
        if ra != rb:
            self.parent[rb] = ra


def template_key(text: str) -> str:
    t = re.sub(r"\d+", "#", normalize_text(text))
    t = re.sub(r"[a-z]+", "@", t)
    return re.sub(r"\s+", "", t)


def shingles(text: str, k: int = 4) -> List[int]:
    t = re.sub(r"\s+", "", normalize_text(text))
    if len(t) <= k:
        return [stable_hash(t)] if t else []
    return [stable_hash(t[i : i + k]) for i in range(len(t) - k + 1)]


def minhash(sh: List[int], perms: List[Tuple[int, int]]) -> Tuple[int, ...]:
    if not sh:
        return tuple(0 for _ in perms)
    return tuple(min(((a * h + b) % _MERSENNE) for h in sh) for a, b in perms)


def jaccard(a: Iterable[int], b: Iterable[int]) -> float:
    sa, sb = set(a), set(b)
    if not sa or not sb:
        return 0.0
    return len(sa & sb) / len(sa | sb)


def build_groups(rows: List[Dict], threshold: float, num_perm: int, bands: int,
                 group_field: str = "", seed: int = 42) -> List[int]:
    rng = random.Random(seed)
    perms = [(rng.randrange(1, _MERSENNE), rng.randrange(0, _MERSENNE)) for _ in range(num_perm)]
    uf = UnionFind(len(rows))

    by_exact: Dict[str, int] = {}
    by_template: Dict[str, int] = {}
    by_business: Dict[str, int] = {}
    sh_cache: List[List[int]] = []
    signatures: List[Tuple[int, ...]] = []

    for i, row in enumerate(rows):
        text = row["text"]
        sh = shingles(text)
        sh_cache.append(sh)
        signatures.append(minhash(sh, perms))
        ekey = normalize_text(text)
        uf.union(by_exact.setdefault(ekey, i), i)
        tkey = template_key(text)
        uf.union(by_template.setdefault(tkey, i), i)
        if group_field:
            bkey = str((row.get("meta") or {}).get(group_field, "")).strip()
            if bkey:
                uf.union(by_business.setdefault(bkey, i), i)

    rows_per_band = max(1, num_perm // bands)
    buckets: Dict[Tuple, List[int]] = defaultdict(list)
    for i, sig in enumerate(signatures):
        for b in range(bands):
            chunk = sig[b * rows_per_band : (b + 1) * rows_per_band]
            buckets[(b, chunk)].append(i)
    for members in buckets.values():
        if len(members) < 2 or len(members) > 200:
            continue
        pivot = members[0]
        for other in members[1:]:
            if jaccard(sh_cache[pivot], sh_cache[other]) >= threshold:
                uf.union(pivot, other)
    return [uf.find(i) for i in range(len(rows))]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", action="append", required=True, help="JSONL (repeatable)")
    ap.add_argument("--outdir", required=True)
    ap.add_argument("--ratios", nargs=3, type=float, default=[0.8, 0.1, 0.1])
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--near-dup-threshold", type=float, default=0.8)
    ap.add_argument("--num-perm", type=int, default=64)
    ap.add_argument("--bands", type=int, default=16)
    ap.add_argument("--group-field", default="", help="meta.<field> forced into one group, e.g. spu_id")
    ap.add_argument("--gold-only-test", action="store_true",
                    help="test/validation may only contain meta.annotation_source == gold")
    args = ap.parse_args()

    set_seed(args.seed)
    rows: List[Dict] = []
    for path in args.input:
        rows.extend(iter_jsonl(path))
    if not rows:
        print("[error] no rows")
        return 2

    group_ids = build_groups(rows, args.near_dup_threshold, args.num_perm, args.bands,
                             args.group_field, args.seed)
    groups: Dict[int, List[int]] = defaultdict(list)
    for idx, g in enumerate(group_ids):
        groups[g].append(idx)

    gold_groups, other_groups = [], []
    for g, idxs in groups.items():
        is_gold = all(
            (rows[i].get("meta") or {}).get("annotation_source", "gold") == "gold" for i in idxs
        )
        (gold_groups if is_gold else other_groups).append((g, idxs))

    rng = random.Random(args.seed)
    rng.shuffle(gold_groups)
    rng.shuffle(other_groups)

    total = len(rows)
    targets = {
        "train": args.ratios[0] * total,
        "validation": args.ratios[1] * total,
        "test": args.ratios[2] * total,
    }
    assigned: Dict[str, List[int]] = {"train": [], "validation": [], "test": []}
    counts = Counter()

    def place(pool, eligible: Tuple[str, ...]) -> None:
        for _, idxs in sorted(pool, key=lambda kv: -len(kv[1])):
            split = max(eligible, key=lambda s: targets[s] - counts[s])
            assigned[split].extend(idxs)
            counts[split] += len(idxs)

    # Silver/weak groups can only ever land in train when --gold-only-test is set.
    place(gold_groups, ("train", "validation", "test"))
    place(other_groups, ("train",) if args.gold_only_test else ("train", "validation", "test"))

    written = {}
    for split, idxs in assigned.items():
        rows_out = [rows[i] for i in sorted(idxs)]
        for r in rows_out:
            r.setdefault("meta", {})["split"] = split
        written[split] = write_jsonl(f"{args.outdir}/{split}.jsonl", rows_out)

    split_of: Dict[int, str] = {}
    leaked = []
    for split, idxs in assigned.items():
        for i in idxs:
            split_of[i] = split
    for g, idxs in groups.items():
        s = {split_of[i] for i in idxs}
        if len(s) > 1:
            leaked.append({"group": g, "splits": sorted(s), "size": len(idxs)})

    label_dist = {
        split: dict(Counter(e["label"] for i in idxs for e in rows[i].get("entities", [])))
        for split, idxs in assigned.items()
    }
    report = {
        **build_manifest({"script": "split_dataset"}),
        "input_files": args.input,
        "n_rows": total,
        "n_groups": len(groups),
        "largest_group": max((len(v) for v in groups.values()), default=0),
        "written": written,
        "label_distribution": label_dist,
        "leaked_groups": leaked,
        "params": {k: v for k, v in vars(args).items()},
    }
    write_json(f"{args.outdir}/split_report.json", report)
    print(f"[ok] {total} rows in {len(groups)} groups -> {written}")
    print(f"     leakage across splits: {len(leaked)} groups (must be 0)")
    for split, dist in label_dist.items():
        print(f"     {split:<11} {dist}")
    return 1 if leaked else 0


if __name__ == "__main__":
    raise SystemExit(main())
