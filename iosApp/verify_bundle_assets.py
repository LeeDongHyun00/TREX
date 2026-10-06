#!/usr/bin/env python3
"""빌드된 iOS .app의 자세 모델·규칙 바이트를 저장소 정본과 대조한다."""
import argparse
import hashlib
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", type=Path, help="Trex.app 또는 TrexPostureInference.app 경로")
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    manifest_path = root / "iosApp/Trex/Resources/ASSET_BASELINE.json"
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
    food_runtime = args.bundle / "Frameworks/TrexFoodRuntime.framework/TrexFoodRuntime"
    food_embedded = food_runtime.is_file() if args.bundle.name == "Trex.app" else None
    passed = len(checks) == 4 and all(check["matches"] for check in checks) and manifest_matches and food_embedded is not False
    print(json.dumps({"passed": passed, "manifestByteIdentical": manifest_matches, "foodRuntimeEmbedded": food_embedded, "files": checks}, ensure_ascii=False, indent=2))
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
