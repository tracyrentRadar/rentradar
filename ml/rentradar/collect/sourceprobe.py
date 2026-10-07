"""Can we collect from this site, and is the data worth collecting?

Two separate questions, and they get confused constantly. The first is a matter
of the publisher's stated policy: what robots.txt says, whether the listing
paths are open to the user agent we identify as. The second is whether the
listings actually carry the fields a price model needs. A site can say yes and
still be useless, and a site that refuses is simply closed, whatever its data
looks like.

Run this from the laptop, not the server. A cloud address gets refused by
content delivery networks for reasons that have nothing to do with the site's
policy, which is the distinction that cost us a day already.

    python -m rentradar.collect.sourceprobe tonaton
    python -m rentradar.collect.sourceprobe tonaton jiji
"""

from __future__ import annotations

import argparse
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path
from urllib.parse import urlparse

try:
    from .fetch import USER_AGENT
except ImportError:  # run as a loose script
    USER_AGENT = "RentRadarBot/1.0 (academic research; contact tracyotopea.kwamina@st.vvu.edu.gh)"

SITES = {
    "tonaton": {
        "home": "https://tonaton.com/",
        "listing": "https://tonaton.com/r_greater-accra/c_houses-apartments-for-rent",
    },
    "jiji": {
        "home": "https://jiji.com.gh/",
        "listing": "https://jiji.com.gh/greater-accra/houses-apartments-for-rent",
    },
    "meqasa": {
        "home": "https://meqasa.com/",
        "listing": "https://meqasa.com/houses-for-rent-in-Accra",
    },
}

# Fields the price model cannot do without. A source missing most of these is
# not a second source, it is extra rows with holes in them.
WANTED = {
    "price": r"(GH[S₵]|GHS|USD|\$|₵)\s?[\d,]{3,}",
    "bedrooms": r"\d+\s*(bed|bdrm|bedroom)",
    "bathrooms": r"\d+\s*(bath|bathroom)",
    "locality": r"(East Legon|Cantonments|Spintex|Osu|Adenta|Madina|Tema|Dansoman|Achimota)",
    "area": r"\d[\d,]*\s*(m2|m²|sqm|sq\.?\s?m|sqft|sq\.?\s?ft)",
}


def fetch(url: str, timeout: int = 25) -> tuple[int, str, str]:
    """Plain GET with our own identified agent. Returns (status, body, note)."""
    req = urllib.request.Request(url, headers={
        "User-Agent": USER_AGENT,
        "Accept": "text/html,application/xhtml+xml,text/plain,*/*",
        "Accept-Language": "en-GB,en;q=0.9",
    })
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            body = r.read().decode("utf-8", errors="replace")
            return r.status, body, ""
    except urllib.error.HTTPError as e:
        try:
            body = e.read().decode("utf-8", errors="replace")
        except Exception:
            body = ""
        return e.code, body, e.reason or ""
    except Exception as e:
        return 0, "", f"{type(e).__name__}: {e}"


def read_robots(text: str, agent_token: str) -> dict:
    """Group directives by user agent, the way a crawler must read them."""
    groups, current = {}, []
    for raw in text.splitlines():
        line = raw.split("#", 1)[0].strip()
        if not line or ":" not in line:
            continue
        key, _, val = line.partition(":")
        key, val = key.strip().lower(), val.strip()
        if key == "user-agent":
            current = [val.lower()]
            groups.setdefault(val.lower(), {"allow": [], "disallow": [], "delay": None})
        elif key in ("allow", "disallow") and current:
            for a in current:
                groups[a][key].append(val)
        elif key == "crawl-delay" and current:
            for a in current:
                try:
                    groups[a]["delay"] = float(val)
                except ValueError:
                    pass
    sitemaps = re.findall(r"(?im)^\s*sitemap:\s*(\S+)", text)
    return {"groups": groups, "sitemaps": sitemaps}


def path_allowed(rules: dict, path: str) -> tuple[bool, str]:
    """Longest matching rule wins, which is the standard's tie-break."""
    best, verdict = -1, True
    reason = "no rule matches, so allowed by default"
    for kind in ("disallow", "allow"):
        for pat in rules.get(kind, []):
            if not pat:
                continue
            literal = pat.rstrip("*")
            if path.startswith(literal) and len(literal) > best:
                best, verdict = len(literal), (kind == "allow")
                reason = f"{kind.title()}: {pat}"
    return verdict, reason


def probe(name: str, cfg: dict) -> None:
    print("\n" + "=" * 70)
    print(f"  {name}")
    print("=" * 70)

    root = f"{urlparse(cfg['home']).scheme}://{urlparse(cfg['home']).netloc}"
    status, body, note = fetch(root + "/robots.txt")
    print(f"\n  robots.txt   HTTP {status}  {note}".rstrip())

    if status != 200:
        print("    Could not read the publisher's policy, so the question of")
        print("    whether collection is permitted stays unanswered. Do not")
        print("    treat silence as consent.")
        return

    print("  " + "-" * 66)
    for line in body.splitlines():
        if line.strip():
            print(f"    {line.rstrip()}")
    print("  " + "-" * 66)

    parsed = read_robots(body, USER_AGENT)
    rules = parsed["groups"].get("*", {"allow": [], "disallow": [], "delay": None})
    for a in parsed["groups"]:
        if "rentradar" in a:
            rules = parsed["groups"][a]
            print(f"\n  A group names our agent specifically: {a}")

    listing_path = urlparse(cfg["listing"]).path or "/"
    ok, why = path_allowed(rules, listing_path)
    print(f"\n  listing path  {listing_path}")
    print(f"  permitted     {'YES' if ok else 'NO'}   ({why})")
    delay = rules.get("delay")
    print(f"  crawl-delay   {delay if delay else 'not declared, we use 5s'}")
    if parsed["sitemaps"]:
        print(f"  sitemaps      {len(parsed['sitemaps'])} declared")
        for s in parsed["sitemaps"][:4]:
            print(f"                {s}")
    else:
        print("  sitemaps      none declared, so pages must be found by paging")

    if not ok:
        print("\n  The publisher says no. That settles it, and nothing about the")
        print("  quality of the data changes the answer.")
        return

    print(f"\n  fetching one listing page to see what the rows carry...")
    status, html, note = fetch(cfg["listing"])
    print(f"  {cfg['listing']}")
    print(f"  HTTP {status}  {note}".rstrip())

    if status in (403, 405, 429):
        print("\n    Refused at the network edge, not by robots.txt. On this")
        print("    connection that is a real refusal: record it and stop. Do not")
        print("    change the user agent or route around it.")
        return
    if status != 200 or not html:
        print("\n    No body to inspect.")
        return

    text = re.sub(r"<script.*?</script>|<style.*?</style>", " ", html, flags=re.S | re.I)
    text = re.sub(r"<[^>]+>", " ", text)
    text = re.sub(r"\s+", " ", text)

    print(f"\n  {len(html):,} bytes, {len(text):,} of visible text")
    print("  fields present anywhere on the page:")
    for field, pattern in WANTED.items():
        hits = re.findall(pattern, text, re.I)
        mark = "yes" if hits else "NO "
        print(f"    {mark}  {field:<10} {len(hits):>4} matches")

    prices = re.findall(r"(?:GH[S₵]|GHS|USD|\$|₵)\s?[\d,]{3,}", text, re.I)
    if prices:
        print(f"\n  example prices: {', '.join(prices[:8])}")
    if re.search(r"(call for price|contact for price|negotiable|price on request)", text, re.I):
        print("  WARNING: some listings hide the price behind a contact request.")
        print("  Those rows cannot train a price model.")


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description="Is this source open, and is it useful?")
    p.add_argument("sites", nargs="*", default=["tonaton"],
                   help=f"any of: {', '.join(SITES)}")
    args = p.parse_args(argv)

    print(f"identifying as:\n  {USER_AGENT}")
    for name in args.sites:
        cfg = SITES.get(name.lower())
        if not cfg:
            print(f"\n  unknown site {name}. known: {', '.join(SITES)}")
            continue
        probe(name.lower(), cfg)

    print("\n" + "=" * 70)
    print("  Permission and usefulness are separate findings. Record both in")
    print("  the collection plan, including the refusals: a documented 'this")
    print("  source said no' is a result, not a gap.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
