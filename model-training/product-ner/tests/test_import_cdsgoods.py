import json
from pathlib import Path

import pytest

from scripts.import_cdsgoods import (
    canonical_title,
    compact_record,
    exact_attribute_candidates,
    expand_inputs,
    iter_bulk_documents,
    split_aliases,
)


def write_bulk(path: Path, documents):
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        for document in documents:
            handle.write(
                json.dumps(
                    {"index": {"_index": "products_v2", "_id": document["sku_id"]}},
                    ensure_ascii=False,
                )
                + "\n"
            )
            handle.write(json.dumps(document, ensure_ascii=False) + "\n")


def test_bulk_shards_are_discovered_and_validated(tmp_path):
    first = tmp_path / "products-000001.ndjson"
    second = tmp_path / "products-000002.ndjson"
    write_bulk(first, [{"sku_id": "sku-1", "spu_id": "spu-1", "title": "公牛插座"}])
    write_bulk(second, [{"sku_id": "sku-2", "spu_id": "spu-2", "title": "华为手机"}])

    paths = expand_inputs([str(tmp_path)])
    rows = [row for path in paths for row, _ in iter_bulk_documents(path)]

    assert paths == [first, second]
    assert [row["sku_id"] for row in rows] == ["sku-1", "sku-2"]


def test_bulk_id_mismatch_is_rejected(tmp_path):
    path = tmp_path / "products-000001.ndjson"
    path.write_text(
        '{"index":{"_index":"products_v2","_id":"action-id"}}\n'
        '{"sku_id":"document-id","spu_id":"spu-1","title":"公牛插座"}\n',
        encoding="utf-8",
    )

    with pytest.raises(ValueError, match="不一致"):
        list(iter_bulk_documents(path))


def test_actionless_document_is_rejected(tmp_path):
    path = tmp_path / "products-000001.ndjson"
    path.write_text(
        '{"sku_id":"sku-1","spu_id":"spu-1","title":"公牛插座"}\n',
        encoding="utf-8",
    )

    with pytest.raises(ValueError, match="缺少 Bulk"):
        list(iter_bulk_documents(path))


def test_directory_shard_gap_is_rejected(tmp_path):
    write_bulk(
        tmp_path / "products-000001.ndjson",
        [{"sku_id": "sku-1", "spu_id": "spu-1", "title": "公牛插座"}],
    )
    write_bulk(
        tmp_path / "products-000003.ndjson",
        [{"sku_id": "sku-3", "spu_id": "spu-3", "title": "华为手机"}],
    )

    with pytest.raises(ValueError, match="分片不连续"):
        expand_inputs([str(tmp_path)])


def test_title_controls_are_replaced_without_changing_internal_offsets():
    title, replacements = canonical_title("\t兄弟\r\n标签机\t")

    assert title == "兄弟  标签机"
    assert replacements == 4


def test_compact_record_drops_private_search_fields_and_keeps_grouping():
    document = {
        "sku_id": "sku-1",
        "spu_id": "spu-1",
        "title": "兄弟 Brother PT-E550W 标签机",
        "brand_id": "brand-1",
        "brand_name": "兄弟",
        "brand_en_name": "Brother",
        "brand_aliases": "兄弟,brother",
        "class_id": "class-3",
        "class_l3": "标签机",
        "class_l2": "办公设备",
        "class_l1": "办公用品",
        "supplier_name": "不应进入训练数据",
        "shop_name": "不应进入训练数据",
        "sales_price": 2000,
        "sku_pic_url": "https://example.invalid/private.jpg",
    }

    row = compact_record(document, {"_id": "sku-1"})

    assert row["id"] == "sku-1"
    assert row["meta"]["split_group"] == "spu-1"
    assert row["meta"]["brand_candidates"] == ["兄弟", "Brother"]
    assert row["meta"]["brand_field"] == "兄弟"
    assert row["meta"]["category_field"] == "标签机"
    serialized = json.dumps(row, ensure_ascii=False)
    assert "supplier_name" not in serialized
    assert "shop_name" not in serialized
    assert "sales_price" not in serialized
    assert "sku_pic_url" not in serialized


def test_placeholder_brand_candidates_are_removed():
    row = compact_record(
        {
            "sku_id": "sku-1",
            "spu_id": "spu-1",
            "title": "通用插座",
            "brand_name": "无品牌",
            "brand_aliases": "其他|OEM",
        },
        {"_id": "sku-1"},
    )

    assert row["meta"]["brand_candidates"] == []
    assert "brand_field" not in row["meta"]


def test_aliases_support_json_and_delimiters():
    assert split_aliases('["华为", "HUAWEI"]') == ["华为", "HUAWEI"]
    assert split_aliases("华为，HUAWEI|荣耀") == ["华为", "HUAWEI", "荣耀"]


def test_only_verbatim_structured_attributes_are_compacted_and_deduplicated():
    document = {
        "spec_json": json.dumps(
            [{"ggmc": "颜色", "ggVal": "深空灰"}, {"ggmc": "单位", "ggVal": "台"}],
            ensure_ascii=False,
        ),
        "attr_json": json.dumps(
            [{"groupName": "主体", "atts": [{"attName": "材质", "vals": ["不锈钢", "塑料"]}]}],
            ensure_ascii=False,
        ),
        "pro_attr_json": json.dumps(
            [{"groupName": "主体", "atts": [{"attName": "材质", "vals": ["不锈钢"]}]}],
            ensure_ascii=False,
        ),
    }

    values = exact_attribute_candidates(document, "深空灰不锈钢保温杯")

    assert values == [
        {"name": "颜色", "value": "深空灰", "sources": ["spec"]},
        {"name": "材质", "value": "不锈钢", "sources": ["sku_attr", "spu_attr"]},
    ]
