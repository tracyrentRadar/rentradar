"""
The inference engine. Loads the exported artefacts once at startup and scores
requests against them.

Two rules it enforces, both inherited from the Java side rather than invented
here.

  A version beginning "stub" means no artefact file is expected. The service
  answers with plausible fixed numbers and says isStub, which is how the
  gateway can run before any model exists.

  Any other version must match a loaded artefact exactly, same market, type,
  version and stage. A mismatch is refused with 422 rather than answered,
  because a number from the wrong model is worse than no number.

Why training data is loaded as well as models. The fraud autoencoder scores a
listing against its eight nearest comparables drawn from the training fold, so
the comparables are part of the model. They are rebuilt here from matrix.npz
and the saved scalers rather than exported separately, so they cannot drift out
of step with what training used.
"""

from __future__ import annotations

import json
import math
from pathlib import Path
from typing import Optional

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
FEATURES = ROOT / "data" / "features"
ARTEFACTS = ROOT / "artifacts" / "models"

MARKET = "GH"
CHANNELS = ["log_rent", "bedrooms", "bathrooms", "toilets",
            "amenity_count", "distance"]
COLUMN_FOR = {
    "bedrooms": "num__bedrooms",
    "bathrooms": "num__bathrooms",
    "toilets": "num__toilets",
    "amenity_count": "num__amenity_count",
}

# Values the stub answers with. Plausible, obviously not measured, and every
# response carries isStub so nothing downstream can mistake them for real.
STUB_PRICE = 4500.0
STUB_RISK = 0.12


class Refused(Exception):
    """A deliberate refusal. Surfaces as 422, which the Java client expects."""

    def __init__(self, detail: str):
        super().__init__(detail)
        self.detail = detail


class Unavailable(Exception):
    """The artefact should be here and is not. Surfaces as 503."""


def _is_stub(version: str) -> bool:
    return (version or "").lower().startswith("stub")


class Engine:
    def __init__(self) -> None:
        self.spec: dict = {}
        self.columns: list[str] = []
        self.encode_one = None

        self.price_bundle: Optional[dict] = None
        self.price_detail: Optional[str] = None

        self.forecast_data: Optional[dict] = None
        self.forecast_detail: Optional[str] = None

        self.fraud_model = None
        self.fraud_detail: Optional[str] = None
        self.fraud_nn = None
        self.fraud_base_tr: Optional[np.ndarray] = None
        self.fraud_feature_scaler = None
        self.fraud_channel_scaler = None
        self.fraud_d_mu = 0.0
        self.fraud_d_sd = 1.0
        self.fraud_order: Optional[np.ndarray] = None
        self.fraud_high = 0.0
        self.fraud_medium = 0.0
        self.fraud_k = 8
        self.channel_cols: dict[str, int] = {}

        # What this service actually holds, read from the export registry.
        # Echoing back whatever was asked for would make the gateway's
        # same-artefact check vacuous, so the versions here are the ones
        # reported and a request for anything else is refused.
        self.registry: dict[str, str] = {}
        self.stage = "CHAMPION"

    # ------------------------------------------------------------- loading

    def load(self) -> None:
        self._load_registry()
        self._load_spec()
        self._load_price()
        self._load_forecast()
        self._load_fraud()

    def _load_registry(self) -> None:
        path = ARTEFACTS / "registry.json"
        if not path.exists():
            return
        try:
            reg = json.loads(path.read_text("utf-8"))
        except Exception:
            return
        models = reg.get("models", {})
        for wire, key in (("PRICE", "price"), ("FRAUD", "fraud"), ("TREND", "forecast")):
            version = (models.get(key) or {}).get("version")
            if version:
                self.registry[wire] = str(version)

    def _load_spec(self) -> None:
        from rentradar.features.build import encode_one  # same code as training

        self.encode_one = encode_one
        self.spec = json.loads((FEATURES / "feature_spec.json").read_text("utf-8"))
        self.columns = list(self.spec.get("columns", []))
        for name, col in COLUMN_FOR.items():
            if col in self.columns:
                self.channel_cols[name] = self.columns.index(col)

    def _load_price(self) -> None:
        import joblib

        path = ARTEFACTS / "price_gb.joblib"
        if not path.exists():
            self.price_detail = f"{path.name} missing, run rentradar.models.export"
            return
        try:
            self.price_bundle = joblib.load(path)
        except Exception as exc:
            self.price_detail = f"failed to load: {exc}"
            return

        # Startup self test. Encoding one probe record through the training
        # encoder must produce exactly the width the model was fitted on. If it
        # does not, the feature spec and the model are from different runs and
        # every prediction would be quietly wrong.
        probe = {
            "bedrooms": 2, "bathrooms": 2, "toilets": 2,
            "property_type": "apartment", "locality": "east legon",
            "furnished": None, "amenities": [],
        }
        try:
            width = len(self.encode_one(probe, self.spec))
        except Exception as exc:
            self.price_detail = f"encode_one rejected the probe record: {exc}"
            self.price_bundle = None
            return

        expected = int(self.price_bundle.get("n_features", width))
        if width != expected:
            self.price_detail = (f"feature width {width} but the model expects "
                                 f"{expected}; spec and model are out of step")
            self.price_bundle = None
            return
        self.price_detail = None

    def _load_forecast(self) -> None:
        path = ARTEFACTS / "forecast_moving_average.json"
        if not path.exists():
            self.forecast_detail = f"{path.name} missing, run rentradar.models.export"
            return
        try:
            self.forecast_data = json.loads(path.read_text("utf-8"))
            self.forecast_detail = None
        except Exception as exc:
            self.forecast_detail = f"failed to load: {exc}"

    def _load_fraud(self) -> None:
        import joblib
        from sklearn.neighbors import NearestNeighbors

        model_path = ARTEFACTS / "fraud_lstm.keras"
        scaler_path = ARTEFACTS / "fraud_lstm_scalers.joblib"
        results_path = ARTEFACTS / "fraud_lstm_results.json"
        scores_path = ARTEFACTS / "fraud_lstm_scores.npz"

        for p in (model_path, scaler_path, results_path, scores_path):
            if not p.exists():
                self.fraud_detail = (f"{p.name} missing, run "
                                     f"rentradar.models.fraud_lstm fit")
                return

        try:
            from tensorflow import keras
            self.fraud_model = keras.models.load_model(model_path, compile=False)

            s = joblib.load(scaler_path)
            self.fraud_feature_scaler = s["feature_scaler"]
            self.fraud_channel_scaler = s["channel_scaler"]
            self.fraud_d_mu = float(s["distance_mu"])
            self.fraud_d_sd = float(s["distance_sd"]) or 1.0

            res = json.loads(results_path.read_text("utf-8"))
            self.fraud_high = float(res["threshold_high"])
            self.fraud_medium = float(res["threshold_medium"])
            self.fraud_k = int(res.get("k_comparables", 8))

            # Rebuild the comparable set exactly as training did.
            data = np.load(FEATURES / "matrix.npz")
            X, y = data["X"], data["y"]
            splits = json.loads((FEATURES / "splits.json").read_text("utf-8"))
            tr = np.asarray(splits["fraud"]["indices"]["train"], dtype="int64")

            base = self._channel_matrix(X, y)
            self.fraud_base_tr = self.fraud_channel_scaler.transform(base[tr])
            s_tr = self.fraud_feature_scaler.transform(X[tr]).astype(np.float32)

            self.fraud_nn = NearestNeighbors(n_neighbors=self.fraud_k).fit(s_tr)

            errors = np.load(scores_path, allow_pickle=True)["error"]
            self.fraud_order = np.sort(errors[tr])

            self.fraud_detail = None
        except Exception as exc:
            self.fraud_model = None
            self.fraud_detail = f"failed to load: {type(exc).__name__}: {exc}"

    def _channel_matrix(self, X: np.ndarray, y: np.ndarray) -> np.ndarray:
        """The five base channels, same order and source as training."""
        def col(name: str) -> np.ndarray:
            i = self.channel_cols.get(name)
            return X[:, i] if i is not None else np.zeros(X.shape[0])

        return np.stack(
            [y, col("bedrooms"), col("bathrooms"), col("toilets"),
             col("amenity_count")], axis=1
        ).astype(np.float64)

    # ------------------------------------------------------- artefact rules

    def resolve(self, ref, model_type: str, loaded: bool, detail: Optional[str]):
        """
        Decide whether this request is answerable, and with what. Returns the
        reference to echo back, or raises.
        """
        if ref.market_id != MARKET:
            raise Refused(
                f"this service holds models for market {MARKET} only, "
                f"asked for {ref.market_id}")
        if ref.model_type.upper() != model_type:
            raise Refused(
                f"artefact says modelType {ref.model_type} but this endpoint "
                f"serves {model_type}")

        if _is_stub(ref.version):
            return ref.model_copy(update={"is_stub": True})

        if not loaded:
            raise Unavailable(detail or f"{model_type} artefact not loaded")

        held = self.registry.get(model_type)
        if held is None:
            raise Unavailable(
                f"no {model_type} version registered; run rentradar.models.export")
        if ref.version != held:
            raise Refused(
                f"asked for {model_type} version {ref.version} but this service "
                f"holds {held}; refusing rather than answering with the wrong model")
        if (ref.stage or "").upper() != self.stage:
            raise Refused(
                f"asked for stage {ref.stage} but this service serves "
                f"{self.stage} only")

        return ref.model_copy(update={"is_stub": False, "version": held,
                                      "stage": self.stage})

    # ------------------------------------------------------------ encoding

    def to_record(self, f) -> dict:
        """
        FeatureVector on the wire to the record shape the training encoder
        expects. furnished is the interesting one: the wire has three states
        and training has two, because Ghana Property Centre never records an
        explicit false. True becomes yes, and both false and null become
        not_stated, since there is no trained population behind an explicit
        false to map onto.
        """
        return {
            "bedrooms": f.bedrooms,
            "bathrooms": f.bathrooms,
            "toilets": f.toilets,
            "property_type": f.property_type,
            "locality": f.locality_key,
            "furnished": True if f.furnished is True else None,
            "amenities": list(f.amenities or []),
        }

    def encode(self, f) -> np.ndarray:
        row = self.encode_one(self.to_record(f), self.spec)
        return np.asarray(row, dtype="float64").reshape(1, -1)

    # --------------------------------------------------------------- price

    def price(self, f, ref):
        if ref.is_stub:
            return STUB_PRICE, STUB_PRICE * 0.85, STUB_PRICE * 1.15

        x = self.encode(f)
        log_pred = float(self.price_bundle["model"].predict(x)[0])
        lo = float(self.price_bundle["interval_log_low"])
        hi = float(self.price_bundle["interval_log_high"])

        # The target is log1p rent, so cedis come back with expm1. The interval
        # is applied in log space and converted, not multiplied in cedis.
        return (math.expm1(log_pred),
                max(math.expm1(log_pred + lo), 0.0),
                math.expm1(log_pred + hi))

    # --------------------------------------------------------------- fraud

    def fraud(self, f, ref):
        if ref.is_stub:
            return STUB_RISK, "LOW", 0.0, "log_rent"

        if f.listed_price is None or f.listed_price <= 0:
            raise Refused("fraud assessment needs listedPrice; the model asks "
                          "whether the asking price fits its comparables, and "
                          "with no asking price there is no question to answer")

        x = self.encode(f)
        subject_base = np.array([[
            math.log1p(float(f.listed_price)),
            x[0, self.channel_cols["bedrooms"]] if "bedrooms" in self.channel_cols else 0.0,
            x[0, self.channel_cols["bathrooms"]] if "bathrooms" in self.channel_cols else 0.0,
            x[0, self.channel_cols["toilets"]] if "toilets" in self.channel_cols else 0.0,
            x[0, self.channel_cols["amenity_count"]] if "amenity_count" in self.channel_cols else 0.0,
        ]], dtype="float64")

        base_s = self.fraud_channel_scaler.transform(subject_base)
        scaled = self.fraud_feature_scaler.transform(x).astype(np.float32)

        dist, idx = self.fraud_nn.kneighbors(scaled)
        idx, dist = idx[:, ::-1], dist[:, ::-1]          # closest last

        comps = np.concatenate(
            [self.fraud_base_tr[idx], dist[:, :, None]], axis=2)
        subject = np.concatenate([base_s, np.zeros((1, 1))], axis=1)
        seq = np.concatenate([comps, subject[:, None, :]], axis=1)
        seq[:, :, -1] = (seq[:, :, -1] - self.fraud_d_mu) / self.fraud_d_sd
        seq = seq.astype(np.float32)

        recon = self.fraud_model(seq, training=False).numpy()
        per_channel = (recon[:, -1, :] - seq[:, -1, :]) ** 2

        # One sided on purpose. A scam baits with a price below what the
        # neighbourhood supports; a listing above it is an overpricing problem
        # the estimate already answers.
        gap = float(recon[0, -1, 0] - seq[0, -1, 0])
        error = max(gap, 0.0) ** 2

        # Percentile rank against the training error distribution, not min-max,
        # so one broken listing cannot rescale everyone else's score.
        rank = float(np.searchsorted(self.fraud_order, error, side="right")
                     / len(self.fraud_order))
        rank = min(max(rank, 0.0), 1.0)

        if error >= self.fraud_high:
            band = "HIGH"
        elif error >= self.fraud_medium:
            band = "MEDIUM"
        else:
            band = "LOW"

        factor = CHANNELS[int(np.argmax(per_channel[0]))]
        return rank, band, error, factor

    # ------------------------------------------------------------ forecast

    def forecast(self, horizon_days: int, ref):
        if ref.is_stub:
            level = STUB_PRICE
            return ([(d, level, level * 0.9, level * 1.1)
                     for d in range(1, horizon_days + 1)], "STABLE", 0.0)

        data = self.forecast_data
        level = float(data["level_ghs"])
        mape = data.get("mape_percent")
        mae = float(data.get("mae_ghs") or 0.0)
        spread = level * (float(mape) / 100.0) if mape else mae

        # Direction is measured, not guessed: the last window against the one
        # before it, called flat unless the gap clears the model's own error.
        series = [float(r["index_ghs"]) for r in data.get("series", [])]
        window = int(data.get("window_months", 6))
        direction = "STABLE"
        if len(series) >= window * 2:
            recent = float(np.mean(series[-window:]))
            prior = float(np.mean(series[-2 * window:-window]))
            move = recent - prior
            if abs(move) > mae:
                direction = "RISING" if move > 0 else "FALLING"

        # A flat line, deliberately. The ADF test finds the index stationary,
        # so day to day movement would be invented rather than forecast.
        points = [(d, level, max(level - spread, 0.0), level + spread)
                  for d in range(1, max(horizon_days, 1) + 1)]
        return points, direction, mae

    # -------------------------------------------------------------- health

    def health(self) -> dict:
        return {
            "price": {"loaded": self.price_bundle is not None,
                      "detail": self.price_detail,
                      "version": (self.price_bundle or {}).get("spec_version")},
            "fraud": {"loaded": self.fraud_model is not None,
                      "detail": self.fraud_detail},
            "trend": {"loaded": self.forecast_data is not None,
                      "detail": self.forecast_detail},
        }