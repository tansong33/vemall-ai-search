"""Entity-level (strict) evaluation.

Token accuracy is meaningless here: a query is ~8 characters and most of them are
``O``. Everything below is computed on *exact character spans*, which is what the
search filter actually consumes.
"""
from __future__ import annotations

from collections import Counter, defaultdict
from dataclasses import dataclass, field
from typing import Dict, Iterable, List, Sequence, Set, Tuple

Key = Tuple[int, int, str]


def _prf(tp: int, fp: int, fn: int) -> Dict[str, float]:
    p = tp / (tp + fp) if tp + fp else 0.0
    r = tp / (tp + fn) if tp + fn else 0.0
    f = 2 * p * r / (p + r) if p + r else 0.0
    return {"precision": p, "recall": r, "f1": f, "tp": tp, "fp": fp, "fn": fn, "support": tp + fn}


@dataclass
class ErrorCase:
    text: str
    gold: List[Key]
    pred: List[Key]
    kinds: List[str] = field(default_factory=list)


def evaluate_spans(
    gold_docs: Sequence[Sequence[Key]],
    pred_docs: Sequence[Sequence[Key]],
    labels: Sequence[str],
    texts: Sequence[str] | None = None,
) -> Dict:
    """Strict micro/macro/per-label metrics + error taxonomy + confusion matrix."""
    if len(gold_docs) != len(pred_docs):
        raise ValueError("gold/pred length mismatch")
    per_label_counts = {lab: Counter() for lab in labels}
    err = Counter()
    confusion: Dict[str, Counter] = defaultdict(Counter)
    error_cases: List[ErrorCase] = []

    for i, (gold, pred) in enumerate(zip(gold_docs, pred_docs)):
        gold_set: Set[Key] = set(gold)
        pred_set: Set[Key] = set(pred)
        for key in gold_set & pred_set:
            per_label_counts[key[2]]["tp"] += 1
            confusion[key[2]][key[2]] += 1
        missed = gold_set - pred_set
        spurious = pred_set - gold_set
        for key in missed:
            per_label_counts[key[2]]["fn"] += 1
        for key in spurious:
            per_label_counts.setdefault(key[2], Counter())["fp"] += 1

        kinds: List[str] = []
        used_pred: Set[Key] = set()
        for g in sorted(missed):
            same_span = [p for p in spurious if p[0] == g[0] and p[1] == g[1] and p not in used_pred]
            overlapping = [
                p for p in spurious if p[0] < g[1] and g[0] < p[1] and p not in used_pred
            ]
            if same_span:
                p = same_span[0]
                used_pred.add(p)
                err["type_error"] += 1
                confusion[g[2]][p[2]] += 1
                kinds.append(f"type:{g[2]}->{p[2]}")
            elif overlapping:
                p = overlapping[0]
                used_pred.add(p)
                if p[2] == g[2]:
                    err["boundary_error"] += 1
                    kinds.append(f"boundary:{g[2]}")
                else:
                    err["boundary_type_error"] += 1
                    confusion[g[2]][p[2]] += 1
                    kinds.append(f"boundary+type:{g[2]}->{p[2]}")
            else:
                err["missing"] += 1
                confusion[g[2]]["O"] += 1
                kinds.append(f"missing:{g[2]}")
        for p in sorted(spurious - used_pred):
            err["spurious"] += 1
            confusion["O"][p[2]] += 1
            kinds.append(f"spurious:{p[2]}")
        if kinds:
            error_cases.append(
                ErrorCase(
                    text=texts[i] if texts else "",
                    gold=sorted(gold_set),
                    pred=sorted(pred_set),
                    kinds=kinds,
                )
            )

    per_label = {
        lab: _prf(c.get("tp", 0), c.get("fp", 0), c.get("fn", 0))
        for lab, c in per_label_counts.items()
    }
    tp = sum(c.get("tp", 0) for c in per_label_counts.values())
    fp = sum(c.get("fp", 0) for c in per_label_counts.values())
    fn = sum(c.get("fn", 0) for c in per_label_counts.values())
    micro = _prf(tp, fp, fn)
    present = [lab for lab in per_label if per_label[lab]["support"] > 0]
    macro_f1 = sum(per_label[lab]["f1"] for lab in present) / len(present) if present else 0.0
    return {
        "micro": micro,
        "macro_f1": macro_f1,
        "per_label": per_label,
        "errors": dict(err),
        "confusion": {k: dict(v) for k, v in confusion.items()},
        "error_cases": error_cases,
        "n_docs": len(gold_docs),
    }


def oov_recall(
    gold_docs: Sequence[Sequence[Key]],
    pred_docs: Sequence[Sequence[Key]],
    texts: Sequence[str],
    known_surfaces: Iterable[str],
    label: str = "BRAND",
) -> Dict[str, float]:
    """Recall restricted to entities whose surface form is NOT in the dictionary.

    This is the number that justifies replacing a dictionary with a model at all.
    """
    known = {s.strip().lower() for s in known_surfaces if s.strip()}
    tp = fn = 0
    misses: List[str] = []
    for gold, pred, text in zip(gold_docs, pred_docs, texts):
        pred_set = set(pred)
        for g in gold:
            if g[2] != label:
                continue
            surface = text[g[0] : g[1]].lower()
            if surface in known:
                continue
            if g in pred_set:
                tp += 1
            else:
                fn += 1
                misses.append(surface)
    total = tp + fn
    return {
        "label": label,
        "oov_support": total,
        "oov_recall": tp / total if total else 0.0,
        "examples_missed": misses[:20],
    }
