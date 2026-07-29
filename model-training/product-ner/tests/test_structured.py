from nerkit.structured import find_structured_spans


def test_all_verbatim_brand_aliases_and_only_first_category_are_used():
    text = "宾得宝（Bundaberg）果汁饮料"

    spans = find_structured_spans(
        text,
        ["宾得宝", "Bundaberg"],
        ["果汁饮料", "饮料", "食品"],
    )

    assert [(span.text, span.label) for span in spans] == [
        ("宾得宝", "BRAND"),
        ("Bundaberg", "BRAND"),
        ("果汁饮料", "CATEGORY"),
    ]


def test_non_verbatim_database_category_is_not_invented():
    spans = find_structured_spans(
        "兄弟 PT-E550W 标签机",
        ["兄弟"],
        ["家庭护理", "保健器械", "医疗保健"],
    )

    assert [(span.text, span.label) for span in spans] == [("兄弟", "BRAND")]


def test_one_character_candidates_are_not_automatic_labels():
    spans = find_structured_spans("A4纸防盗门白糖", ["A"], ["门", "糖"])

    assert spans == []


def test_ascii_brand_requires_boundaries():
    spans = find_structured_spans("HPE服务器 HP 打印机 XHP", ["HP"], [])

    assert [(span.text, span.start, span.end) for span in spans] == [("HP", 7, 9)]
