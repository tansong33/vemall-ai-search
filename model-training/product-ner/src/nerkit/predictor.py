"""Inference wrapper shared by predict.py and evaluate.py."""
from __future__ import annotations

import json
import time
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence

from .dictionary import DictionaryNer
from .fusion import FusionPolicy, fuse
from .labels import LabelScheme, Span
from .patterns import annotate_rules
from .text_norm import normalize_text
from .version import SCHEMA_VERSION, __version__


class NerPredictor:
    """Loads a checkpoint once, then answers queries.

    ``normalize_text`` is offset preserving, so every offset returned here indexes the
    ORIGINAL query string the user typed — that is what Java needs for highlighting.
    """

    def __init__(
        self,
        model=None,
        tokenizer=None,
        scheme: Optional[LabelScheme] = None,
        dictionary: Optional[DictionaryNer] = None,
        policy: Optional[FusionPolicy] = None,
        max_length: int = 64,
        device: str = "cpu",
        model_version: str = "unversioned",
        enable_rules: bool = True,
        lowercase: bool = True,
    ) -> None:
        self.model = model
        self.tokenizer = tokenizer
        self.scheme = scheme
        self.dictionary = dictionary
        self.policy = policy or FusionPolicy()
        self.max_length = max_length
        self.device = device
        self.model_version = model_version
        self.enable_rules = enable_rules
        self.lowercase = lowercase

    # ---------- construction ----------
    @classmethod
    def from_pretrained(
        cls,
        model_dir: str | Path,
        dictionary: Optional[DictionaryNer] = None,
        policy: Optional[FusionPolicy] = None,
        device: str = "cpu",
        max_length: int = 64,
        torch_threads: int = 1,
        **kwargs,
    ) -> "NerPredictor":
        import torch
        from transformers import AutoTokenizer

        from .model import ProductNerModel

        torch.set_num_threads(max(1, torch_threads))
        d = Path(model_dir)
        model = ProductNerModel.load(d, device=device)
        tokenizer = AutoTokenizer.from_pretrained(str(d), use_fast=True)
        meta = json.loads((d / "ner_config.json").read_text(encoding="utf-8"))
        scheme = LabelScheme(entity_labels=meta["entity_labels"], scheme=meta["scheme"])
        return cls(
            model=model, tokenizer=tokenizer, scheme=scheme, dictionary=dictionary,
            policy=policy, max_length=max_length, device=device,
            model_version=meta.get("model_version", f"ner-{__version__}"), **kwargs,
        )

    @classmethod
    def dictionary_only(
        cls, dictionary: DictionaryNer, enable_rules: bool = True, **kwargs
    ) -> "NerPredictor":
        """Degraded mode: no checkpoint on disk, behave like the Java dictionary NER."""
        policy = kwargs.pop("policy", None) or FusionPolicy(mode="dictionary")
        policy.mode = "dictionary"
        return cls(dictionary=dictionary, policy=policy, enable_rules=enable_rules,
                   model_version="dictionary-only", **kwargs)

    @property
    def has_model(self) -> bool:
        return self.model is not None

    # ---------- inference ----------
    def _model_spans(self, texts: Sequence[str]) -> List[List[Span]]:
        import torch

        from .alignment import decode_spans, encode_example

        encoded = [
            encode_example(self.tokenizer, normalize_text(t, self.lowercase),
                           max_length=self.max_length)
            for t in texts
        ]
        maxlen = max(len(e["input_ids"]) for e in encoded)
        pad_id = self.tokenizer.pad_token_id or 0
        input_ids = torch.tensor(
            [e["input_ids"] + [pad_id] * (maxlen - len(e["input_ids"])) for e in encoded]
        )
        attn = torch.tensor(
            [e["attention_mask"] + [0] * (maxlen - len(e["attention_mask"])) for e in encoded]
        )
        input_ids, attn = input_ids.to(self.device), attn.to(self.device)
        tag_ids, conf = self.model.predict_tags(input_ids, attn)
        tag_ids, conf = tag_ids.cpu().tolist(), conf.cpu().tolist()
        out: List[List[Span]] = []
        for i, text in enumerate(texts):
            enc = encoded[i]
            n = len(enc["input_ids"])
            out.append(
                decode_spans(
                    text, enc["offset_mapping"], enc["special_tokens_mask"],
                    tag_ids[i][:n], self.scheme, token_confidence=conf[i][:n], source="model",
                )
            )
        return out

    def predict(self, texts: Sequence[str], mode: Optional[str] = None) -> List[Dict[str, Any]]:
        started = time.perf_counter()
        policy = FusionPolicy(**{**self.policy.__dict__})
        if mode:
            policy.mode = mode
        if policy.mode != "dictionary" and not self.has_model:
            policy.mode = "dictionary"  # automatic degradation, reported per response

        model_spans: List[List[Span]] = (
            self._model_spans(texts) if policy.mode != "dictionary" else [[] for _ in texts]
        )
        results: List[Dict[str, Any]] = []
        for i, text in enumerate(texts):
            dict_spans = self.dictionary.annotate(text) if self.dictionary else []
            rule_spans = annotate_rules(text) if self.enable_rules else []
            spans, source, degraded = fuse(model_spans[i], dict_spans, rule_spans, policy)
            results.append(
                {
                    "query": text,
                    "entities": [s.to_dict() for s in spans],
                    "source": source,
                    "degraded": degraded or not self.has_model,
                    "model_version": self.model_version,
                    "schema_version": SCHEMA_VERSION,
                }
            )
        took = (time.perf_counter() - started) * 1000.0
        per = took / max(len(texts), 1)
        for r in results:
            r["took_ms"] = round(per, 3)
        return results

    def predict_one(self, text: str, mode: Optional[str] = None) -> Dict[str, Any]:
        return self.predict([text], mode=mode)[0]
