"""
RentRadar inference service, Phase 10.

Three endpoints, matching the paths the Spring Boot MlHttpClient already calls:

    POST /v1/price/estimate
    POST /v1/fraud/assess
    POST /v1/forecast/trend

Plus /health for the gateway's health check and /v1/admin/reload for its admin
reload endpoint.

Models load once at startup, never per request. Loading a Keras model per
request would put a second on every call and is the usual reason a Python
inference tier looks slow.

    uvicorn service.main:app --host 0.0.0.0 --port 8000
"""

from __future__ import annotations

import logging
import os
import sys
from contextlib import asynccontextmanager
from pathlib import Path

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "3")

# Run from the ml folder so 'rentradar' and 'service' both import.
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from fastapi import FastAPI, HTTPException  # noqa: E402
from fastapi.responses import JSONResponse  # noqa: E402

from service.engine import Engine, Refused, Unavailable, MARKET  # noqa: E402
from service.schemas import (  # noqa: E402
    ForecastPoint, ForecastRequest, ForecastResponse, FraudResponse,
    HealthResponse, ModelHealth, PredictRequest, PriceResponse,
)

logging.basicConfig(level=logging.INFO,
                    format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("rentradar.inference")

engine = Engine()


@asynccontextmanager
async def lifespan(app: FastAPI):
    log.info("loading artefacts")
    engine.load()
    for name, state in engine.health().items():
        if state["loaded"]:
            log.info("  %-6s loaded", name)
        else:
            log.warning("  %-6s NOT loaded: %s", name, state["detail"])
    yield
    log.info("shutting down")


app = FastAPI(
    title="RentRadar inference",
    version="1.0.0",
    summary="Serves the price estimator, fraud autoencoder and rent index "
            "level to the RentRadar gateway.",
    lifespan=lifespan,
)


@app.exception_handler(Refused)
async def refused_handler(_request, exc: Refused):
    # 422 is the status the Java client treats as a deliberate refusal and
    # carries through with its detail intact, rather than flattening.
    return JSONResponse(status_code=422, content={"detail": exc.detail})


@app.exception_handler(Unavailable)
async def unavailable_handler(_request, exc: Unavailable):
    return JSONResponse(status_code=503, content={"detail": str(exc)})


def _round(value: float, places: int = 2) -> float:
    return float(round(value, places))


@app.post("/v1/price/estimate", response_model=PriceResponse)
def estimate_price(req: PredictRequest) -> PriceResponse:
    ran = engine.resolve(req.model, "PRICE",
                         engine.price_bundle is not None, engine.price_detail)
    predicted, lower, upper = engine.price(req.features, ran)
    return PriceResponse(
        predicted=_round(predicted),
        lower=_round(lower),
        upper=_round(upper),
        currency=req.features.currency or "GHS",
        model_ran=ran,
    )


@app.post("/v1/fraud/assess", response_model=FraudResponse)
def assess_fraud(req: PredictRequest) -> FraudResponse:
    ran = engine.resolve(req.model, "FRAUD",
                         engine.fraud_model is not None, engine.fraud_detail)
    score, level, error, factor = engine.fraud(req.features, ran)
    return FraudResponse(
        risk_score=_round(score, 4),
        risk_level=level,
        reconstruction_error=_round(error, 6),
        principal_factor=factor,
        model_ran=ran,
    )


@app.post("/v1/forecast/trend", response_model=ForecastResponse)
def forecast_trend(req: ForecastRequest) -> ForecastResponse:
    if req.horizon_days < 1 or req.horizon_days > 365:
        raise Refused(f"horizonDays must be between 1 and 365, got {req.horizon_days}")
    ran = engine.resolve(req.model, "TREND",
                         engine.forecast_data is not None, engine.forecast_detail)
    points, direction, mae = engine.forecast(req.horizon_days, ran)
    return ForecastResponse(
        series=[ForecastPoint(day=d, value=_round(v), lower=_round(lo),
                              upper=_round(hi)) for d, v, lo, hi in points],
        direction=direction,
        mean_absolute_error=_round(mae),
        model_ran=ran,
    )


@app.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    state = engine.health()
    models = {
        name: ModelHealth(
            loaded=s["loaded"],
            version=engine.registry.get(name.upper() if name != "trend" else "TREND"),
            stage=engine.stage if s["loaded"] else None,
            detail=s["detail"],
        )
        for name, s in state.items()
    }
    ok = all(s["loaded"] for s in state.values())
    body = HealthResponse(
        status="UP" if ok else "DEGRADED",
        spec_version=str(engine.spec.get("version") or engine.spec.get("spec_version") or ""),
        market_id=MARKET,
        models=models,
    )
    if not ok:
        # 503 so the gateway's own health endpoint can report the dependency
        # down rather than claiming everything is fine.
        raise HTTPException(status_code=503, detail=body.model_dump(by_alias=True))
    return body


@app.post("/v1/admin/reload", status_code=202)
def reload_models() -> dict:
    """Called by the gateway's POST /api/v1/admin/models/reload."""
    log.info("reload requested")
    engine.load()
    return {"status": "reloaded", "models": engine.health(),
            "versions": engine.registry}