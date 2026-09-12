#!/usr/bin/env python3
"""빌드된 M2 .app의 실제 파일 바이트를 저장소 정본과 대조한다."""
import argparse
import hashlib
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", type=Path, help="TrexPostureInference.app 경로")
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    manifest_path = root / "docs/ios-kmp/ASSET_BASELINE.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    checks = []
    for entry in manifest["files"]:
        if not entry["path"].startswith("app/src/main/assets/posture/"):
            continue
        name = Path(entry["path"]).name
        path = args.bundle / name
        actual = hashlib.sha256(path.read_bytes()).hexdigest() if path.is_file() else None
        checks.append({"name": name, "sha256": actual, "matches": actual == entry["sha256"]})
    bundled_manifest = args.bundle / "ASSET_BASELINE.json"
    manifest_matches = bundled_manifest.is_file() and bundled_manifest.read_bytes() == manifest_path.read_bytes()
    passed = len(checks) == 4 and all(check["matches"] for check in checks) and manifest_matches
    print(json.dumps({"passed": passed, "manifestByteIdentical": manifest_matches, "files": checks}, ensure_ascii=False, indent=2))
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
