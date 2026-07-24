"""FastAPI inference service.

Design rule: this service must never be the reason search breaks. If the checkpoint is
missing or fails to load, it starts anyway in dictionary-only mode and reports
``degraded=true`` on every response, so the Java side can alert without losing traffic.

    uvicorn service.app:app --host 0.0.0.0 --port 8000 --workers 2
"""
from __future__ import annotations

import logging
import os
import sys
import time
from collections import Counter
from pathlib import Path
from typing import Optional

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from fastapi import FastAPI, HTTPException, Response
from fastapi.responses import PlainTextResponse

from nerkit.dictionary import DictionaryNer
from nerkit.fusion import FusionPolicy
from nerkit.predictor import NerPredictor
from nerkit.version import SCHEMA_VERSION, __version__
from service.schemas import (
    BatchNerRequest, BatchNerResponse, HealthResponse, NerRequest, NerResponse,
)

LOG = logging.getLogger("ner")
logging.basicConfig(level=os.getenv("NER_LOG_LEVEL", "INFO"))

MODEL_DIR = os.getenv("NER_MODEL_DIR", "artifacts/ner-v1/best")
DICT_FILES = [p for p in os.getenv("NER_DICT_FILES", "").split(",") if p.strip()]
MODE = os.getenv("NER_MODE", "hybrid")
TAU_ACCEPT = float(os.getenv("NER_TAU_ACCEPT", "0.60"))
TAU_FALLBACK = float(os.getenv("NER_TAU_FALLBACK", "0.45"))
MAX_QUERY_CHARS = int(os.getenv("NER_MAX_QUERY_CHARS", "128"))
MAX_BATCH = int(os.getenv("NER_MAX_BATCH", "32"))
TORCH_THREADS = int(os.getenv("NER_TORCH_THREADS", "1"))
ENABLE_RULES = os.getenv("NER_ENABLE_RULES", "1") not in ("0", "false", "False")

app = FastAPI(title="Product Search NER", version=__version__)
STATE: dict = {"predictor": None, "started_at": time.time(), "counters": Counter()}


def _load_predictor() -> NerPredictor:
    dictionary = DictionaryNer.from_files(DICT_FILES) if DICT_FILES else None
    policy = FusionPolicy(mode=MODE, tau_accept=TAU_ACCEPT, tau_fallback=TAU_FALLBACK)
    model_path = Path(MODEL_DIR)
    if MODE != "dictionary" and (model_path / "ner_config.json").exists():
        try:
            predictor = NerPredictor.from_pretrained(
                model_path, dictionary=dictionary, policy=policy, device=os.getenv("NER_DEVICE", "cpu"),
                torch_threads=TORCH_THREADS, enable_rules=ENABLE_RULES,
            )
            LOG.info("loaded model from %s (version=%s)", model_path, predictor.model_version)
            return predictor
        except Exception as exc:  # pragma: no cover - defensive startup path
            LOG.exception("model load failed, falling back to dictionary-only: %s", exc)
    else:
        LOG.warning("no checkpoint at %s — starting in dictionary-only mode", model_path)
    if dictionary is None:
        dictionary = DictionaryNer()
    return NerPredictor.dictionary_only(dictionary, enable_rules=ENABLE_RULES, policy=policy)


@app.on_event("startup")
def startup() -> None:
    STATE["predictor"] = _load_predictor()


def predictor() -> NerPredictor:
    if STATE["predictor"] is None:
        STATE["predictor"] = _load_predictor()
    return STATE["predictor"]


@app.get("/health", response_model=HealthResponse)
@app.get("/healthz", response_model=HealthResponse, include_in_schema=False)
def health() -> HealthResponse:
    p = predictor()
    return HealthResponse(
        status="ok" if p.has_model else "degraded",
        model_loaded=p.has_model,
        dictionary_terms=p.dictionary.size if p.dictionary else 0,
        model_version=p.model_version,
        schema_version=SCHEMA_VERSION,
        mode=p.policy.mode,
        uptime_seconds=round(time.time() - STATE["started_at"], 2),
    )


@app.get("/ready", include_in_schema=False)
def ready(response: Response) -> dict:
    p = predictor()
    ok = p.has_model or p.policy.mode == "dictionary"
    response.status_code = 200 if ok else 503
    return {"ready": ok}


@app.get("/labels")
def labels() -> dict:
    p = predictor()
    return {
        "entity_labels": p.scheme.entity_labels if p.scheme else [],
        "scheme": p.scheme.scheme if p.scheme else None,
        "model_version": p.model_version,
        "schema_version": SCHEMA_VERSION,
    }


def _guard(query: str) -> str:
    if query is None:
        raise HTTPException(status_code=422, detail="query is required")
    if len(query) > MAX_QUERY_CHARS:
        raise HTTPException(
            status_code=413, detail=f"query longer than {MAX_QUERY_CHARS} characters"
        )
    return query


@app.post("/ner", response_model=NerResponse)
def ner(req: NerRequest) -> NerResponse:
    started = time.perf_counter()
    query = _guard(req.query)
    STATE["counters"]["requests"] += 1
    if not query.strip():
        p = predictor()
        return NerResponse(
            query=query, entities=[], source="model" if p.has_model else "dictionary",
            degraded=not p.has_model, model_version=p.model_version,
            schema_version=SCHEMA_VERSION, took_ms=0.0, request_id=req.request_id,
        )
    result = predictor().predict_one(query, mode=req.mode)
    STATE["counters"][f"source::{result['source']}"] += 1
    if result["degraded"]:
        STATE["counters"]["degraded"] += 1
    STATE["counters"]["entities"] += len(result["entities"])
    return NerResponse(
        query=result["query"],
        entities=result["entities"],
        source=result["source"],
        degraded=result["degraded"],
        model_version=result["model_version"],
        schema_version=result["schema_version"],
        took_ms=round((time.perf_counter() - started) * 1000, 3),
        request_id=req.request_id,
    )


@app.post("/ner/batch", response_model=BatchNerResponse)
def ner_batch(req: BatchNerRequest) -> BatchNerResponse:
    started = time.perf_counter()
    if len(req.queries) > MAX_BATCH:
        raise HTTPException(status_code=413, detail=f"batch larger than {MAX_BATCH}")
    for q in req.queries:
        _guard(q)
    STATE["counters"]["batch_requests"] += 1
    results = predictor().predict(req.queries, mode=req.mode)
    took = round((time.perf_counter() - started) * 1000, 3)
    return BatchNerResponse(
        results=[
            NerResponse(
                query=r["query"], entities=r["entities"], source=r["source"],
                degraded=r["degraded"], model_version=r["model_version"],
                schema_version=r["schema_version"], took_ms=r["took_ms"],
                request_id=req.request_id,
            )
            for r in results
        ],
        took_ms=took,
        request_id=req.request_id,
    )


@app.get("/metrics", response_class=PlainTextResponse, include_in_schema=False)
def metrics() -> str:
    p = predictor()
    lines = [
        "# TYPE ner_requests_total counter",
        f"ner_requests_total {STATE['counters']['requests']}",
        "# TYPE ner_degraded_total counter",
        f"ner_degraded_total {STATE['counters']['degraded']}",
        "# TYPE ner_entities_total counter",
        f"ner_entities_total {STATE['counters']['entities']}",
        "# TYPE ner_model_loaded gauge",
        f"ner_model_loaded {int(p.has_model)}",
    ]
    for key, value in STATE["counters"].items():
        if key.startswith("source::"):
            lines.append(f'ner_source_total{{source="{key.split("::")[1]}"}} {value}')
    return "\n".join(lines) + "\n"
