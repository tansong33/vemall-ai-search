"""Torch dataset + collator over the internal JSONL format.

Internal record format (one JSON object per line)::

    {"id": "...", "text": "公牛插座", "entities": [{"start":0,"end":2,"label":"BRAND"}],
     "meta": {"split_group": "...", "annotation_source": "gold"}}
"""
from __future__ import annotations

from collections import Counter
from typing import Any, Dict, List, Optional, Sequence

import torch
from torch.utils.data import Dataset

from .alignment import encode_example
from .io_utils import read_jsonl
from .labels import IGNORE_INDEX, LabelScheme, Span
from .text_norm import normalize_text


def row_to_spans(row: Dict[str, Any]) -> List[Span]:
    return [
        Span(
            start=int(e["start"]),
            end=int(e["end"]),
            label=str(e["label"]).upper(),
            text=e.get("text", ""),
            source=e.get("source", "gold"),
        )
        for e in row.get("entities", [])
    ]


class NerJsonlDataset(Dataset):
    def __init__(
        self,
        path: str,
        tokenizer,
        scheme: LabelScheme,
        max_length: int = 64,
        boundary_policy: str = "expand",
        lowercase: bool = True,
        keep_labels: Optional[Sequence[str]] = None,
    ) -> None:
        self.rows = read_jsonl(path)
        self.tokenizer = tokenizer
        self.scheme = scheme
        self.max_length = max_length
        self.boundary_policy = boundary_policy
        self.lowercase = lowercase
        self.keep = set(keep_labels) if keep_labels else set(scheme.entity_labels)
        self.stats = Counter()
        self._encoded: List[Dict[str, Any]] = [self._encode(r) for r in self.rows]

    def _encode(self, row: Dict[str, Any]) -> Dict[str, Any]:
        text = normalize_text(row["text"], self.lowercase)
        spans = [s for s in row_to_spans(row) if s.label in self.keep]
        enc = encode_example(
            self.tokenizer, text, spans, self.scheme,
            max_length=self.max_length, boundary_policy=self.boundary_policy,
        )
        rep = enc["report"]
        self.stats["truncated"] += len(rep.truncated_spans)
        self.stats["boundary_mismatch"] += len(rep.boundary_mismatches)
        self.stats["overlapping"] += len(rep.overlapping_spans)
        self.stats["examples"] += 1
        self.stats["entities"] += len(spans)
        return {
            "input_ids": enc["input_ids"],
            "attention_mask": enc["attention_mask"],
            "labels": enc["labels"],
            "offset_mapping": enc["offset_mapping"],
            "special_tokens_mask": enc["special_tokens_mask"],
            "text": row["text"],
            "id": row.get("id", ""),
            "gold": [(s.start, s.end, s.label) for s in spans],
        }

    def __len__(self) -> int:
        return len(self._encoded)

    def __getitem__(self, idx: int) -> Dict[str, Any]:
        return self._encoded[idx]

    def tag_counts(self) -> Counter:
        c = Counter()
        for item in self._encoded:
            for lab in item["labels"]:
                if lab != IGNORE_INDEX:
                    c[self.scheme.id2label[lab]] += 1
        return c


def make_collate_fn(pad_token_id: int):
    def collate(batch: List[Dict[str, Any]]) -> Dict[str, Any]:
        maxlen = max(len(b["input_ids"]) for b in batch)

        def pad(key: str, value: int) -> torch.Tensor:
            return torch.tensor(
                [b[key] + [value] * (maxlen - len(b[key])) for b in batch], dtype=torch.long
            )

        out = {
            "input_ids": pad("input_ids", pad_token_id),
            "attention_mask": pad("attention_mask", 0),
            "labels": pad("labels", IGNORE_INDEX),
        }
        out["meta"] = [
            {
                "text": b["text"],
                "id": b["id"],
                "gold": b["gold"],
                "offset_mapping": b["offset_mapping"],
                "special_tokens_mask": b["special_tokens_mask"],
            }
            for b in batch
        ]
        return out

    return collate


def compute_class_weights(
    counts: Counter, scheme: LabelScheme, mode: str = "inv_sqrt", cap: float = 10.0
) -> Optional[torch.Tensor]:
    """Tag-level weights for the CE loss. ``O`` dominates ~70% of tokens in queries."""
    if mode == "none":
        return None
    total = sum(counts.values())
    weights = []
    for tag in scheme.tags:
        freq = counts.get(tag, 0)
        if freq <= 0:
            weights.append(1.0)
            continue
        ratio = total / freq
        w = ratio ** 0.5 if mode == "inv_sqrt" else ratio
        weights.append(min(w, cap))
    scale = sum(weights) / len(weights)
    return torch.tensor([w / scale for w in weights], dtype=torch.float)
