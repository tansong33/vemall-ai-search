"""YAML-driven configuration. Every script takes ``--config`` and nothing else matters."""
from __future__ import annotations

import hashlib
import json
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional

import yaml


@dataclass
class DataConfig:
    train_file: str = "data/processed/v1/train.jsonl"
    validation_file: str = "data/processed/v1/validation.jsonl"
    test_file: str = "data/processed/v1/test.jsonl"
    max_length: int = 64
    boundary_policy: str = "expand"  # expand | drop | error
    lowercase: bool = True
    dictionary_files: List[str] = field(default_factory=list)


@dataclass
class ModelConfig:
    encoder: str = "hfl/chinese-macbert-base"
    scheme: str = "BIO"  # BIO | BIOES
    entity_labels: List[str] = field(
        default_factory=lambda: ["BRAND", "CATEGORY", "MODEL", "SPEC", "COLOR"]
    )
    dropout: float = 0.1
    use_crf: bool = False
    constrain_transitions: bool = True


@dataclass
class TrainConfig:
    output_dir: str = "artifacts/ner-v1"
    seed: int = 42
    epochs: int = 8
    train_batch_size: int = 32
    eval_batch_size: int = 64
    learning_rate: float = 3e-5
    crf_learning_rate: float = 1e-3
    head_learning_rate: float = 1e-4
    weight_decay: float = 0.01
    warmup_ratio: float = 0.1
    max_grad_norm: float = 1.0
    scheduler: str = "linear"  # linear | cosine
    mixed_precision: str = "auto"  # auto | fp16 | bf16 | no
    class_weight: str = "none"  # none | inv_sqrt | inv_freq
    class_weight_cap: float = 10.0
    early_stopping_patience: int = 3
    early_stopping_metric: str = "micro_f1"
    eval_steps: int = 0  # 0 = evaluate once per epoch
    logging_steps: int = 20
    num_workers: int = 0
    resume_from: Optional[str] = None
    device: str = "auto"


@dataclass
class ServingConfig:
    model_dir: str = "artifacts/ner-v1/best"
    mode: str = "hybrid"  # model | dictionary | hybrid
    tau_accept: float = 0.60
    tau_fallback: float = 0.45
    agreement_bonus: float = 0.05
    max_batch: int = 32
    max_query_chars: int = 128
    torch_threads: int = 1
    enable_rules: bool = True


@dataclass
class Config:
    data: DataConfig = field(default_factory=DataConfig)
    model: ModelConfig = field(default_factory=ModelConfig)
    train: TrainConfig = field(default_factory=TrainConfig)
    serving: ServingConfig = field(default_factory=ServingConfig)

    @classmethod
    def load(cls, path: str | Path, overrides: Dict[str, Any] | None = None) -> "Config":
        raw = yaml.safe_load(Path(path).read_text(encoding="utf-8")) or {}
        if overrides:
            for dotted, value in overrides.items():
                section, _, key = dotted.partition(".")
                raw.setdefault(section, {})[key] = value
        return cls(
            data=DataConfig(**raw.get("data", {})),
            model=ModelConfig(**raw.get("model", {})),
            train=TrainConfig(**raw.get("train", {})),
            serving=ServingConfig(**raw.get("serving", {})),
        )

    def to_dict(self) -> Dict[str, Any]:
        return {k: asdict(v) for k, v in self.__dict__.items()}

    def fingerprint(self) -> str:
        """Stable hash of the config, recorded with every checkpoint."""
        blob = json.dumps(self.to_dict(), sort_keys=True, ensure_ascii=False)
        return hashlib.sha256(blob.encode("utf-8")).hexdigest()[:12]
