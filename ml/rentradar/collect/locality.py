"""Resolve free-text addresses to a controlled list of Accra localities.

Ghana Property Centre publishes a locality from a fixed list: East Legon,
Cantonments, Tse Addo. Jiji lets the seller type whatever they like, so the same
field arrives as "Spintex Peace And Love", "Achimota Petroleum", "Adjriganor",
"3 Minutes Drive From" and "Ga121-6607".

Locality is the strongest feature class in a rent model. Left raw, the Jiji rows
would produce thousands of one-off values, every one of them swept into "other"
by the minimum count rule, and the second source would contribute nothing but
its price.

So the clean source supplies the vocabulary and the messy one is matched into
it. Six outcomes, each recorded on the record that used it:

    exact           the text already is a known locality
    alias           a listed misspelling of a known locality
    contains        a known locality appears inside it, longest name wins, so
                    "Spintex Peace And Love" resolves to Spintex and "East
                    Legon Hills" never collapses into "East Legon"
    fuzzy           close enough on edit distance to be a misspelling
    not_a_locality  a property description or a GPS code in the address box
    unmatched       left exactly as written and flagged, never guessed

Nothing is invented. An address that resolves to nothing keeps its own text, and
the flag says so, which is the difference between a cleaning rule and a fudge.

    python -m rentradar.collect.locality report
    python -m rentradar.collect.locality apply
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from difflib import SequenceMatcher
from pathlib import Path
from typing import Optional

# Below this, a near-match is more likely to be a different place than a typo.
FUZZY_THRESHOLD = 0.88

# A canonical name must appear this often in the clean source to be trusted as
# a real locality rather than a one-off someone typed.
MIN_GAZETTEER_COUNT = 5

# Four is safe because the containment test uses word boundaries, so "Dome"
# matches "Dome Pillar 2" but never the middle of another word.
MIN_CONTAINS_LEN = 4

CLEAN_SOURCES = {"ghana_property_centre"}

# Localities the clean source never lists, because it sells the premium end of
# the market. Every name here was read off the unmatched report with a count
# beside it, not invented, and every one is a real Accra or Tema locality.
# Without them the second source contributes its prices and none of its
# locations, which is the opposite of why it was collected.
#
# "Tema" is coarse, a whole city rather than a neighbourhood, the same caveat
# that applies to "Accra Metropolitan" in the clean source. It is kept because
# a coarse location still beats "other", but it belongs in the write-up as a
# limitation rather than being passed off as a locality.
SUPPLEMENTARY = [
    "Ablekuma",
    "Abokobi",
    "ACP",
    "Amanfrom",
    "Amasaman",
    "American House",
    "Ashiyie",
    "Ashongman",
    "Borteyman",
    "Bortianor",
    "Burma Hills",
    "Coastal Estate",
    "Community 18",
    "Community 20",
    "Community 25",
    "Danfa",
    "Dansoman",
    "Dodowa",
    "Dome",
    "Frafraha",
    "Greda Estate",
    "HFC Estate",
    "Kotobabi",
    "Labone",
    "Lakeside",
    "Lapaz",
    "Lekma",
    "Malejor",
    "Mile 7",
    "Oyibi",
    "Pantang",
    "Pokuase",
    "Sakumono",
    "Sowutuom",
    "Teiman",
    "Tema",
    "Teshie",
    "Trasacco",
    "Tuba",
    "West Legon",
    "Westland Boulevard",
]

# Spellings the fuzzy matcher misses because they fall just under the bar.
# "Oyarafa" against "Oyarifa" scores 0.857 and the threshold is 0.88. Lowering
# the threshold would catch these and also start inventing matches, so the few
# that matter are listed by hand instead. Keys are folded: lowercase, letters
# and digits only, single spaces.
ALIASES = {
    "oyarafa": "Oyarifa",
    "trassaco": "Trasacco",
    "west trassaco": "Trasacco",
    "asheiyei": "Ashiyie",
    "tseado": "Tse Addo",
    "airport residentials": "Airport Residential Area",
    "bortiano": "Bortianor",
}

# Text that is plainly not a place: a property description typed into the
# address box, or a GhanaPost GPS code. Checked only after every matching
# method has failed, so "East Legon 3 bedroom apartment" still resolves to
# East Legon rather than being thrown away for containing the word apartment.
NOT_A_PLACE = re.compile(
    r"(bed\s?r|bedroom|chamber|self\s?contain|apartment|flat\b|storey|"
    r"agency|terms applied|negotiab|furnished|^g[a-z]{1,2}[-\s]?\d{3,}|\d{4,})",
    re.I)

# Words that carry no location, stripped before fuzzy matching so "Spintex
# Baatsonaa Junction" and "Spintex Baatsonaa" do not look like different places.
NOISE = re.compile(
    r"\b(off|near|behind|beside|opposite|around|at|the|and|junction|station|"
    r"road|street|close|avenue|estate|estates|court|flats|apartment|apartments|"
    r"house|houses|area|drive|minutes|minute|min|from|to|by|new|old)\b", re.I)


def fold(s: Optional[str]) -> str:
    return re.sub(r"[^a-z0-9]+", " ", (s or "").lower()).strip()


def denoise(s: str) -> str:
    return re.sub(r"\s+", " ", NOISE.sub(" ", s)).strip()


def load(src: Path) -> list[dict]:
    rows = []
    for path in sorted(src.glob("*.jsonl")):
        with path.open(encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if not line:
                    continue
                try:
                    rows.append(json.loads(line))
                except json.JSONDecodeError:
                    continue
    return rows


def build_gazetteer(rows: list[dict]) -> list[str]:
    """Canonical names: the clean source's list, plus the supplementary ones."""
    counts = Counter()
    for r in rows:
        if r.get("source") in CLEAN_SOURCES and r.get("locality"):
            counts[r["locality"].strip()] += 1
    names = [n for n, c in counts.items() if c >= MIN_GAZETTEER_COUNT]
    names.extend(n for n in SUPPLEMENTARY if n not in names)
    # Longest first, so "East Legon Hills" is tested before "East Legon".
    return sorted(names, key=lambda n: (-len(n), n))


def resolve(raw: Optional[str], gazetteer: list[str]) -> tuple[Optional[str], str]:
    """Return (canonical, method). Never invents a place."""
    if not raw or not raw.strip():
        return None, "empty"

    want = fold(raw)
    table = {fold(n): n for n in gazetteer}

    if want in ALIASES:
        return ALIASES[want], "alias"

    if want in table:
        return table[want], "exact"

    # Longest canonical name contained in the text wins.
    for name in gazetteer:
        key = fold(name)
        if len(key) < MIN_CONTAINS_LEN:
            continue
        if re.search(rf"\b{re.escape(key)}\b", want):
            return name, "contains"

    stripped = denoise(want)
    if stripped and stripped in table:
        return table[stripped], "exact"

    best, score = None, 0.0
    for name in gazetteer:
        key = fold(name)
        for candidate in {want, stripped} - {""}:
            r = SequenceMatcher(None, candidate, key).ratio()
            if r > score:
                best, score = name, r
    if best and score >= FUZZY_THRESHOLD:
        return best, "fuzzy"

    if NOT_A_PLACE.search(raw):
        return None, "not_a_locality"

    return None, "unmatched"


def section(title: str) -> None:
    print("\n" + "-" * 70)
    print(title)
    print("-" * 70)


def cmd_report(args) -> int:
    rows = load(Path(args.records))
    if not rows:
        print(f"No records under {args.records}.")
        return 1

    gaz = build_gazetteer(rows)
    print(f"{len(rows):,} records")
    print(f"{len(gaz)} canonical localities, clean source plus supplementary")

    dirty = [r for r in rows if r.get("source") not in CLEAN_SOURCES]
    if not dirty:
        print("\nNo free-text sources present. Nothing to resolve.")
        return 0

    methods = Counter()
    examples: dict[str, list[tuple[str, str]]] = {}
    unmatched = Counter()
    for r in dirty:
        canon, how = resolve(r.get("locality"), gaz)
        methods[how] += 1
        if how == "unmatched":
            unmatched[r.get("locality") or ""] += 1
        elif canon:
            examples.setdefault(how, [])
            if len(examples[how]) < 8:
                examples[how].append((r["locality"], canon))

    section("1. How the free-text addresses resolve")
    total = len(dirty)
    for how in ("exact", "alias", "contains", "fuzzy", "unmatched",
                "not_a_locality", "empty"):
        n = methods[how]
        if n:
            print(f"  {n:>6,}  {n / total * 100:>5.1f}%  {how}")

    for how in ("alias", "contains", "fuzzy"):
        if examples.get(how):
            section(f"2. {how} matches, a sample")
            for raw, canon in examples[how]:
                print(f"  {raw[:44]:<46} -> {canon}")

    if unmatched:
        section("3. Left alone, most common first")
        print("  These keep their own text and carry a flag. Anything here that")
        print("  is a real locality should be added to SUPPLEMENTARY by hand.\n")
        for name, n in unmatched.most_common(60):
            print(f"  {n:>4}  {name[:60]}")
        print(f"\n  {len(unmatched):,} distinct unmatched values")

    section("What this changes")
    resolved = (methods["exact"] + methods["alias"]
                + methods["contains"] + methods["fuzzy"])
    print(f"  {resolved:,} of {total:,} free-text rows ({resolved / total * 100:.1f}%)")
    print("  land on a known locality instead of a one-off value.")
    print("\n  Run with 'apply' to write the resolved copies. The original text")
    print("  is kept on every record as locality_raw, so this is reversible.")
    return 0


def cmd_apply(args) -> int:
    src, out = Path(args.records), Path(args.out)
    rows = load(src)
    if not rows:
        print(f"No records under {src}.")
        return 1
    gaz = build_gazetteer(rows)
    out.mkdir(parents=True, exist_ok=True)

    methods = Counter()
    by_file: dict[str, list[dict]] = {}
    for r in rows:
        new = dict(r)
        if r.get("source") in CLEAN_SOURCES:
            methods["clean_source_untouched"] += 1
        else:
            canon, how = resolve(r.get("locality"), gaz)
            methods[how] += 1
            flags = list(new.get("cleaning_flags") or [])
            new["locality_raw"] = r.get("locality")
            if canon:
                new["locality"] = canon
                flags.append(f"locality_resolved_{how}")
            elif how == "not_a_locality":
                # Kept separate from unresolved. This one is not a failure to
                # match a place, it is a record whose address field never held
                # a place to begin with.
                flags.append("locality_not_a_place")
            else:
                flags.append("locality_unresolved")
            new["cleaning_flags"] = list(dict.fromkeys(flags))
        by_file.setdefault(str(r.get("source") or "unknown"), []).append(new)

    written = 0
    for source, recs in by_file.items():
        dest = out / f"{source}_resolved.jsonl"
        with dest.open("w", encoding="utf-8") as fh:
            for r in recs:
                fh.write(json.dumps(r, ensure_ascii=False) + "\n")
        print(f"  {len(recs):>6,}  {dest}")
        written += len(recs)

    section("Resolved")
    for how, n in methods.most_common():
        print(f"  {n:>6,}  {how}")
    print(f"\n  {written:,} records written to {out}")
    print("  locality_raw holds the original text on every changed record.")
    return 0


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description="Resolve free-text localities")
    p.add_argument("--records", default="data/records")
    sub = p.add_subparsers(dest="command", required=True)

    r = sub.add_parser("report", help="show what would change, write nothing")
    r.set_defaults(func=cmd_report)

    a = sub.add_parser("apply", help="write resolved copies")
    a.add_argument("--out", default="data/interim")
    a.set_defaults(func=cmd_apply)

    args = p.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())