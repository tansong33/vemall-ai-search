import json
from collections import Counter

from scripts.llm_annotate import (
    annotate_row,
    build_request,
    chunks,
    iter_input_rows,
    iter_live_responses,
    label_studio_task,
    looks_like_measure_or_size,
    relocate,
    resume_state,
    skip_verified_prefix,
)


def test_es_dump_streaming_keeps_sku_and_spu_group(tmp_path):
    path = tmp_path / "export.json"
    records = [
        {
            "_index": "products_v2",
            "_id": "outer-id",
            "_source": {
                "sku_id": "sku-1",
                "spu_id": "spu-1",
                "title": "公牛插座10只装",
                "brand_id": "brand-1",
            },
        },
        {
            "_index": "products_v2",
            "_id": "outer-id-2",
            "_source": {"sku_id": "sku-2", "spu_id": "spu-1", "title": "公牛插座20只装"},
        },
    ]
    path.write_text(
        "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in records),
        encoding="utf-8",
    )

    rows = list(iter_input_rows(str(path)))

    assert [row["id"] for row in rows] == ["sku-1", "sku-2"]
    assert rows[0]["meta"]["spu_id"] == "spu-1"
    assert rows[0]["meta"]["split_group"] == "spu-1"
    assert [len(batch) for batch in chunks(iter(rows), 1)] == [1, 1]


def test_label_studio_task_preserves_group_id():
    task = label_studio_task(
        {
            "id": "sku-1",
            "text": "公牛插座",
            "entities": [{"start": 0, "end": 2, "text": "公牛", "label": "BRAND"}],
            "meta": {"teacher": "deepseek-v4-flash", "spu_id": "spu-1", "split_group": "spu-1"},
        }
    )

    # task.data 的形状是跨脚本契约，由 nerkit.label_studio.build_task 统一产出；
    # 完整的键集断言在 tests/test_label_studio_contract.py。
    assert task["data"]["group_id"] == "spu-1"
    assert task["data"]["query_id"] == "sku-1"
    assert task["predictions"][0]["model_version"] == "llm:deepseek-v4-flash"


def test_deepseek_v4_uses_non_thinking_mode_for_extraction():
    request = build_request(
        [{"id": "sku-1", "text": "公牛插座", "meta": {}}],
        "deepseek-v4-flash",
        0.0,
    )

    assert request["thinking"] == {"type": "disabled"}


def test_bailian_uses_provider_specific_non_thinking_parameter():
    request = build_request(
        [{"id": "sku-1", "text": "公牛插座", "meta": {}}],
        "deepseek-v4-flash",
        0.0,
        "https://dashscope.aliyuncs.com/compatible-mode/v1",
    )

    assert request["enable_thinking"] is False
    assert "thinking" not in request
    assert "response_format" not in request


def test_live_concurrency_yields_batches_in_input_order(monkeypatch):
    def fake_call(request, base_url, api_key, retries, timeout):
        marker = request["messages"][-1]["content"]
        return {"marker": marker}

    monkeypatch.setattr("scripts.llm_annotate.call_openai_compatible", fake_call)
    source = [
        [{"id": "1", "text": "第一条", "meta": {}}],
        [{"id": "2", "text": "第二条", "meta": {}}],
        [{"id": "3", "text": "第三条", "meta": {}}],
    ]

    responses = list(
        iter_live_responses(
            source,
            start_batch=7,
            concurrency=2,
            model="deepseek-v4-flash",
            temperature=0,
            base_url="https://api.deepseek.com",
            api_key="not-a-real-key",
            retries=1,
            timeout=1,
        )
    )

    assert [batch_index for batch_index, _, _ in responses] == [7, 8, 9]
    assert [response["marker"] for _, _, response in responses] == [
        "0. 第一条",
        "0. 第二条",
        "0. 第三条",
    ]


def test_relocate_miss_rate_counts_surfaces_not_final_rule_entities():
    stats = Counter()

    assert relocate("兄弟标签机", "兄弟", "BRAND", stats)
    assert relocate("兄弟标签机", "打印机", "CATEGORY", stats) == []

    assert stats["relocate_surface_attempts"] == 2
    assert stats["relocate_surface_hits"] == 1
    assert stats["relocate_miss"] == 1


def test_measure_like_models_are_rejected_and_category_is_capped():
    stats = Counter({"_model": "test-teacher"})
    row = {
        "id": "sku-1",
        "text": "德力西 XH-200 3P 63A 断路器 空气开关",
        "meta": {"category_candidates": ["空气开关"]},
    }

    annotated = annotate_row(
        row,
        [
            {"t": "XH-200", "l": "MODEL"},
            {"t": "3P", "l": "MODEL"},
            {"t": "63A", "l": "MODEL"},
            {"t": "断路器", "l": "CATEGORY"},
            {"t": "空气开关", "l": "CATEGORY"},
        ],
        stats,
        with_rules=True,
    )

    assert sum(entity["label"] == "CATEGORY" for entity in annotated["entities"]) == 1
    assert any(entity["text"] == "空气开关" for entity in annotated["entities"])
    assert any(
        entity["text"] == "XH-200" and entity["label"] == "MODEL"
        for entity in annotated["entities"]
    )
    assert not any(
        entity["label"] == "MODEL" and entity["text"] in {"3P", "63A"}
        for entity in annotated["entities"]
    )
    assert stats["dropped_measure_as_model"] == 2
    assert all("source" in entity for entity in annotated["entities"])


def test_apparel_sizes_are_not_models():
    assert looks_like_measure_or_size("S")
    assert looks_like_measure_or_size("XXXL")
    assert looks_like_measure_or_size("41码")
    assert not looks_like_measure_or_size("M185")


def test_resume_validates_input_prefix(tmp_path):
    output = tmp_path / "partial.jsonl"
    output.write_text(
        json.dumps(
            {
                "id": "sku-2",
                "text": "第二条",
                "entities": [{"start": 0, "end": 1, "label": "CATEGORY"}],
            },
            ensure_ascii=False,
        )
        + "\n",
        encoding="utf-8",
    )
    count, last_id, entities = resume_state(output)

    assert (count, last_id, entities) == (1, "sku-2", 1)
    rows = iter(
        [
            {"id": "sku-2", "text": "第二条", "meta": {}},
            {"id": "sku-3", "text": "第三条", "meta": {}},
        ]
    )
    assert [row["id"] for row in skip_verified_prefix(rows, 1, last_id)] == ["sku-3"]
