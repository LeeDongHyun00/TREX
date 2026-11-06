#!/bin/bash
set -euo pipefail
task_root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$task_root"
bash iosApp/scripts/setup-mac.sh
task_simulator_id="$(xcrun simctl list devices available --json | python3 -c '
import json,sys,re
devices=json.load(sys.stdin)["devices"]
for runtime in sorted(devices, key=lambda x: tuple(map(int,re.findall(r"\d+",x))), reverse=True):
    if ".iOS-" in runtime:
        for device in devices[runtime]:
            if device.get("isAvailable") and device["name"].startswith("iPhone"):
                print(device["udid"]);sys.exit(0)
raise SystemExit("사용 가능한 iPhone Simulator 런타임을 Xcode에서 설치해 주세요.")
')"
xcodebuild -workspace iosApp/Trex.xcworkspace -scheme Trex -configuration Debug \
  -destination "platform=iOS Simulator,id=$task_simulator_id" \
  -derivedDataPath "$task_root/iosApp/build/tests/DerivedData" \
  -resultBundlePath "$task_root/iosApp/build/tests/$(date +%Y%m%d-%H%M%S).xcresult" \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO test
