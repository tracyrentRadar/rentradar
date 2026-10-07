"""The one record shape every source writes into, and the normalisation rules.

Every rule here is recorded on the record itself via cleaning_flags. Nothing is
applied silently, because a cleaning decision you cannot reconstruct is a
cleaning decision you cannot defend.
"""

from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass, field, asdict
from datetime import datetime, timezone
from typing import Optional

# How many months each quoted period covers. Monthly rent is amount divided by
# this. Ghanaian listings quote all of these interchangeably and treating them
# as one number is the fastest way to corrupt the corpus.
MONTHS_IN_PERIOD = {
    "day": 12 / 365,
    "week": 12 / 52,
    "month": 1.0,
    "quarter": 3.0,
    "half_year": 6.0,
    "year": 12.0,
}

SQFT_TO_SQM = 0.092903

# Flagged for manual review rather than dropped. Deciding that an outlier is
# noise rather than a genuine luxury listing is a judgement, and it should be a
# recorded one.
RENT_FLOOR_GHS = 150
RENT_CEILING_GHS = 100_000

_PERIOD_PATTERNS = [
    (r"\bper\s*day\b|/\s*day\b|daily", "day"),
    (r"\bper\s*week\b|/\s*week\b|weekly|/\s*wk\b", "week"),
    (r"\bper\s*month\b|/\s*month\b|monthly|/\s*mo\b|p\.?m\.?\b", "month"),
    (r"\bper\s*quarter\b|quarterly|3\s*months?\b", "quarter"),
    (r"half[\s-]*year|6\s*months?\b|bi[\s-]*annual", "half_year"),
    (r"\bper\s*(year|annum)\b|/\s*year\b|yearly|annually|12\s*months?\b", "year"),
]

_AREA_PATTERNS = [
    (r"sq\.?\s*m|m²|m2|square\s*met(er|re)", "sqm"),
    (r"sq\.?\s*ft|ft²|ft2|square\s*f(ee|oo)t", "sqft"),
]

_CURRENCY_PATTERNS = [
    (r"gh[₵c]|ghs|cedi", "GHS"),
    (r"\bus\$|usd|\$", "USD"),
    (r"€|eur", "EUR"),
    (r"£|gbp", "GBP"),
]


def detect_period(text: str, default: str = "month") -> tuple[str, bool]:
    """Return (period, was_explicit). Defaulting is itself worth flagging."""
    if not text:
        return default, False
    low = text.lower()
    for pattern, period in _PERIOD_PATTERNS:
        if re.search(pattern, low):
            return period, True
    return default, False


def detect_currency(text: str, default: str = "GHS") -> tuple[str, bool]:
    if not text:
        return default, False
    low = text.lower()
    for pattern, code in _CURRENCY_PATTERNS:
        if re.search(pattern, low):
            return code, True
    return default, False


def detect_area_unit(text: str) -> Optional[str]:
    if not text:
        return None
    low = text.lower()
    for pattern, unit in _AREA_PATTERNS:
        if re.search(pattern, low):
            return unit
    return None


def parse_amount(text: str) -> Optional[float]:
    """Pull the first money-looking number out of a string.

    Handles '1,280', 'GH₵ 4,500/month', '2.5K'. Returns None rather than
    guessing when there is nothing to parse.
    """
    if not text:
        return None
    cleaned = text.replace(",", "")
    k = re.search(r"(\d+(?:\.\d+)?)\s*[kK]\b", cleaned)
    if k:
        return float(k.group(1)) * 1000
    m = re.search(r"(\d+(?:\.\d+)?)", cleaned)
    return float(m.group(1)) if m else None


def to_monthly(amount: float, period: str) -> float:
    months = MONTHS_IN_PERIOD.get(period)
    if not months:
        raise ValueError(f"unknown rent period: {period}")
    return amount / months


def to_sqm(value: float, unit: str) -> float:
    if unit == "sqm":
        return value
    if unit == "sqft":
        return value * SQFT_TO_SQM
    raise ValueError(f"unknown area unit: {unit}")


def url_hash(url: str) -> str:
    """Stored instead of the URL, so records stay traceable without
    republishing anyone's listing."""
    return hashlib.sha256(url.strip().lower().encode("utf-8")).hexdigest()[:32]


def phone_hash(phone: Optional[str]) -> Optional[str]:
    if not phone:
        return None
    digits = re.sub(r"\D", "", phone)
    if len(digits) < 7:
        return None
    return hashlib.sha256(digits.encode("utf-8")).hexdigest()[:32]


def normalise_locality(raw: Optional[str]) -> Optional[str]:
    if not raw:
        return None
    s = re.sub(r"\s+", " ", raw).strip(" ,.-").title()
    return s or None


@dataclass
class Record:
    """One rental listing, normalised. Matches the backend property document.

    Money always carries its currency and area always carries its unit, per the
    binding multi-market rules. The originals are kept beside the normalised
    values so every transformation is reversible.
    """

    source: str
    source_record_id: str
    captured_at: str
    market_id: str
    city: str
    locality: Optional[str]

    bedrooms: Optional[int]
    bathrooms: Optional[int]
    furnished: Optional[bool]

    area_value: Optional[float]
    area_unit: Optional[str]
    area_sqm: Optional[float]

    asking_rent_amount: Optional[float]
    asking_rent_currency: str
    rent_period: str
    rent_monthly_ghs: Optional[float]

    posted_date: Optional[str]
    listing_url_hash: Optional[str]
    agent_phone_hash: Optional[str] = None
    property_type: Optional[str] = None
    toilets: Optional[int] = None
    parking_spaces: Optional[int] = None
    amenities: list[str] = field(default_factory=list)
    cleaning_flags: list[str] = field(default_factory=list)

    @classmethod
    def build(
        cls,
        *,
        source: str,
        source_record_id: str,
        city: str,
        market_id: str = "GH",
        locality: Optional[str] = None,
        bedrooms=None,
        bathrooms=None,
        furnished=None,
        area_text: Optional[str] = None,
        area_value: Optional[float] = None,
        price_text: Optional[str] = None,
        price_amount: Optional[float] = None,
        posted_date: Optional[str] = None,
        listing_url: Optional[str] = None,
        agent_phone: Optional[str] = None,
        property_type: Optional[str] = None,
        toilets: Optional[int] = None,
        parking_spaces: Optional[int] = None,
        amenities: Optional[list[str]] = None,
    ) -> "Record":
        flags: list[str] = []

        period, explicit_period = detect_period(price_text or "")
        if not explicit_period:
            flags.append("rent_period_defaulted_monthly")

        currency, explicit_currency = detect_currency(price_text or "")
        if not explicit_currency:
            flags.append("currency_defaulted_ghs")

        amount = price_amount if price_amount is not None else parse_amount(price_text or "")
        if amount is None:
            flags.append("rent_missing")
            monthly = None
        else:
            monthly_native = to_monthly(amount, period)
            # Binding rule 2: never silently convert currency. Only GHS gets a
            # monthly GHS figure, anything else keeps its own currency and the
            # downstream pipeline decides what to do with it.
            if currency == "GHS":
                monthly = round(monthly_native, 2)
                if monthly < RENT_FLOOR_GHS:
                    flags.append("rent_below_floor_review")
                if monthly > RENT_CEILING_GHS:
                    flags.append("rent_above_ceiling_review")
            else:
                monthly = None
                flags.append(f"rent_not_ghs_{currency.lower()}")
            if period != "month":
                flags.append(f"rent_normalised_from_{period}")

        unit = detect_area_unit(area_text or "")
        value = area_value if area_value is not None else parse_amount(area_text or "")
        if value is None:
            area_sqm = None
            unit = None
            flags.append("area_missing")
        else:
            if unit is None:
                unit = "sqm"
                flags.append("area_unit_defaulted_sqm")
            area_sqm = round(to_sqm(value, unit), 2)
            if unit != "sqm":
                flags.append(f"area_normalised_from_{unit}")

        if bedrooms is None:
            flags.append("bedrooms_missing")

        return cls(
            source=source,
            source_record_id=str(source_record_id),
            captured_at=datetime.now(timezone.utc).isoformat(timespec="seconds"),
            market_id=market_id,
            city=city,
            locality=normalise_locality(locality),
            bedrooms=bedrooms,
            bathrooms=bathrooms,
            furnished=furnished,
            area_value=value,
            area_unit=unit,
            area_sqm=area_sqm,
            asking_rent_amount=amount,
            asking_rent_currency=currency,
            rent_period=period,
            rent_monthly_ghs=monthly,
            posted_date=posted_date,
            listing_url_hash=url_hash(listing_url) if listing_url else None,
            agent_phone_hash=phone_hash(agent_phone),
            property_type=property_type,
            toilets=toilets,
            parking_spaces=parking_spaces,
            amenities=sorted(amenities or []),
            cleaning_flags=flags,
        )

    def dedupe_key(self) -> tuple:
        """Coarse key for the first dedupe pass. The tolerance comparison on
        area and rent happens afterwards, this only buckets candidates."""
        return (
            self.city,
            self.locality,
            self.bedrooms,
            self.bathrooms,
            round(self.area_sqm / 10) if self.area_sqm else None,
            round(self.rent_monthly_ghs / 100) if self.rent_monthly_ghs else None,
        )

    def to_json(self) -> str:
        return json.dumps(asdict(self), ensure_ascii=False)
