"""Request/response contract for the Java backend.

Field style is switchable because the existing Spring DTO naming could not be inspected
from here: set ``NER_FIELD_STYLE=camel`` (default, matches Jackson defaults) or
``snake``. Both styles are accepted on input regardless of the setting.
"""
from __future__ import annotations

import os
from typing import List, Literal, Optional

from pydantic import BaseModel, ConfigDict, Field


def to_camel(name: str) -> str:
    head, *rest = name.split("_")
    return head + "".join(w.capitalize() for w in rest)


FIELD_STYLE = os.getenv("NER_FIELD_STYLE", "camel").lower()
_MODEL_CONFIG = ConfigDict(
    populate_by_name=True,
    alias_generator=to_camel if FIELD_STYLE == "camel" else None,
)


class Entity(BaseModel):
    model_config = _MODEL_CONFIG

    text: str = Field(..., description="entity surface as it appears in the original query")
    label: str = Field(..., description="BRAND | CATEGORY | MODEL | SPEC | COLOR ...")
    start: int = Field(..., ge=0, description="character offset into the ORIGINAL query, inclusive")
    end: int = Field(..., ge=0, description="character offset, exclusive")
    confidence: float = Field(..., ge=0.0, le=1.0)
    source: Literal["model", "dictionary", "hybrid", "rule"] = "model"


class NerRequest(BaseModel):
    model_config = _MODEL_CONFIG

    query: str = Field(..., description="raw user query")
    mode: Optional[Literal["model", "dictionary", "hybrid"]] = Field(
        None, description="per-request override; defaults to the server's configured mode"
    )
    request_id: Optional[str] = Field(None, description="echoed back for log correlation")


class BatchNerRequest(BaseModel):
    model_config = _MODEL_CONFIG

    queries: List[str] = Field(..., min_length=1)
    mode: Optional[Literal["model", "dictionary", "hybrid"]] = None
    request_id: Optional[str] = None


class NerResponse(BaseModel):
    model_config = _MODEL_CONFIG

    query: str
    entities: List[Entity]
    source: str = Field(..., description="model | dictionary | hybrid — how THIS answer was produced")
    degraded: bool = Field(
        False, description="true when the model was unavailable/unsure and the dictionary answered"
    )
    model_version: str
    schema_version: str
    took_ms: float
    request_id: Optional[str] = None


class BatchNerResponse(BaseModel):
    model_config = _MODEL_CONFIG

    results: List[NerResponse]
    took_ms: float
    request_id: Optional[str] = None


class HealthResponse(BaseModel):
    model_config = _MODEL_CONFIG

    status: Literal["ok", "degraded"]
    model_loaded: bool
    dictionary_terms: int
    model_version: str
    schema_version: str
    mode: str
    uptime_seconds: float
