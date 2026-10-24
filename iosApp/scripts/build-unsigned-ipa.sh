#!/bin/bash
set -euo pipefail
task_root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$task_root"
bash iosApp/scripts/setup-mac.sh
build_root="$task_root/iosApp/build/sideload"
mkdir -p "$build_root"
xcodebuild -workspace iosApp/Trex.xcworkspace -scheme Trex -configuration Release \
  -sdk iphoneos -destination 'generic/platform=iOS' -derivedDataPath "$build_root/DerivedData" \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO build
python3 iosApp/verify_bundle_assets.py "$build_root/DerivedData/Build/Products/Release-iphoneos/Trex.app"
# 검증을 통과한 .app만 Payload에 담는다. 서명은 각 테스터의 Sideloadly에서 한다.
python3 - "$build_root" <<'PY'
from pathlib import Path
import sys, zipfile
root=Path(sys.argv[1]); app=root/'DerivedData/Build/Products/Release-iphoneos/Trex.app'
with zipfile.ZipFile(root/'TREX-unsigned.ipa','w',zipfile.ZIP_DEFLATED) as ipa:
    for path in sorted(app.rglob('*')):
        if path.is_file(): ipa.write(path,Path('Payload/Trex.app')/path.relative_to(app))
print(root/'TREX-unsigned.ipa')
PY
