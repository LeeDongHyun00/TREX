#!/usr/bin/env python3
"""빌드된 iOS .app의 자세 모델·규칙 바이트를 저장소 정본과 대조한다."""
import argparse
import hashlib
import json
import subprocess
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
    food_model_matches = None
    if args.bundle.name == "Trex.app":
        food_manifest_path = root / "iosApp/Trex/Resources/FOOD_MODEL_BASELINE.json"
        food_manifest = json.loads(food_manifest_path.read_text(encoding="utf-8"))
        food_manifest_matches = (args.bundle / food_manifest_path.name).read_bytes() == food_manifest_path.read_bytes()
        food_model_matches = food_manifest_matches and hashlib.sha256((args.bundle / food_manifest["bundledName"]).read_bytes()).hexdigest() == food_manifest["bundledSha256"]
    exposed_native = []
    if food_embedded:
        symbols = subprocess.run(["xcrun", "nm", "-gU", str(food_runtime)], check=True, capture_output=True, text=True).stdout
        exposed_native = [line.split()[-1] for line in symbols.splitlines() if line.split() and line.split()[-1].startswith(("_TfLite", "__Z"))]
    passed = len(checks) == 4 and all(check["matches"] for check in checks) and manifest_matches and food_embedded is not False and food_model_matches is not False and not exposed_native
    print(json.dumps({"passed": passed, "manifestByteIdentical": manifest_matches, "foodRuntimeEmbedded": food_embedded, "foodModelMatches": food_model_matches, "exposedFoodNativeSymbols": exposed_native, "files": checks}, ensure_ascii=False, indent=2))
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
