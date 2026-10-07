"""Ghana Property Centre collector.

Works from the published sitemap rather than search pagination. Their robots.txt
permits everything except *report/create*, and they advertise the sitemap
themselves, so this is the route the site is asking crawlers to take.

    python -m rentradar.collect.gpc urls                 # refresh the URL list
    python -m rentradar.collect.gpc plan                 # what would be crawled
    python -m rentradar.collect.gpc crawl --city Accra --limit 600
    python -m rentradar.collect.gpc parse-file page.html # offline check

Extraction order, strongest signal first:
  1. RealEstateListing JSON-LD: price, currency, datePosted. A typed contract.
  2. The "Property details" table: bedrooms, bathrooms, toilets, parking, type.
  3. The URL path: transaction, category, region, area, suburb, id, bedrooms.
  4. The DOM price block, for the rent period only.

Nothing is read from the "similar properties" carousel, which carries other
listings' bedroom counts and areas and is the obvious way to silently poison
this corpus.
"""

from __future__ import annotations

import argparse
import json
import logging
import re
import sys
from collections import Counter
from datetime import date
from pathlib import Path
from typing import Iterable, Optional

from bs4 import BeautifulSoup

from .fetch import CollectionLog, DeclinedByServer, PoliteFetcher, RobotsDisallowed, now_iso
from .schema import Record

log = logging.getLogger("rentradar.gpc")

SOURCE = "ghana_property_centre"
BASE = "https://ghanapropertycentre.com"
SITEMAP_INDEX = f"{BASE}/sitemaps/index.xml"

GH_REGIONS = {
    "greater-accra", "ashanti", "western", "western-north", "central", "eastern",
    "volta", "oti", "northern", "north-east", "savannah", "upper-east",
    "upper-west", "bono", "bono-east", "ahafo",
}

# Region plus area decides the city. Measured 3 Oct 2026: of 4,035 rentals,
# 3,962 are Greater Accra and 53 Ashanti. Western, Central and Northern have
# none at all, so Takoradi, Cape Coast and Tamale cannot come from this source.
CITY_BY_AREA = {
    ("greater-accra", "tema-metropolitan"): "Tema",
    ("ashanti", "kumasi-metropolitan"): "Kumasi",
    ("ashanti", "obuasi-municipal"): "Obuasi",
    ("western", "sekondi-takoradi-metropolitan"): "Sekondi-Takoradi",
    ("central", "cape-coast-metropolitan"): "Cape Coast",
    ("northern", "tamale-metropolitan"): "Tamale",
}
CITY_BY_REGION = {
    "greater-accra": "Accra",
    "ashanti": "Kumasi",
    "western": "Sekondi-Takoradi",
    "central": "Cape Coast",
    "northern": "Tamale",
}

# Short lets are priced per night and are a different market. Commercial is not
# residential rent. Both are excluded by path rather than discovered later in
# the data, where they would quietly widen every price distribution.
EXCLUDED_CATEGORY_ROOTS = {
    "short-let", "commercial", "land", "event-centre-venue",
    "industrial", "hotel-guest-house",
}

DEFAULT_URLS_FILE = "data/interim/gpc_listing_urls.txt"
DEFAULT_RECORDS_DIR = "data/records"
DEFAULT_ARCHIVE_DIR = "data/archive"


# --------------------------------------------------------------------- URLs

def fetch_url_list(fetcher: PoliteFetcher, out_path: Path) -> list[str]:
    """Pull the sitemap index, then the listings sitemap. Two requests."""
    index = fetcher.get(SITEMAP_INDEX, archive=False)
    subs = re.findall(r"<loc>\s*([^<]+?)\s*</loc>", index)
    listings = next((s for s in subs if "listing" in s.lower()), None)
    if not listings:
        raise RuntimeError(f"no listings sitemap in index, found: {subs}")

    body = fetcher.get(listings, archive=False)
    urls = [u.strip() for u in body.splitlines() if u.strip().startswith("http")]
    if not urls:
        urls = re.findall(r"<loc>\s*([^<]+?)\s*</loc>", body)

    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text("\n".join(urls), encoding="utf-8")
    log.info("%s URLs written to %s", f"{len(urls):,}", out_path)
    return urls


def parse_url(url: str) -> Optional[dict]:
    """Decompose a listing URL. Returns None when it is not a rental we want.

    Shape: /<transaction>/<category>[/<subcategory>]/<region>/<area>[/<suburb>]/<id>-<slug>
    The number of segments varies, so the region is found by name rather than
    by position.
    """
    if not url.startswith(BASE + "/"):
        return None
    segs = url[len(BASE) + 1:].strip("/").split("/")
    if len(segs) < 4:
        return None

    transaction, *rest = segs
    if transaction != "for-rent":
        return None

    body, tail = rest[:-1], rest[-1]
    region_at = next((i for i, s in enumerate(body) if s in GH_REGIONS), None)
    if region_at is None:
        return None

    category = body[:region_at]
    if not category or category[0] in EXCLUDED_CATEGORY_ROOTS:
        return None

    region = body[region_at]
    place = body[region_at + 1:]
    area = place[0] if place else None
    suburb = place[1] if len(place) > 1 else None

    city = CITY_BY_AREA.get((region, area)) or CITY_BY_REGION.get(region)
    if not city:
        return None

    m = re.match(r"(\d+)-(.*)$", tail)
    if not m:
        return None
    listing_id, slug = m.group(1), m.group(2)

    return {
        "url": url,
        "listing_id": listing_id,
        "slug": slug,
        "category": "/".join(category),
        "region": region,
        "area": area,
        "suburb": suburb,
        "city": city,
        "locality": (suburb or area or "").replace("-", " ") or None,
    }


def load_url_list(path: Path) -> list[str]:
    if not path.exists():
        raise SystemExit(f"{path} not found. Run the 'urls' command first.")
    return [u.strip() for u in path.read_text(encoding="utf-8").splitlines() if u.strip()]


# ----------------------------------------------------------------- the page

def _details_table(soup: BeautifulSoup) -> dict[str, str]:
    """The "Property details" key/value block.

    Read by structure, a row being a div holding exactly two spans, and scoped
    to the section under that heading so the similar-properties carousel can
    never contribute a value. Whatever keys GPC publishes come through, so
    listings that do carry a plot size are picked up without this needing to
    predict the label.
    """
    heading = soup.find(["h2", "h3"], string=re.compile(r"property\s+details", re.I))
    if not heading:
        return {}
    section = heading.find_parent("section") or heading.parent

    out: dict[str, str] = {}
    for div in section.find_all("div"):
        spans = div.find_all("span", recursive=False)
        if len(spans) != 2:
            continue
        key = " ".join(spans[0].get_text().split())
        val = " ".join(spans[1].get_text().split())
        if key and val and len(key) < 40:
            out[key.lower()] = val
    return out


def _amenities(soup: BeautifulSoup) -> list[str]:
    heading = soup.find(["h2", "h3"], string=re.compile(r"features\s+and\s+amenities", re.I))
    if not heading:
        return []
    section = heading.find_parent("section") or heading.parent
    items = []
    for sp in section.find_all("span"):
        t = " ".join(sp.get_text().split())
        # Group headers ("Interior & Finishing") and stray long text are not
        # amenities. Everything real here is a short noun phrase.
        if t and 2 < len(t) < 40 and "&" not in t and t.lower() != "features and amenities":
            items.append(t)
    seen, out = set(), []
    for t in items:
        if t.lower() not in seen:
            seen.add(t.lower())
            out.append(t)
    return out


def _json_ld(soup: BeautifulSoup) -> dict:
    for tag in soup.find_all("script", type="application/ld+json"):
        try:
            data = json.loads(tag.string or tag.get_text() or "")
        except (json.JSONDecodeError, TypeError):
            continue
        for item in (data if isinstance(data, list) else [data]):
            if isinstance(item, dict) and item.get("@type") == "RealEstateListing":
                return item
    return {}


_PERIOD_WORDS = re.compile(
    r"per\s+(night|day|week|month|quarter|year|annum)|/\s*(night|day|week|month|year)",
    re.I,
)


def _rent_period_text(soup: BeautifulSoup, ld: dict) -> tuple[Optional[str], str]:
    """Find the words stating the period, and say where they came from.

    The period is nowhere in the structured data, only in prose, so this looks
    in the title first (most consistently formatted), then the price block,
    then the description.
    """
    og = soup.find("meta", property="og:title")
    for where, text in (
        ("og:title", og.get("content") if og else ""),
        ("description", ld.get("description", "")),
    ):
        if text:
            m = _PERIOD_WORDS.search(text)
            if m:
                return m.group(0), where
    return None, "none"


def _agent_phone(soup: BeautifulSoup) -> Optional[str]:
    """First listed number, stored only as a hash. Repeated numbers across many
    listings are a fraud signal; the number itself is nobody's business."""
    a = soup.find("a", href=re.compile(r"^tel:"))
    return a["href"].split(":", 1)[1] if a else None


def _int(value: Optional[str]) -> Optional[int]:
    if not value:
        return None
    m = re.search(r"\d+", value)
    return int(m.group(0)) if m else None


def extract(html: str, meta: dict) -> Record:
    soup = BeautifulSoup(html, "lxml")
    ld = _json_ld(soup)
    details = _details_table(soup)
    flags_extra: list[str] = []

    offers = ld.get("offers") or {}
    amount = offers.get("price")
    currency = (offers.get("priceCurrency") or "").upper() or None
    try:
        amount = float(amount) if amount is not None else None
    except (TypeError, ValueError):
        amount = None

    if amount is None:
        flags_extra.append("price_absent_from_jsonld")
    if currency is None:
        flags_extra.append("currency_absent_from_jsonld")

    period_text, period_src = _rent_period_text(soup, ld)
    if period_text is None:
        flags_extra.append("rent_period_not_stated_on_page")
    elif re.search(r"night", period_text, re.I):
        flags_extra.append("short_let_priced_per_night")

    # Built rather than scraped. The page shows both the asking price and GPC's
    # own cedi conversion of it; handing the raw block to the currency detector
    # would let the converted figure decide the currency of the real one.
    price_text = " ".join(x for x in (currency, str(amount) if amount is not None else None,
                                      period_text) if x)

    bedrooms = _int(details.get("bedrooms"))
    if bedrooms is None:
        m = re.search(r"(\d+)[\s-]*bed", meta.get("slug", ""), re.I)
        if m:
            bedrooms = int(m.group(1))
            flags_extra.append("bedrooms_from_url_slug")

    # Only the title asserts furnishing. Absence means unknown, never
    # unfurnished: treating unknown as unfurnished mixes two populations.
    name = ld.get("name") or ""
    haystack = f"{name} {meta.get('slug', '')}"
    furnished = True if re.search(r"furnish", haystack, re.I) else None
    if furnished is None:
        flags_extra.append("furnished_unknown")

    area_text = next(
        (v for k, v in details.items() if re.search(r"size|area|sqm|plot", k)), None
    )

    rec = Record.build(
        source=SOURCE,
        source_record_id=meta["listing_id"],
        city=meta["city"],
        locality=meta.get("locality"),
        bedrooms=bedrooms,
        bathrooms=_int(details.get("bathrooms")),
        furnished=furnished,
        area_text=area_text,
        price_text=price_text,
        price_amount=amount,
        posted_date=ld.get("datePosted"),
        listing_url=meta["url"],
        agent_phone=_agent_phone(soup),
        property_type=details.get("property type"),
        toilets=_int(details.get("toilets")),
        parking_spaces=_int(details.get("parking spaces")),
        amenities=_amenities(soup),
    )
    rec.cleaning_flags.extend(flags_extra)
    if period_src != "none":
        rec.cleaning_flags.append(f"rent_period_from_{period_src.replace(':', '_')}")
    return rec


# ------------------------------------------------------------------ commands

def _seen_ids(path: Path) -> set[str]:
    """Already-collected IDs, so an interrupted run resumes instead of
    restarting. A 600 listing crawl is fifty minutes and will get interrupted."""
    if not path.exists():
        return set()
    out = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            try:
                out.add(json.loads(line)["source_record_id"])
            except (json.JSONDecodeError, KeyError):
                continue
    return out


def select(urls: Iterable[str], city: Optional[str]) -> list[dict]:
    rows = [m for m in (parse_url(u) for u in urls) if m]
    if city:
        rows = [m for m in rows if m["city"].lower() == city.lower()]
    return rows


def cmd_plan(args) -> int:
    rows = select(load_url_list(Path(args.urls)), args.city)
    print(f"{len(rows):,} rental listings match after excluding short lets and commercial\n")
    for label, key in (("city", "city"), ("category", "category")):
        print(f"-- by {label} --")
        for k, n in Counter(r[key] for r in rows).most_common(12):
            print(f"  {n:>6,}  {k}")
        print()
    hours = len(rows) * 5 / 3600
    print(f"At one request every 5 seconds this is {hours:.1f} hours for the full set.")
    return 0


def cmd_crawl(args) -> int:
    records_dir = Path(args.records)
    records_dir.mkdir(parents=True, exist_ok=True)
    out_path = records_dir / f"{SOURCE}_{(args.city or 'all').lower().replace(' ', '-')}.jsonl"

    rows = select(load_url_list(Path(args.urls)), args.city)
    done = _seen_ids(out_path)
    todo = [r for r in rows if r["listing_id"] not in done][: args.limit]

    print(f"{len(rows):,} matched, {len(done):,} already collected, {len(todo):,} to fetch")
    if not todo:
        return 0

    fetcher = PoliteFetcher(Path(args.archive))
    clog = CollectionLog(Path(args.records).parent / "collection_log.csv")
    started = now_iso()
    kept = flagged = failed = 0
    note = ""

    try:
        with out_path.open("a", encoding="utf-8") as fh:
            for i, meta in enumerate(todo, 1):
                try:
                    html = fetcher.get(meta["url"])
                except RobotsDisallowed as exc:
                    log.warning("robots disallows %s", exc)
                    failed += 1
                    continue
                except DeclinedByServer as exc:
                    note = f"declined after {i - 1}: {exc}"
                    log.error("%s", note)
                    break
                except RuntimeError as exc:
                    log.warning("failed %s: %s", meta["listing_id"], exc)
                    failed += 1
                    continue

                try:
                    rec = extract(html, meta)
                except Exception as exc:  # noqa: BLE001
                    log.warning("parse failed %s: %s", meta["listing_id"], exc)
                    failed += 1
                    continue

                fh.write(rec.to_json() + "\n")
                fh.flush()
                kept += 1
                if rec.cleaning_flags:
                    flagged += 1
                if i % 20 == 0:
                    print(f"  {i}/{len(todo)}  kept={kept} flagged={flagged} failed={failed}")
    except KeyboardInterrupt:
        note = "interrupted by user"
        print("\nstopping, records written so far are kept")

    clog.write(
        started_at=started, finished_at=now_iso(), source=SOURCE,
        city=args.city or "all", pages_fetched=fetcher.stats.requests_made,
        records_attempted=len(todo), records_kept=kept,
        records_flagged=flagged, failures=failed, notes=note,
    )
    print(f"\n{kept:,} records appended to {out_path}")
    print(f"{flagged:,} carry at least one cleaning flag, {failed} failed")
    return 0


def cmd_urls(args) -> int:
    fetcher = PoliteFetcher(Path(args.archive))
    urls = fetch_url_list(fetcher, Path(args.urls))
    print(f"{len(urls):,} URLs saved to {args.urls}")
    print(f"{len(select(urls, None)):,} are rentals we would collect")
    return 0


def cmd_parse_file(args) -> int:
    """Parse a saved page without any network access, to check the extractor."""
    html = Path(args.path).read_text(encoding="utf-8")
    soup = BeautifulSoup(html, "lxml")
    canonical = (soup.find("meta", property="og:url") or {}).get("content", "")
    meta = parse_url(canonical) or {
        "url": canonical, "listing_id": "0", "slug": "", "city": "Accra", "locality": None,
    }
    print(json.dumps(json.loads(extract(html, meta).to_json()), indent=2))
    return 0


def main(argv=None) -> int:
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    p = argparse.ArgumentParser(description="Ghana Property Centre collector")
    p.add_argument("--urls", default=DEFAULT_URLS_FILE)
    p.add_argument("--records", default=DEFAULT_RECORDS_DIR)
    p.add_argument("--archive", default=DEFAULT_ARCHIVE_DIR)
    sub = p.add_subparsers(dest="command", required=True)

    sub.add_parser("urls").set_defaults(func=cmd_urls)

    pl = sub.add_parser("plan")
    pl.add_argument("--city")
    pl.set_defaults(func=cmd_plan)

    cr = sub.add_parser("crawl")
    cr.add_argument("--city")
    cr.add_argument("--limit", type=int, default=400)
    cr.set_defaults(func=cmd_crawl)

    pf = sub.add_parser("parse-file")
    pf.add_argument("path")
    pf.set_defaults(func=cmd_parse_file)

    args = p.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
