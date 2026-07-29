import json
from collections import Counter

from scripts.llm_consensus import (
    build_review_request,
    merge_candidates,
    parse_review_results,
    review_row,
)


def row(entities):
    return {
        "id": "sku-1",
        "text": "公牛GN-B303插座10只装家用",
        "entities": entities,
        "meta": {"split_group": "spu-1"},
    }


def entity(start, end, label, text, source):
    return {
        "start": start,
        "end": end,
        "label": label,
        "text": text,
        "source": source,
    }


def test_candidates_merge_sources_and_lock_only_hard_evidence():
    extracted = row(
        [
            entity(0, 2, "BRAND", "公牛", "structured"),
            entity(2, 9, "MODEL", "GN-B303", "llm"),
            entity(11, 14, "PACKAGE_COMBINATION", "10只", "rule"),
        ]
    )
    weak = row(
        [
            entity(0, 2, "BRAND", "公牛", "dictionary"),
            entity(2, 9, "MODEL", "GN-B303", "rule"),
        ]
    )

    candidates = merge_candidates(extracted, weak)

    assert len(candidates) == 3
    assert candidates[0].sources == {"structured", "dictionary"}
    assert candidates[0].locked is True
    assert candidates[1].sources == {"llm", "rule"}
    assert candidates[1].locked is False
    assert candidates[2].locked is True


def test_review_accepts_locked_and_explicitly_kept_soft_candidates():
    extracted = row(
        [
            entity(0, 2, "BRAND", "公牛", "structured"),
            entity(2, 9, "MODEL", "GN-B303", "llm"),
            entity(9, 11, "CATEGORY", "插座", "llm"),
            entity(11, 14, "PACKAGE_COMBINATION", "10只", "rule"),
            entity(15, 17, "SCENE", "家用", "llm"),
        ]
    )
    candidates = merge_candidates(extracted)
    result = {
        "complete": True,
        "decisions": [
            {"c": 1, "keep": True, "l": "MODEL"},
            {"c": 2, "keep": True, "l": "CATEGORY"},
            {"c": 4, "keep": False},
        ],
    }

    reviewed = review_row(extracted, candidates, result, "reviewer")

    assert reviewed["_consensus"]["accepted"] is True
    assert [(e["text"], e["label"]) for e in reviewed["entities"]] == [
        ("公牛", "BRAND"),
        ("GN-B303", "MODEL"),
        ("插座", "CATEGORY"),
        ("10只", "PACKAGE_COMBINATION"),
    ]
    assert reviewed["meta"]["consensus_status"] == "accepted"


def test_review_rejects_whole_row_when_candidate_set_is_incomplete():
    extracted = row([entity(2, 9, "MODEL", "GN-B303", "llm")])
    candidates = merge_candidates(extracted)

    reviewed = review_row(
        extracted,
        candidates,
        {"complete": False, "decisions": [{"c": 0, "keep": True}]},
        "reviewer",
    )

    assert reviewed["_consensus"]["accepted"] is False
    assert reviewed["_consensus"]["reason"] == "candidate_set_incomplete"
    assert reviewed["entities"] == []


def test_review_rejects_missing_decision_and_multiple_categories():
    extracted = row(
        [
            entity(9, 11, "CATEGORY", "插座", "llm"),
            entity(15, 17, "CATEGORY", "家用", "llm"),
        ]
    )
    candidates = merge_candidates(extracted)

    missing = review_row(
        extracted,
        candidates,
        {"complete": True, "decisions": [{"c": 0, "keep": True}]},
        "reviewer",
    )
    assert missing["_consensus"]["reason"] == "missing_decision"

    multiple = review_row(
        extracted,
        candidates,
        {
            "complete": True,
            "decisions": [
                {"c": 0, "keep": True},
                {"c": 1, "keep": True},
            ],
        },
        "reviewer",
    )
    assert multiple["_consensus"]["reason"] == "multiple_categories"


def test_review_request_disables_deepseek_thinking():
    extracted = row([entity(2, 9, "MODEL", "GN-B303", "llm")])
    request = build_review_request(
        [{"row": extracted, "candidates": merge_candidates(extracted)}],
        "deepseek-v4-flash",
        0,
        "https://api.deepseek.com",
    )

    assert request["thinking"] == {"type": "disabled"}
    assert request["response_format"] == {"type": "json_object"}


def test_parse_review_results_accepts_markdown_fence():
    stats = Counter()
    content = """```json
{"results":[{"i":0,"complete":true,"decisions":[]}]}
```"""

    parsed = parse_review_results(content, 1, stats)

    assert parsed[0]["complete"] is True
    assert stats["batch_parse_fail"] == 0
