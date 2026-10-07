"""
Wire shapes for the inference service.

These mirror the records in MlHttpClient.java field for field. Jackson sends
camelCase, so every field carries a camelCase alias and the Python keeps
snake_case names. Change a field here and you change it there in the same
commit, or the two tiers stop understanding each other.
"""

from __future__ import annotations

from typing import List, Optional

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel


class Wire(BaseModel):
    """
    protected_namespaces is cleared because the contract genuinely has fields
    called modelType and modelRan, and pydantic otherwise objects to anything
    starting model_.
    """
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        protected_namespaces=(),
    )


class ArtefactRef(Wire):
    market_id: str
    model_type: str
    version: str
    stage: str
    artefact_uri: Optional[str] = None
    is_stub: Optional[bool] = None


class FeatureVector(Wire):
    spec_version: int
    market_id: str
    location_id: Optional[str] = None
    locality_key: Optional[str] = None
    bedrooms: int
    bathrooms: int
    toilets: Optional[int] = None

    # Accepted and ignored. parkingSpaces is 73.4 per cent present on Ghana
    # Property Centre and 0.0 per cent on Jiji, so it is a source label wearing
    # a feature's name. sizeSquareMetres does not exist in the corpus at all.
    # source is a source label outright. All three stay on the wire so the
    # contract does not move, and none of them reach a model.
    parking_spaces: Optional[int] = None
    size_square_metres: Optional[float] = None
    source: Optional[str] = None

    property_type: Optional[str] = None
    furnished: Optional[bool] = None
    amenities: List[str] = Field(default_factory=list)
    listed_price: Optional[float] = None
    currency: Optional[str] = None


class PredictRequest(Wire):
    spec_version: int
    model: ArtefactRef
    features: FeatureVector


class ForecastRequest(Wire):
    market_id: str
    location_id: Optional[str] = None
    horizon_days: int
    model: ArtefactRef


class PriceResponse(Wire):
    predicted: float
    lower: float
    upper: float
    currency: str
    model_ran: ArtefactRef


class FraudResponse(Wire):
    risk_score: float
    risk_level: str
    reconstruction_error: float
    principal_factor: str
    model_ran: ArtefactRef


class ForecastPoint(Wire):
    day: int
    value: float
    lower: float
    upper: float


class ForecastResponse(Wire):
    series: List[ForecastPoint]
    direction: str
    mean_absolute_error: float
    model_ran: ArtefactRef


class ModelHealth(Wire):
    loaded: bool
    version: Optional[str] = None
    stage: Optional[str] = None
    detail: Optional[str] = None


class HealthResponse(Wire):
    status: str
    spec_version: Optional[str] = None
    market_id: str
    models: dict[str, ModelHealth]