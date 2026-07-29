"""标注任务的跨脚本契约。

生产者（weak_label / llm_annotate / export_label_studio）和消费者
（assign_label_studio_tasks / compare_label_studio / merge_label_studio_exports）
分属不同脚本，靠 task 的形状对接。历史上两次在这里断过：

* ``llm_annotate`` 自己拼 task，发 ``data.id`` 而消费者要 ``data.query_id``，
  任务导入正常、走到仲裁才炸；
* ``label_studio.NER_LABELS`` 落后标签集两轮，16 个标签里 13 个被判为非法。

两次都没有测试拦住，因为没有一个用例把生产者的输出喂给消费者。本文件专门补这条缝。
"""
from __future__ import annotations

import json

import pytest

from nerkit import label_studio
from nerkit.dictionary import DEFAULT_PRIORITY
from nerkit.labels import Span

pytest.importorskip("yaml")
import yaml  # noqa: E402


CANONICAL_ROW = {
    "id": "sku-1C752497C0BBF003",
    "text": "公牛插座 5号电池 黑色",
    "entities": [
        {"start": 0, "end": 2, "label": "BRAND", "text": "公牛"},
        {"start": 2, "end": 4, "label": "CATEGORY", "text": "插座"},
        {"start": 10, "end": 12, "label": "COLOR", "text": "黑色"},
    ],
    "meta": {"teacher": "qwen-max", "split_group": "spu-1C752490D23BF003",
             "brand_field": "公牛", "category_field": "插座"},
}


def _producers():
    """两个生产者对同一条 canonical 记录产出的 task。"""
    from scripts import llm_annotate, weak_label

    spans = [Span(0, 2, "BRAND", "公牛"), Span(2, 4, "CATEGORY", "插座"),
             Span(10, 12, "COLOR", "黑色")]
    return {
        "llm_annotate": llm_annotate.label_studio_task(CANONICAL_ROW),
        "weak_label": weak_label.to_label_studio_task(CANONICAL_ROW, spans, "weak-v1"),
    }


@pytest.mark.parametrize("producer", ["llm_annotate", "weak_label"])
def test_every_producer_emits_a_readable_query_id(producer: str) -> None:
    """消费者不带 fallback 也必须能取到 query_id —— fallback 用的是不稳定的 LS 任务号。"""
    task = _producers()[producer]
    assert label_studio.query_id_from_task(task) == CANONICAL_ROW["id"]


@pytest.mark.parametrize("producer", ["llm_annotate", "weak_label"])
def test_producers_agree_on_task_data_keys(producer: str) -> None:
    """data 键漂移过一次（id/spu_id vs meta_id/query_id/source），这里钉死。"""
    assert set(_producers()[producer]["data"]) == {
        "text", "query_id", "meta_id", "group_id", "source",
        "brand_field", "category_field",
    }


def test_labeling_config_fields_are_all_provided() -> None:
    """labeling_config.xml 绑了 $brand_field / $category_field，缺了界面上是空白。"""
    from pathlib import Path

    config = Path(__file__).resolve().parents[1] / "label_studio" / "labeling_config.xml"
    xml = config.read_text(encoding="utf-8")
    data = _producers()["llm_annotate"]["data"]
    for field in ("text", "brand_field", "category_field"):
        if f"${field}" in xml:
            assert field in data, f"labeling_config 引用了 ${field}，但 task.data 没有"


def test_round_trip_producer_to_consumer(tmp_path) -> None:
    """生产 -> 落盘 -> 消费者解析出实体，全链路走一遍。"""
    source = tmp_path / "silver.jsonl"
    source.write_text(json.dumps(CANONICAL_ROW, ensure_ascii=False) + "\n", encoding="utf-8")
    target = tmp_path / "import.json"

    assert label_studio.write_tasks_from_jsonl(source, target) == 1
    tasks = label_studio.read_export(target)

    # 把预标注当成一份人工标注回灌，模拟标注员直接确认的情形
    task = tasks[0]
    task["annotations"] = [{"result": task["predictions"][0]["result"], "ground_truth": True}]

    annotation = label_studio.choose_annotation(task, require_ground_truth=True)
    entities = label_studio.entities_from_annotation(task, annotation)
    assert [(e["start"], e["end"], e["label"]) for e in entities] == [
        (0, 2, "BRAND"), (2, 4, "CATEGORY"), (10, 12, "COLOR")
    ]


def test_ner_labels_track_the_live_label_set() -> None:
    """NER_LABELS 曾经硬编码并落后两轮，导致 16 个标签里 13 个被拒。"""
    assert label_studio.NER_LABELS == set(DEFAULT_PRIORITY)
    assert "PRODUCT_TYPE" not in label_studio.NER_LABELS
    assert "ATTRIBUTE_VALUE" not in label_studio.NER_LABELS


def test_label_set_matches_labels_v1_yaml() -> None:
    """labels_v1.yaml 是标签集的唯一事实来源，代码里的副本必须跟着它。"""
    from pathlib import Path

    config = Path(__file__).resolve().parents[1] / "configs" / "labels_v1.yaml"
    spec = yaml.safe_load(config.read_text(encoding="utf-8"))
    assert set(spec["entity_labels"]) == set(DEFAULT_PRIORITY)
    assert spec["priority"] == DEFAULT_PRIORITY, "优先级两端不一致会让评测和线上给出不同实体"


def test_every_live_label_survives_a_round_trip() -> None:
    """16 个标签逐个过一遍消费者的校验，不留下第二个 PRODUCT_TYPE 式的哑弹。"""
    for label in DEFAULT_PRIORITY:
        row = {"id": f"row-{label}", "text": "样例文本",
               "entities": [{"start": 0, "end": 2, "label": label, "text": "样例"}],
               "meta": {}}
        task = label_studio.build_task(row, row["entities"], model_version="t")
        task["annotations"] = [{"result": task["predictions"][0]["result"]}]
        annotation = label_studio.choose_annotation(task)
        entities = label_studio.entities_from_annotation(task, annotation)
        assert entities[0]["label"] == label
