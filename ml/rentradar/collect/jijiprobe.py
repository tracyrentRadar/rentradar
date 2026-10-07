"""Look at how Jiji structures a rental listing, before writing a collector for it.

Writing an extractor against a guessed page shape is how you end up with a field
called area_sqm holding a plot size. So: fetch the Accra rental index, work out
what a listing link looks like, fetch exactly one listing, and print everything
that could plausibly be a structured attribute.

Nothing here extracts a Record. Its only job is to show the markup so the
collector can be written against what the page says rather than what we hope it
says.

    python -m rentradar.collect.jijiprobe
    python -m rentradar.collect.jijiprobe --listing <a specific listing url>
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from pathlib import Path
from urllib.parse import urljoin, urlparse

from .fetch import DeclinedByServer, PoliteFetcher, RobotsDisallowed

INDEX = "https://jiji.com.gh/greater-accra/houses-apartments-for-rent"

try:
    from bs4 import BeautifulSoup
except ImportError:
    print("beautifulsoup4 is required: py -m pip install beautifulsoup4 lxml")
    raise SystemExit(1)


def soup_of(html: str) -> BeautifulSoup:
    try:
        return BeautifulSoup(html, "lxml")
    except Exception:
        return BeautifulSoup(html, "html.parser")


def shape(path: str) -> str:
    """Collapse a path to its skeleton so URL families group together."""
    parts = []
    for seg in path.strip("/").split("/"):
        if not seg:
            continue
        # A category segment like "houses-apartments-for-rent" is long but has
        # no digits. A listing slug almost always carries an id.
        if (re.search(r"\d", seg) and len(seg) >= 8) or len(seg) >= 32:
            parts.append("<slug>")
        else:
            parts.append(seg)
    return "/" + "/".join(parts)


def find_listing_links(html: str, base: str) -> tuple[list[str], Counter]:
    s = soup_of(html)
    host = urlparse(base).netloc
    shapes, urls = Counter(), {}
    for a in s.find_all("a", href=True):
        full = urljoin(base, a["href"])
        p = urlparse(full)
        if p.netloc != host:
            continue
        sh = shape(p.path)
        shapes[sh] += 1
        urls.setdefault(sh, full)
    return [urls[k] for k, _ in shapes.most_common()], shapes


def dump_json_ld(s: BeautifulSoup) -> None:
    blocks = s.find_all("script", type="application/ld+json")
    print(f"\n  JSON-LD blocks: {len(blocks)}")
    for i, tag in enumerate(blocks, 1):
        raw = tag.string or tag.get_text() or ""
        try:
            data = json.loads(raw)
        except json.JSONDecodeError:
            print(f"    block {i}: not valid JSON ({len(raw)} chars)")
            continue
        text = json.dumps(data, indent=2, ensure_ascii=False)
        print(f"    --- block {i} " + "-" * 50)
        for line in text.splitlines()[:70]:
            print("    " + line)
        if len(text.splitlines()) > 70:
            print(f"    ... {len(text.splitlines()) - 70} more lines")


def dump_attributes(s: BeautifulSoup) -> None:
    """Anything shaped like a label and a value, however it is marked up."""
    print("\n  attribute-shaped pairs")
    seen, shown = set(), 0

    # definition lists and tables, the two conventional forms
    for dl in s.find_all(["dl", "table"]):
        keys = dl.find_all(["dt", "th"])
        vals = dl.find_all(["dd", "td"])
        for k, v in zip(keys, vals):
            pair = (k.get_text(" ", strip=True), v.get_text(" ", strip=True))
            if pair[0] and pair[1] and pair not in seen:
                seen.add(pair)
                print(f"    {pair[0][:34]:<36} {pair[1][:44]}")
                shown += 1

    # class names carrying attr/param/property/spec, the usual framework idiom
    pat = re.compile(r"(attr|param|propert|spec|detail|feature)", re.I)
    for el in s.find_all(attrs={"class": pat}):
        t = el.get_text(" | ", strip=True)
        t = re.sub(r"\s*\|\s*(\|\s*)+", " | ", t)
        if 6 < len(t) < 160 and t not in seen:
            seen.add(t)
            print(f"    {t[:80]}")
            shown += 1
            if shown > 60:
                print("    (stopping at 60)")
                return
    if not shown:
        print("    none found, so the attributes are probably rendered by script")


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description="Inspect Jiji listing markup")
    p.add_argument("--index", default=INDEX)
    p.add_argument("--listing", help="skip discovery and inspect this url")
    p.add_argument("--archive", default="data/raw")
    args = p.parse_args(argv)

    fetcher = PoliteFetcher(Path(args.archive))
    listing = args.listing

    if not listing:
        print(f"index  {args.index}")
        if not fetcher.allowed(args.index):
            print("  robots.txt disallows this path. stopping.")
            return 1
        try:
            html = fetcher.get(args.index)
        except RobotsDisallowed:
            print("  robots.txt disallows this path. stopping.")
            return 1
        except DeclinedByServer as e:
            print(f"  server declined: {e}")
            return 1

        links, shapes = find_listing_links(html, args.index)
        print(f"\n  link families on the index page:")
        for sh, n in shapes.most_common(12):
            print(f"    {n:>4}  {sh}")

        # The listing family is the one that repeats most and ends in a slug.
        cands = [s for s, _ in shapes.most_common() if s.endswith("<slug>")]
        if not cands:
            print("\n  No slug-shaped link family found. Pass one with --listing.")
            return 1
        target_shape = cands[0]
        listing = next(u for u in links if shape(urlparse(u).path) == target_shape)
        print(f"\n  treating this family as listings: {target_shape}")

    print(f"\nlisting  {listing}")
    if not fetcher.allowed(listing):
        print("  robots.txt disallows this path. stopping.")
        return 1
    try:
        html = fetcher.get(listing)
    except RobotsDisallowed:
        print("  robots.txt disallows this path. stopping.")
        return 1
    except DeclinedByServer as e:
        print(f"  server declined: {e}")
        return 1

    s = soup_of(html)
    print(f"  {len(html):,} bytes")
    title = s.find("title")
    if title:
        print(f"  title: {title.get_text(strip=True)[:100]}")

    dump_json_ld(s)
    dump_attributes(s)

    text = re.sub(r"<(script|style).*?</\1>", " ", html, flags=re.S | re.I)
    text = re.sub(r"<[^>]+>", " ", text)
    text = re.sub(r"\s+", " ", text)
    print("\n  fields found anywhere in the visible text")
    for name, pat in {
        "price": r"(GH[S₵]|GHS|USD|\$|₵)\s?[\d,]{3,}",
        "bedrooms": r"\d+\s*(bed|bedroom)",
        "bathrooms": r"\d+\s*(bath|bathroom)",
        "toilets": r"\d+\s*toilet",
        "area": r"\d[\d,]*\s*(m2|m²|sqm|sq\.?\s?m|sqft|sq\.?\s?ft)",
        "furnished": r"furnish",
        "serviced": r"serviced",
    }.items():
        hits = re.findall(pat, text, re.I)
        print(f"    {'yes' if hits else 'NO '}  {name:<10} {len(hits):>3}")

    print(f"\n  archived under {args.archive}, so this page can be re-read")
    print("  without another request.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
