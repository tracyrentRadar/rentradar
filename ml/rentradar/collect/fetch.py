"""Polite fetching, robots compliance, raw archiving and the collection log.

Jiji's terms prohibit software "aimed to interference with the normal operation
of the Platform". The obligation is therefore about conduct, not about whether
a scraping clause exists. Everything here exists so that if challenged you can
show the traffic was indistinguishable from a slow human reading the site.
"""

from __future__ import annotations

import csv
import hashlib
import logging
import time
from dataclasses import dataclass
from datetime import date, datetime, timezone
from pathlib import Path
from urllib.parse import urlparse
from urllib.robotparser import RobotFileParser

import requests

log = logging.getLogger("rentradar.fetch")

# Put a real address here. Identifying the crawler is the single cheapest
# thing you can do to stay on the right side of this, and impersonating a
# browser is the single most expensive.
CONTACT_EMAIL = "tracyotopea.kwamina@st.vvu.edu.gh"
USER_AGENT = (
    f"RentRadarResearchBot/1.0 (BSc research project, Valley View University; "
    f"contact {CONTACT_EMAIL})"
)

# Five seconds, not two. Section 3.5.1 of the proposal commits to "no more than
# one request every five seconds". That is a published commitment in a
# submitted document, so it is the binding figure regardless of what any
# robots.txt permits.
MIN_DELAY_SECONDS = 5.0
TIMEOUT_SECONDS = 20
MAX_RETRIES = 2
DAILY_REQUEST_CAP = 1200


class RobotsDisallowed(RuntimeError):
    """Raised when robots.txt says no. This is not an error to work around."""


class DeclinedByServer(RuntimeError):
    """403, 405 or a challenge. The site is declining automated traffic.

    Tonaton returned 405 to an ordinary request during source assessment. That
    is a refusal. Do not rotate user agents or addresses to get past it.
    """


@dataclass
class FetchStats:
    requests_made: int = 0
    bytes_read: int = 0
    refusals: int = 0


class PoliteFetcher:
    def __init__(self, archive_dir: Path, min_delay: float = MIN_DELAY_SECONDS):
        self.archive_dir = Path(archive_dir)
        self.archive_dir.mkdir(parents=True, exist_ok=True)
        self.min_delay = min_delay
        self.stats = FetchStats()
        self._last_request_at = 0.0
        self._robots: dict[str, RobotFileParser] = {}
        self._session = requests.Session()
        self._session.headers.update(
            {
                "User-Agent": USER_AGENT,
                "Accept": "text/html,application/xhtml+xml",
                "Accept-Language": "en-GB,en;q=0.9",
            }
        )

    # ---- robots ----

    def _robots_for(self, url: str) -> RobotFileParser:
        parsed = urlparse(url)
        origin = f"{parsed.scheme}://{parsed.netloc}"
        if origin in self._robots:
            return self._robots[origin]

        rp = RobotFileParser()
        robots_url = f"{origin}/robots.txt"
        try:
            resp = self._session.get(robots_url, timeout=TIMEOUT_SECONDS)
            if resp.status_code == 200:
                rp.parse(resp.text.splitlines())
                # Keep the evidence. A dated copy of robots.txt is the artefact
                # that shows you checked before you crawled.
                stamp = date.today().isoformat()
                out = self.archive_dir / "robots" / f"{parsed.netloc}_{stamp}.txt"
                out.parent.mkdir(parents=True, exist_ok=True)
                out.write_text(resp.text, encoding="utf-8")
                log.info("robots.txt saved for %s", parsed.netloc)
            else:
                # No robots.txt is permission by omission, not an invitation.
                # Keep the same polite conduct either way.
                rp.parse([])
                log.warning("no robots.txt at %s (HTTP %s)", robots_url, resp.status_code)
        except requests.RequestException as exc:
            rp.parse([])
            log.warning("could not read robots.txt for %s: %s", parsed.netloc, exc)

        self._robots[origin] = rp
        return rp

    def allowed(self, url: str) -> bool:
        return self._robots_for(url).can_fetch(USER_AGENT, url)

    def crawl_delay(self, url: str) -> float:
        rp = self._robots_for(url)
        declared = rp.crawl_delay(USER_AGENT)
        return max(float(declared or 0), self.min_delay)

    # ---- fetching ----

    def get(self, url: str, *, archive: bool = True) -> str:
        if self.stats.requests_made >= DAILY_REQUEST_CAP:
            raise RuntimeError(
                f"daily request cap of {DAILY_REQUEST_CAP} reached, stopping"
            )

        if not self.allowed(url):
            raise RobotsDisallowed(f"robots.txt disallows {url}")

        delay = self.crawl_delay(url)
        elapsed = time.monotonic() - self._last_request_at
        if elapsed < delay:
            time.sleep(delay - elapsed)

        last_error: Exception | None = None
        for attempt in range(MAX_RETRIES + 1):
            try:
                resp = self._session.get(url, timeout=TIMEOUT_SECONDS)
                self._last_request_at = time.monotonic()
                self.stats.requests_made += 1

                if resp.status_code in (401, 403, 405, 429):
                    self.stats.refusals += 1
                    raise DeclinedByServer(
                        f"HTTP {resp.status_code} from {urlparse(url).netloc}. "
                        "Treat this as the site declining automated traffic."
                    )
                resp.raise_for_status()
                self.stats.bytes_read += len(resp.content)

                if archive:
                    self._archive(url, resp.text)
                return resp.text

            except DeclinedByServer:
                raise
            except requests.RequestException as exc:
                last_error = exc
                if attempt < MAX_RETRIES:
                    backoff = delay * (attempt + 2)
                    log.warning("retry %s for %s in %.1fs: %s", attempt + 1, url, backoff, exc)
                    time.sleep(backoff)
                else:
                    raise RuntimeError(f"failed after {MAX_RETRIES + 1} attempts: {url}") from last_error

        raise RuntimeError("unreachable")

    def _archive(self, url: str, html: str) -> None:
        """Keep the raw page. If a parse turns out to be wrong six weeks from
        now you can re-parse rather than re-crawl, which is both faster and
        politer."""
        stamp = date.today().isoformat()
        name = hashlib.sha256(url.encode("utf-8")).hexdigest()[:24]
        out = self.archive_dir / "raw" / stamp / f"{name}.html"
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(html, encoding="utf-8")


class CollectionLog:
    """One row per session. This is the first thing an examiner asks for when
    probing a corpus claim."""

    FIELDS = [
        "started_at",
        "finished_at",
        "source",
        "city",
        "pages_fetched",
        "records_attempted",
        "records_kept",
        "records_flagged",
        "failures",
        "notes",
    ]

    def __init__(self, path: Path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        if not self.path.exists():
            with self.path.open("w", newline="", encoding="utf-8") as fh:
                csv.DictWriter(fh, fieldnames=self.FIELDS).writeheader()

    def write(self, **row) -> None:
        payload = {k: row.get(k, "") for k in self.FIELDS}
        with self.path.open("a", newline="", encoding="utf-8") as fh:
            csv.DictWriter(fh, fieldnames=self.FIELDS).writerow(payload)


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")
