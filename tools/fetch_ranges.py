"""Parallel range downloader for large single-file HTTP sources.

Zenodo serves one MM-Fit workout video per request at a few hundred kilobytes a second on a single
connection, which puts the corpus out of reach on wall-clock alone. It does honour byte ranges, so
the fix is bandwidth-shaped rather than clever: split the file into contiguous chunks, pull them
concurrently, and write each into its own offset of a pre-sized file.

Resumable, but not by looking at the file's size. The destination is pre-sized to its full length
before the first byte arrives, so an interrupted download leaves a file that is already the right
length and mostly zeroes -- a size check would call that finished and hand back a corrupt video.
Completion is therefore recorded per chunk in a sidecar, which is deleted only once every chunk has
landed; a file with no sidecar beside it is the only kind this tool believes is complete.
"""

from __future__ import annotations

import argparse
import concurrent.futures as futures
import sys
import time
import urllib.request
from pathlib import Path

CHUNK_BYTES = 16 * 1024 * 1024
READ_BYTES = 1 << 20


def _content_length(url: str) -> int:
    request = urllib.request.Request(url, method="HEAD")
    with urllib.request.urlopen(request, timeout=60) as response:
        return int(response.headers["Content-Length"])


def _fetch(url: str, path: Path, start: int, end: int, attempts: int = 5) -> int:
    """Writes bytes [start, end] into `path` at `start`. Returns the count written."""
    for attempt in range(attempts):
        try:
            request = urllib.request.Request(url, headers={"Range": f"bytes={start}-{end}"})
            written = 0
            with urllib.request.urlopen(request, timeout=120) as response:
                with path.open("r+b") as handle:
                    handle.seek(start)
                    while True:
                        block = response.read(READ_BYTES)
                        if not block:
                            break
                        handle.write(block)
                        written += len(block)
            if written != end - start + 1:
                raise OSError(f"short chunk {start}-{end}: {written}")
            return written
        except Exception as error:  # noqa: BLE001 -- retried, then surfaced
            if attempt == attempts - 1:
                raise
            print(f"  retry {start}-{end}: {error}", file=sys.stderr, flush=True)
            time.sleep(2 * (attempt + 1))
    return 0


def _sidecar(destination: Path) -> Path:
    return destination.with_suffix(destination.suffix + ".chunks")


def download(url: str, destination: Path, connections: int, chunk_bytes: int) -> None:
    total = _content_length(url)
    ledger = _sidecar(destination)

    if destination.exists() and destination.stat().st_size == total and not ledger.exists():
        print(f"{destination.name}: already complete ({total / 2**20:.0f} MB)", file=sys.stderr)
        return

    chunks = [
        (start, min(start + chunk_bytes - 1, total - 1)) for start in range(0, total, chunk_bytes)
    ]
    completed: set[int] = set()
    if destination.exists() and destination.stat().st_size == total and ledger.exists():
        completed = {
            int(line) for line in ledger.read_text().split() if line.strip().isdigit()
        }
    else:
        # A fresh start, or a partial file whose ledger was lost: pre-size and take nothing on
        # trust. Pre-sizing is what lets the chunk writers seek to their own offsets.
        with destination.open("wb") as handle:
            handle.truncate(total)
        completed = set()
    ledger.write_text("\n".join(str(start) for start in sorted(completed)))

    outstanding = [(start, end) for start, end in chunks if start not in completed]
    done = sum(
        min(start + chunk_bytes, total) - start for start in completed
    )
    if completed:
        print(
            f"{destination.name}: resuming with {len(completed)}/{len(chunks)} chunks already in "
            f"place",
            file=sys.stderr,
        )
    started = time.time()
    with futures.ThreadPoolExecutor(max_workers=connections) as pool:
        pending = {
            pool.submit(_fetch, url, destination, start, end): start
            for start, end in outstanding
        }
        for future in futures.as_completed(pending):
            done += future.result()
            completed.add(pending[future])
            ledger.write_text("\n".join(str(start) for start in sorted(completed)))
            elapsed = max(time.time() - started, 1e-6)
            print(
                f"  {destination.name}: {done / 2**20:7.0f}/{total / 2**20:.0f} MB "
                f"({done / elapsed / 2**20:.1f} MB/s)",
                file=sys.stderr,
                flush=True,
            )

    if len(completed) != len(chunks):
        raise SystemExit(f"{destination.name}: {len(chunks) - len(completed)} chunks missing")
    ledger.unlink()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("url")
    parser.add_argument("destination", type=Path)
    parser.add_argument("--connections", type=int, default=8)
    parser.add_argument("--chunk-bytes", type=int, default=CHUNK_BYTES)
    args = parser.parse_args(argv)
    download(args.url, args.destination, args.connections, args.chunk_bytes)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
