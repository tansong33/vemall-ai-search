"""Encoder + token-classification head (optionally + CRF), with save/load helpers."""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Dict, Optional

import torch
import torch.nn as nn
from transformers import AutoConfig, AutoModel

from .crf import CRF
from .labels import IGNORE_INDEX, LabelScheme

NER_CONFIG_FILE = "ner_config.json"
HEAD_FILE = "head.pt"


class ProductNerModel(nn.Module):
    def __init__(
        self,
        encoder_name_or_path: str,
        scheme: LabelScheme,
        dropout: float = 0.1,
        use_crf: bool = False,
        constrain_transitions: bool = True,
        class_weights: Optional[torch.Tensor] = None,
        encoder_config: Optional[Any] = None,
        _init_encoder: bool = True,
    ) -> None:
        super().__init__()
        self.scheme = scheme
        self.use_crf = use_crf
        self.encoder_name_or_path = encoder_name_or_path
        if _init_encoder:
            self.encoder = AutoModel.from_pretrained(encoder_name_or_path)
        else:
            cfg = encoder_config or AutoConfig.from_pretrained(encoder_name_or_path)
            self.encoder = AutoModel.from_config(cfg)
        hidden = self.encoder.config.hidden_size
        self.dropout = nn.Dropout(dropout)
        self.classifier = nn.Linear(hidden, scheme.num_tags)
        self.crf: Optional[CRF] = None
        if use_crf:
            self.crf = CRF(scheme.num_tags, batch_first=True)
            if constrain_transitions:
                self.crf.apply_constraints(scheme.invalid_transitions())
        if class_weights is not None:
            self.register_buffer("class_weights", class_weights)
        else:
            self.class_weights = None

    # ---------- forward ----------
    def forward(
        self,
        input_ids: torch.Tensor,
        attention_mask: torch.Tensor,
        labels: Optional[torch.Tensor] = None,
        token_type_ids: Optional[torch.Tensor] = None,
    ) -> Dict[str, torch.Tensor]:
        kwargs = {"input_ids": input_ids, "attention_mask": attention_mask}
        if token_type_ids is not None:
            kwargs["token_type_ids"] = token_type_ids
        hidden = self.encoder(**kwargs).last_hidden_state
        logits = self.classifier(self.dropout(hidden))
        out: Dict[str, torch.Tensor] = {"logits": logits}
        if labels is None:
            return out

        if self.crf is not None:
            # CRF cannot consume -100; mask those positions out instead.
            crf_mask = (labels != IGNORE_INDEX) & attention_mask.bool()
            crf_mask[:, 0] = True  # the first timestep must always be active
            safe_labels = labels.clone()
            safe_labels[labels == IGNORE_INDEX] = 0
            out["loss"] = self.crf(logits, safe_labels, mask=crf_mask, reduction="mean")
        else:
            loss_fct = nn.CrossEntropyLoss(
                weight=self.class_weights if self.class_weights is not None else None,
                ignore_index=IGNORE_INDEX,
            )
            out["loss"] = loss_fct(logits.view(-1, self.scheme.num_tags), labels.view(-1))
        return out

    @torch.no_grad()
    def predict_tags(self, input_ids, attention_mask, token_type_ids=None):
        """Returns (tag_ids [B,T], confidence [B,T]) — confidence is a per-token posterior."""
        out = self.forward(input_ids, attention_mask, token_type_ids=token_type_ids)
        logits = out["logits"]
        if self.crf is not None:
            mask = attention_mask.bool()
            paths = self.crf.decode(logits, mask=mask)
            marg = self.crf.marginals(logits, mask=mask)
            tag_ids = torch.zeros_like(input_ids)
            conf = torch.zeros(logits.shape[:2], dtype=torch.float, device=logits.device)
            for b, path in enumerate(paths):
                for t, tag in enumerate(path):
                    tag_ids[b, t] = tag
                    conf[b, t] = marg[b, t, tag]
            return tag_ids, conf
        probs = torch.softmax(logits, dim=-1)
        conf, tag_ids = probs.max(dim=-1)
        return tag_ids, conf

    # ---------- persistence ----------
    def save(self, output_dir: str | Path, extra: Optional[Dict[str, Any]] = None) -> None:
        out = Path(output_dir)
        out.mkdir(parents=True, exist_ok=True)
        self.encoder.save_pretrained(out, safe_serialization=True)
        head_state = {"classifier": self.classifier.state_dict()}
        if self.crf is not None:
            head_state["crf"] = self.crf.state_dict()
        torch.save(head_state, out / HEAD_FILE)
        meta = {
            "entity_labels": self.scheme.entity_labels,
            "scheme": self.scheme.scheme,
            "tags": self.scheme.tags,
            "use_crf": self.use_crf,
            "encoder": self.encoder_name_or_path,
            "hidden_size": self.encoder.config.hidden_size,
        }
        if extra:
            meta.update(extra)
        (out / NER_CONFIG_FILE).write_text(
            json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8"
        )

    @classmethod
    def load(cls, model_dir: str | Path, device: str = "cpu") -> "ProductNerModel":
        d = Path(model_dir)
        meta = json.loads((d / NER_CONFIG_FILE).read_text(encoding="utf-8"))
        scheme = LabelScheme(entity_labels=meta["entity_labels"], scheme=meta["scheme"])
        model = cls(
            encoder_name_or_path=str(d),
            scheme=scheme,
            use_crf=bool(meta.get("use_crf", False)),
            _init_encoder=True,
        )
        head = torch.load(d / HEAD_FILE, map_location="cpu")
        model.classifier.load_state_dict(head["classifier"])
        if model.crf is not None and "crf" in head:
            model.crf.load_state_dict(head["crf"])
        model.to(device).eval()
        model.meta = meta  # type: ignore[attr-defined]
        return model


def parameter_groups(model: ProductNerModel, cfg) -> list[dict]:
    """Discriminative learning rates: encoder << head < CRF."""
    no_decay = ("bias", "LayerNorm.weight", "layer_norm")
    enc_decay, enc_no_decay, head, crf = [], [], [], []
    for name, param in model.named_parameters():
        if not param.requires_grad:
            continue
        if name.startswith("crf."):
            crf.append(param)
        elif name.startswith("classifier."):
            head.append(param)
        elif any(nd in name for nd in no_decay):
            enc_no_decay.append(param)
        else:
            enc_decay.append(param)
    groups = [
        {"params": enc_decay, "lr": cfg.learning_rate, "weight_decay": cfg.weight_decay},
        {"params": enc_no_decay, "lr": cfg.learning_rate, "weight_decay": 0.0},
        {"params": head, "lr": cfg.head_learning_rate, "weight_decay": cfg.weight_decay},
    ]
    if crf:
        groups.append({"params": crf, "lr": cfg.crf_learning_rate, "weight_decay": 0.0})
    return [g for g in groups if g["params"]]
