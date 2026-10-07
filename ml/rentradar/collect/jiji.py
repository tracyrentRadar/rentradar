"""Jiji Ghana collector, written against the real markup rather than a guess.

Jiji is the second source. It covers the mid and lower market that Ghana
Property Centre almost entirely misses: GPC's corpus is three quarters dollar
denominated with a median around 21,000 cedis a month, which is the expat and
premium end of Accra, not the market a tenant actually shops in.

Three things were checked on the page before any of this was written:

  the price carries its own period    "GH 30,000" sits beside "per month", so
                                      Jiji quotes monthly rent exactly as GPC
                                      does and the two merge without arithmetic
  Property Size is the plot           3,400 sqm on a semi-detached townhouse is
                                      land, not floor area, the same as GPC's
                                      "Plot size". It is captured and flagged,
                                      and it stays out of the model
  there is no posting date            Jiji shows only a coarse badge, "1+ month
                                      on Jiji". Every record is flagged for it,
                                      and Jiji rows cannot feed the time series

Two differences from GPC are recorded on every record rather than smoothed over:
the seller is identified by name because the phone number sits behind a click,
and the absent date. Both are flags, not silent substitutions.

    python -m rentradar.collect.jiji urls --pages 40
    python -m rentradar.collect.jiji crawl --limit 50
    python -m rentradar.collect.jiji parse-file data/raw/raw/<date>/<file>.html
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import asdict
from pathlib import Path
from typing import Iterable, Optional
from urllib.parse import urljoin, urlparse

from .fetch import (CollectionLog, DeclinedByServer, PoliteFetcher,
                    RobotsDisallowed, now_iso)
from .schema import Record, normalise_locality, phone_hash, url_hash

try:
    from bs4 import BeautifulSoup
except ImportError:
    print("beautifulsoup4 is required: py -m pip install beautifulsoup4 lxml")
    raise SystemExit(1)

SOURCE = "jiji"
BASE = "https://jiji.com.gh"
INDEX = f"{BASE}/greater-accra/houses-apartments-for-rent"

# Short lets and holiday rentals are a different product with a different
# price basis, the same exclusion applied to the Ghana Property Centre crawl.
EXCLUDED_PATH_WORDS = {
    "temporary-and-vacation-rentals", "short-let", "houses-apartments-for-sale",
    "land", "commercial", "event-centre", "office",
}

LISTING_PATH = re.compile(r"^/[a-z0-9-]+/houses-apartments-for-rent/[^/]+\.html$")


def soup_of(html: str) -> BeautifulSoup:
    try:
        return BeautifulSoup(html, "lxml")
    except Exception:
        return BeautifulSoup(html, "html.parser")


def text_of(el) -> Optional[str]:
    if el is None:
        return None
    t = el.get_text(" ", strip=True)
    return t or None


# ------------------------------------------------------------------- the url

def parse_url(url: str) -> Optional[dict]:
    """Locality and listing id come free in the path.

    /east-legon/houses-apartments-for-rent/furnished-4bdrm-...-dqxcs0nXdXrF.html
     ^ locality                            ^ slug, ending in the listing id
    """
    p = urlparse(url)
    parts = [s for s in p.path.split("/") if s]
    if len(parts) < 3 or not LISTING_PATH.match(p.path):
        return None
    if any(w in p.path for w in EXCLUDED_PATH_WORDS):
        return None

    locality_slug, _, slug = parts[0], parts[1], parts[2]
    slug = slug[:-5] if slug.endswith(".html") else slug

    # The id is the last hyphen-separated token, a mixed-case opaque string.
    tail = slug.rsplit("-", 1)[-1]
    listing_id = tail if re.fullmatch(r"[A-Za-z0-9]{12,}", tail) else slug[-24:]

    return {
        "url": f"{BASE}{p.path}",
        "source_record_id": listing_id,
        "locality": normalise_locality(locality_slug.replace("-", " ").title()),
        "city": "Accra",
    }


# --------------------------------------------------------------- the listing

def _attributes(s: BeautifulSoup) -> dict[str, str]:
    """The labelled grid: Property Size, Toilets, Furnishing, and the rest."""
    out: dict[str, str] = {}
    for a in s.select("div.b-advert-attribute"):
        k = text_of(a.select_one(".b-advert-attribute__key"))
        v = text_of(a.select_one(".b-advert-attribute__value"))
        if k and v:
            out[k.strip().lower()] = v.strip()
    return out


def _icon_attributes(s: BeautifulSoup) -> dict[str, str]:
    """The header strip: property type, "4 bedrooms", "5 bathrooms"."""
    out: dict[str, str] = {}
    for el in s.select("div.b-advert-icon-attribute"):
        t = text_of(el)
        if not t:
            continue
        m = re.match(r"^(\d+)\s+(bedroom|bathroom|toilet|parking)", t, re.I)
        if m:
            out[m.group(2).lower()] = m.group(1)
        elif "type" not in out:
            out["type"] = t
    return out


def _price_text(s: BeautifulSoup) -> Optional[str]:
    """Amount and period together, so schema.py reads the period off the page.

    Passing these as one string is deliberate. Record.build flags
    rent_period_defaulted_monthly when no period is stated, which is the
    difference between knowing a rent is monthly and assuming it.
    """
    return text_of(s.select_one(".qa-advert-price-view-title")) or \
        text_of(s.select_one(".qa-advert-price-view"))


def _amenities(s: BeautifulSoup) -> list[str]:
    seen, out = set(), []
    for t in s.select("div.b-advert-attributes__tag"):
        v = text_of(t)
        if v and v.lower() not in seen and len(v) < 60:
            seen.add(v.lower())
            out.append(v)
    return out


def _seller(s: BeautifulSoup) -> Optional[str]:
    return text_of(s.select_one(".b-seller-block__name")) or \
        text_of(s.select_one(".qa-seller-name"))


def _listing_age(s: BeautifulSoup) -> Optional[str]:
    for b in s.select(".b-seller-badge"):
        t = text_of(b) or ""
        if "on jiji" in t.lower():
            return t
    return None


def _int(v: Optional[str]) -> Optional[int]:
    if not v:
        return None
    m = re.search(r"\d+", v.replace(",", ""))
    return int(m.group()) if m else None


def _furnished(v: Optional[str]) -> Optional[bool]:
    if not v:
        return None
    t = v.strip().lower()
    if t.startswith("furnish"):
        return True
    if t.startswith("unfurnish") or t.startswith("not furnish"):
        return False
    return None  # "Semi-furnished" is neither, and saying so beats guessing


def extract(html: str, meta: dict) -> Record:
    s = soup_of(html)
    attrs = _attributes(s)
    icons = _icon_attributes(s)
    flags: list[str] = []

    canonical = s.find("meta", property="og:url")
    url = (canonical.get("content") if canonical else None) or meta.get("url")

    locality = attrs.get("property address") or meta.get("locality")
    if attrs.get("property address") and meta.get("locality") and \
            attrs["property address"].strip().lower() != str(meta["locality"]).strip().lower():
        flags.append("locality_url_and_page_disagree")

    bedrooms = _int(icons.get("bedroom")) or _int(attrs.get("bedrooms"))
    bathrooms = _int(icons.get("bathroom")) or _int(attrs.get("bathrooms"))
    toilets = _int(attrs.get("toilets"))
    parking = _int(attrs.get("parking spaces")) or _int(icons.get("parking"))

    prop_type = icons.get("type") or attrs.get("subtype")

    # Jiji calls it Property Size and means the plot, the same quantity Ghana
    # Property Centre publishes as Plot size. Captured because it is on the
    # page, flagged because its name invites being read as floor area, and
    # excluded from the feature set for exactly that reason.
    size_text = attrs.get("property size")
    if size_text:
        flags.append("area_is_plot_not_floor")

    # The number sits behind a click, so the seller name is the grouping key.
    # It is the right key for a grouped split, and the flag is there so nobody
    # later reads agent_phone_hash as a hashed phone number.
    seller = _seller(s)
    if seller:
        flags.append("agent_id_from_seller_name")

    age = _listing_age(s)
    flags.append("posted_date_absent_jiji_shows_age_only")
    if age:
        flags.append(f"listing_age_{re.sub(r'[^a-z0-9]+', '_', age.lower()).strip('_')}")

    rec = Record.build(
        source=SOURCE,
        source_record_id=str(meta.get("source_record_id") or url_hash(url or "")),
        city=meta.get("city") or "Accra",
        locality=normalise_locality(locality),
        bedrooms=bedrooms,
        bathrooms=bathrooms,
        furnished=_furnished(attrs.get("furnishing")),
        area_text=size_text,
        price_text=_price_text(s),
        posted_date=None,
        listing_url=url,
        property_type=prop_type,
        toilets=toilets,
        parking_spaces=parking,
        amenities=_amenities(s),
    )
    # schema.phone_hash expects seven digits and rightly refuses a name, so the
    # seller key is hashed here instead. Same column, same purpose, which is a
    # stable identifier to group a split on; different provenance, which is why
    # agent_id_from_seller_name rides on every one of these records.
    if seller:
        rec.agent_phone_hash = url_hash(f"jiji-seller:{seller.strip().lower()}")

    rec.cleaning_flags = list(dict.fromkeys(list(rec.cleaning_flags) + flags))
    return rec


# ------------------------------------------------------------------ commands

def _seen_ids(path: Path) -> set[str]:
    out: set[str] = set()
    if not path.exists():
        return out
    with path.open(encoding="utf-8") as fh:
        for line in fh:
            try:
                out.add(str(json.loads(line).get("source_record_id")))
            except json.JSONDecodeError:
                continue
    return out


def collect_urls(fetcher: PoliteFetcher, pages: int, out_path: Path) -> list[str]:
    found: dict[str, None] = {}
    for n in range(1, pages + 1):
        url = INDEX if n == 1 else f"{INDEX}?page={n}"
        try:
            html = fetcher.get(url)
        except RobotsDisallowed:
            print(f"  page {n}: disallowed by robots.txt, stopping")
            break
        except DeclinedByServer as e:
            print(f"  page {n}: declined by server ({e}), stopping")
            break

        before = len(found)
        s = soup_of(html)
        for a in s.find_all("a", href=True):
            full = urljoin(BASE, a["href"].split("?")[0])
            if urlparse(full).netloc.endswith("jiji.com.gh") and parse_url(full):
                found[full] = None
        gained = len(found) - before
        print(f"  page {n:>3}: {gained:>3} new, {len(found):,} total")
        if gained == 0:
            print("  no new listings on this page, stopping")
            break

    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text("\n".join(found), encoding="utf-8")
    return list(found)


def cmd_urls(args) -> int:
    fetcher = PoliteFetcher(Path(args.archive))
    urls = collect_urls(fetcher, args.pages, Path(args.out))
    print(f"\n{len(urls):,} listing urls written to {args.out}")
    return 0


def cmd_crawl(args) -> int:
    url_path = Path(args.urls)
    if not url_path.exists():
        print(f"No url list at {url_path}. Run the urls command first.")
        return 1
    urls = [u.strip() for u in url_path.read_text(encoding="utf-8").splitlines() if u.strip()]

    records_path = Path(args.records) / f"{SOURCE}_accra.jsonl"
    records_path.parent.mkdir(parents=True, exist_ok=True)
    seen = _seen_ids(records_path)
    if seen:
        print(f"{len(seen):,} already collected, resuming")

    fetcher = PoliteFetcher(Path(args.archive))
    clog = CollectionLog(Path(args.records).parent / "collection_log.csv")

    todo = []
    for u in urls:
        meta = parse_url(u)
        if meta and str(meta["source_record_id"]) not in seen:
            todo.append(meta)
    if args.limit:
        todo = todo[: args.limit]
    print(f"{len(todo):,} to fetch\n")

    written = declined = failed = 0
    with records_path.open("a", encoding="utf-8") as out:
        for i, meta in enumerate(todo, 1):
            try:
                html = fetcher.get(meta["url"])
            except RobotsDisallowed:
                declined += 1
                clog.write(url=meta["url"], outcome="robots_disallowed", at=now_iso())
                continue
            except DeclinedByServer as e:
                declined += 1
                clog.write(url=meta["url"], outcome=f"declined:{e}", at=now_iso())
                print(f"  [{i}/{len(todo)}] declined, stopping: {e}")
                break
            except Exception as e:
                failed += 1
                clog.write(url=meta["url"], outcome=f"error:{type(e).__name__}", at=now_iso())
                continue

            try:
                rec = extract(html, meta)
            except Exception as e:
                failed += 1
                clog.write(url=meta["url"], outcome=f"parse_error:{type(e).__name__}",
                           at=now_iso())
                continue

            out.write(json.dumps(asdict(rec), ensure_ascii=False) + "\n")
            out.flush()
            written += 1
            clog.write(url=meta["url"], outcome="ok", at=now_iso())
            if i % 10 == 0 or i == len(todo):
                print(f"  [{i}/{len(todo)}] {written:,} written")

    print(f"\n{written:,} written, {declined:,} declined, {failed:,} failed")
    print(f"records at {records_path}")
    return 0


def cmd_parse_file(args) -> int:
    path = Path(args.path)
    html = path.read_text(encoding="utf-8", errors="ignore")
    meta = parse_url(args.url) if args.url else {}
    rec = extract(html, meta or {})
    print(json.dumps(asdict(rec), indent=2, ensure_ascii=False))
    return 0


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description="Jiji Ghana rental collector")
    p.add_argument("--archive", default="data/raw")
    sub = p.add_subparsers(dest="command", required=True)

    u = sub.add_parser("urls", help="page the index and save listing urls")
    u.add_argument("--pages", type=int, default=40)
    u.add_argument("--out", default="data/interim/jiji_listing_urls.txt")
    u.set_defaults(func=cmd_urls)

    c = sub.add_parser("crawl", help="fetch and extract each listing")
    c.add_argument("--urls", default="data/interim/jiji_listing_urls.txt")
    c.add_argument("--records", default="data/records")
    c.add_argument("--limit", type=int)
    c.set_defaults(func=cmd_crawl)

    f = sub.add_parser("parse-file", help="extract one archived page, for checking")
    f.add_argument("path")
    f.add_argument("--url")
    f.set_defaults(func=cmd_parse_file)

    args = p.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())