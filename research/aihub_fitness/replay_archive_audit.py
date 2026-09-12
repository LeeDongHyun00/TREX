"""tar 내부 전체 파일명을 직접 확인한다. 이미지는 해제하지 않고 헤더만 읽는다."""
from concurrent.futures import ProcessPoolExecutor, as_completed
from pathlib import Path
from collections import Counter
import json
import sys
import tarfile
import time

HERE = Path(__file__).resolve().parent
OUT = HERE / "outputs/device_replay/archive_audit.json"
ROOTS = [Path(r"C:\Users\hp276\Desktop\trex\data\013.피트니스자세"), Path(r"T:\trex_allData\013.피트니스자세")]


def scan(args):
    path, wanted = args
    start = time.monotonic()
    days, matched, files = Counter(), [], 0
    with tarfile.open(path, "r:") as tf:
        for member in tf:
            if time.monotonic() - start > 180:
                raise TimeoutError(path)
            if not member.isfile(): continue
            name = member.name.lstrip("./")
            days[name.split("/")[0]] += 1
            files += 1
            if name in wanted: matched.append(dict(key=name, offset=member.offset_data, size=member.size))
            # TarFile 기본 멤버 캐시의 메모리 증가를 막는다.
            tf.members.clear()
    p = Path(path)
    return dict(path=path, size=p.stat().st_size, mtime_ns=p.stat().st_mtime_ns,
                files=files, days=dict(days), validation_first_images=matched, elapsed_s=round(time.monotonic()-start, 2))


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    labels = json.loads((OUT.parent / "labels.json").read_text(encoding="utf-8"))
    wanted = {r["first_key"] for r in labels if r["split"] == "2.Validation" and r["first_key"]}
    previous = json.loads(OUT.read_text(encoding="utf-8")) if OUT.exists() else []
    by_path = {r["path"]: r for r in previous}
    pending = []
    for p in sorted(p for root in ROOTS for p in root.rglob("*.tar")):
        old = by_path.get(str(p))
        if old and old["mtime_ns"] == p.stat().st_mtime_ns and old["size"] == p.stat().st_size: continue
        pending.append((str(p), wanted))
    print(f"direct tar header audit: {len(pending)} archives", flush=True)
    with ProcessPoolExecutor(max_workers=4) as pool:
        for result in as_completed([pool.submit(scan, item) for item in pending]):
            r = result.result()
            by_path[r["path"]] = r
            OUT.write_text(json.dumps(list(by_path.values()), ensure_ascii=False, indent=1), encoding="utf-8")
            print(f"{Path(r['path']).name}: files={r['files']}, days={list(r['days'])}, validation_matches={len(r['validation_first_images'])}, {r['elapsed_s']}s", flush=True)
