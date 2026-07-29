"""批量预测与主动学习挑样本。

这是「模型 -> 人工复审 -> 重新训练」回流链路的第一环。它一度是缺的：predict.py 输出的
是 HTTP API 的形状（query / took_ms / schema_version），喂不进 export_label_studio.py，
链路在这里断着而没有任何测试发现。
"""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

import pytest

from predict_corpus import is_uncertain

ROOT = Path(__file__).resolve().parents[1]


def test_no_entity_is_always_uncertain() -> None:
    """零实体是未登录词高发区 —— 正是模型相对词典的价值所在，必须送人工。"""
    assert is_uncertain([], tau=0.75, require_labels=[]) == "no_entity"


def test_low_confidence_uses_the_weakest_entity() -> None:
    """一条里只要有一个实体没把握就该复审，不能用平均值把它稀释掉。"""
    entities = [
        {"label": "BRAND", "confidence": 0.99},
        {"label": "MODEL", "confidence": 0.40},
    ]
    assert is_uncertain(entities, tau=0.75, require_labels=[]) == "low_confidence"
    assert is_uncertain(entities, tau=0.30, require_labels=[]) == ""


def test_required_label_missing_wins_over_confidence() -> None:
    """强制每条都要有 CATEGORY 时，缺了就送审，哪怕其余实体置信度很高。"""
    entities = [{"label": "BRAND", "confidence": 0.99}]
    assert is_uncertain(entities, 0.75, ["CATEGORY"]) == "missing:CATEGORY"
    assert is_uncertain(entities, 0.75, ["BRAND"]) == ""


def test_round_trip_prediction_to_label_studio(tmp_path: Path) -> None:
    """跑通 predict_corpus -> export_label_studio -> 消费者能读出 query_id。

    断的是脚本之间的接缝，所以这里必须真的起子进程串一遍，不能只调函数。
    """
    sys.path.insert(0, str(ROOT / "src"))
    from nerkit import label_studio

    corpus = tmp_path / "raw.jsonl"
    corpus.write_text(
        json.dumps({"id": "sku-1", "text": "公牛插座", "spu_id": "spu-1"}, ensure_ascii=False)
        + "\n",
        encoding="utf-8",
    )
    dictionary = tmp_path / "brand.tsv"
    dictionary.write_text("公牛\tBRAND\t1.0\n插座\tCATEGORY\t1.0\n", encoding="utf-8")

    predicted = tmp_path / "pred.jsonl"
    run = subprocess.run(
        [sys.executable, str(ROOT / "scripts" / "predict_corpus.py"),
         "--input", str(corpus), "--out", str(predicted),
         "--dict", str(dictionary), "--mode", "dictionary"],
        capture_output=True, text=True, cwd=str(ROOT),
    )
    assert run.returncode == 0, run.stderr

    row = json.loads(predicted.read_text(encoding="utf-8").strip())
    assert row["id"] == "sku-1"
    assert row["meta"]["annotation_source"] == "prediction", "模型输出不是 gold 也不是 silver"
    assert row["meta"]["split_group"] == "spu-1", "缺 split_group 会让切分退化成按行，进而泄漏"
    assert {e["label"] for e in row["entities"]} == {"BRAND", "CATEGORY"}

    tasks_path = tmp_path / "import.json"
    run = subprocess.run(
        [sys.executable, str(ROOT / "scripts" / "export_label_studio.py"),
         "--input", str(predicted), "--out", str(tasks_path)],
        capture_output=True, text=True, cwd=str(ROOT),
    )
    assert run.returncode == 0, run.stderr

    task = label_studio.read_export(tasks_path)[0]
    assert label_studio.query_id_from_task(task) == "sku-1"
    labels = {r["value"]["labels"][0] for r in task["predictions"][0]["result"]}
    assert labels == {"BRAND", "CATEGORY"}
