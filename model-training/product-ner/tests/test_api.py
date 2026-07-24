"""FastAPI contract tests — these run in dictionary-only (degraded) mode on purpose,
because that is exactly the state the service starts in when a checkpoint is missing."""
import os
import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

ROOT = Path(__file__).resolve().parents[1]
os.environ.setdefault("NER_DICT_FILES", str(ROOT / "data/dict/brand.tsv") + "," +
                     str(ROOT / "data/dict/category.tsv"))
os.environ.setdefault("NER_MODEL_DIR", str(ROOT / "artifacts/does-not-exist"))
sys.path.insert(0, str(ROOT))

from service.app import app  # noqa: E402


@pytest.fixture(scope="module")
def client():
    with TestClient(app) as c:
        yield c


def test_health_reports_degraded_without_a_checkpoint(client):
    body = client.get("/health").json()
    assert body["status"] == "degraded"
    assert body["modelLoaded"] is False
    assert body["dictionaryTerms"] > 0


def test_ner_still_answers_when_the_model_is_missing(client):
    body = client.post("/ner", json={"query": "公牛插座"}).json()
    assert body["degraded"] is True
    assert body["source"] == "dictionary"
    labels = {(e["label"], e["start"], e["end"]) for e in body["entities"]}
    assert ("BRAND", 0, 2) in labels and ("CATEGORY", 2, 4) in labels


def test_offsets_are_usable_for_highlighting(client):
    query = "公牛插座 3米"
    body = client.post("/ner", json={"query": query}).json()
    for e in body["entities"]:
        assert query[e["start"]:e["end"]] == e["text"]


def test_response_carries_versions_and_timing(client):
    body = client.post("/ner", json={"query": "华为手机", "requestId": "abc-1"}).json()
    assert body["schemaVersion"] and body["modelVersion"]
    assert body["tookMs"] >= 0 and body["requestId"] == "abc-1"


def test_empty_query_is_not_an_error(client):
    body = client.post("/ner", json={"query": "   "}).json()
    assert body["entities"] == []


def test_oversized_query_is_rejected(client):
    r = client.post("/ner", json={"query": "长" * 500})
    assert r.status_code == 413


def test_batch_endpoint(client):
    body = client.post("/ner/batch", json={"queries": ["公牛插座", "华为手机"]}).json()
    assert len(body["results"]) == 2


def test_labels_and_metrics_endpoints(client):
    assert client.get("/labels").status_code == 200
    assert "ner_requests_total" in client.get("/metrics").text
