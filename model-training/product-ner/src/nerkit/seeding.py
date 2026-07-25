"""Deterministic seeding. Called by every script that touches randomness."""
from __future__ import annotations

import hashlib
import os
import random


def set_seed(seed: int, deterministic: bool = True) -> None:
    random.seed(seed)
    os.environ["PYTHONHASHSEED"] = str(seed)
    try:
        import numpy as np

        np.random.seed(seed)
    except ImportError:
        pass
    try:
        import torch

        torch.manual_seed(seed)
        torch.cuda.manual_seed_all(seed)
        if deterministic:
            torch.backends.cudnn.deterministic = True
            torch.backends.cudnn.benchmark = False
            os.environ.setdefault("CUBLAS_WORKSPACE_CONFIG", ":4096:8")
    except ImportError:
        pass


def stable_hash(text: str) -> int:
    """Process-stable hash (builtin hash() is salted by PYTHONHASHSEED)."""
    return int(hashlib.md5(text.encode("utf-8")).hexdigest()[:16], 16)
