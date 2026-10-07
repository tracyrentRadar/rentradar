"""What does a Jiji price actually mean, and what does its size field measure?

Two questions, both answered from the page already archived, so this makes no
further request.

The first one is dangerous. Ghana Property Centre quotes a monthly rent. Ghanaian
listings very often quote the total for an advance period instead, a year or two
paid up front, and 30,000 cedis reads perfectly plausibly either way: as a month
for a furnished East Legon townhouse, or as a year for an ordinary flat. Merging
a corpus of monthly figures with a corpus of annual ones would leave the model
trained on two different quantities wearing one name, and nothing would ever
throw an error. The only symptom would be predictions that are wrong by a factor
of twelve for part of the data.

The second is the lesson from area_sqm. Jiji labels a field "Property Size". That
could be the floor area or the plot, and the two are not interchangeable.

    python -m rentradar.collect.jijiprice
"""

from __future__ import annotations

import argparse
import html as htmlmod
import re
import sys
from pathlib import Path

PRICE = re.compile(r"(?:GH[S₵]|GHS|₵|US\$|\$)\s?[\d][\d,\. ]{2,}", re.I)

PERIOD_WORDS = re.compile(
    r"(per\s+month|monthly|/\s*month|a\s+month|per\s+annum|per\s+year|yearly|"
    r"annually|/\s*year|per\s+day|nightly|minimum\s+rental\s+period|"
    r"advance|payment\s+plan|rent\s+period)", re.I)

SIZE_WORDS = re.compile(
    r"(property\s+size|plot\s+size|floor\s+area|covered\s+area|land\s+size|"
    r"built[- ]up|total\s+area)", re.I)


def visible(html: str) -> str:
    # Entities matter here: the cedi sign is usually written &#8373; in source,
    # and a price regex that does not unescape first finds nothing at all.
    html = htmlmod.unescape(html)
    t = re.sub(r"<(script|style)\b.*?</\1>", " ", html, flags=re.S | re.I)
    t = re.sub(r"<[^>]+>", " | ", t)
    t = re.sub(r"\s*\|\s*(\|\s*)+", " | ", t)
    return re.sub(r"[ \t]+", " ", t)


def around(text: str, pattern: re.Pattern, pad: int, limit: int) -> list[str]:
    out, seen = [], set()
    for m in pattern.finditer(text):
        a, b = max(0, m.start() - pad), min(len(text), m.end() + pad)
        snip = text[a:b].strip()
        fold = re.sub(r"[^a-z0-9]+", "", snip.lower())
        if fold in seen:
            continue
        seen.add(fold)
        out.append(snip)
        if len(out) >= limit:
            break
    return out


def newest_jiji_page(archive: Path) -> Path | None:
    best, best_t = None, -1.0
    for p in archive.rglob("*"):
        if not p.is_file() or p.suffix.lower() not in {".html", ".htm", ""}:
            continue
        try:
            if p.stat().st_size < 1_000:
                continue
            head = p.read_text(encoding="utf-8", errors="ignore")[:4000]
        except OSError:
            continue
        if "jiji" not in head.lower():
            continue
        t = p.stat().st_mtime
        if t > best_t:
            best, best_t = p, t
    return best


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description="Read the price and size meaning off the archive")
    p.add_argument("--archive", default="data/raw")
    p.add_argument("--file", help="a specific archived page")
    args = p.parse_args(argv)

    path = Path(args.file) if args.file else newest_jiji_page(Path(args.archive))
    if not path or not path.exists():
        print(f"No archived Jiji page found under {args.archive}.")
        print("Run jijiprobe first, or pass one with --file.")
        return 1

    print(f"reading {path}  ({path.stat().st_size:,} bytes)\n")
    text = visible(path.read_text(encoding="utf-8", errors="ignore"))

    print("-" * 70)
    print("1. Every price on the page, with what surrounds it")
    print("-" * 70)
    print("  The question is whether a period word sits next to the figure.\n")
    for s in around(text, PRICE, 150, 10):
        print(f"  ... {s} ...\n")

    print("-" * 70)
    print("2. Period wording anywhere on the page")
    print("-" * 70)
    hits = around(text, PERIOD_WORDS, 90, 14)
    if hits:
        for s in hits:
            print(f"  ... {s} ...")
    else:
        print("  none. If no price carries a period, the collector must refuse")
        print("  to assume monthly rather than defaulting to it.")

    print("\n" + "-" * 70)
    print("3. What the size field is called, and what sits beside it")
    print("-" * 70)
    for s in around(text, SIZE_WORDS, 120, 8):
        print(f"  ... {s} ...\n")

    print("-" * 70)
    print("Read it yourself before I write the extractor. If the price turns")
    print("out to be a yearly or advance total, Jiji cannot be merged with the")
    print("Ghana Property Centre rows without dividing it out first, and that")
    print("division has to be recorded as a cleaning rule like every other one.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
