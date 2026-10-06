#!/bin/bash
set -euo pipefail
task_root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$task_root"
if ! command -v xcodebuild >/dev/null; then echo 'Xcode를 설치하고 첫 실행 설정을 완료해 주세요.' >&2; exit 1; fi
task_xcode_version="$(xcodebuild -version | awk '/^Xcode / { print $2 }')"
if [ "${task_xcode_version%%.*}" -lt 26 ]; then echo 'Kotlin 2.3.21 공통 엔진에는 Xcode 26 계열을 사용해 주세요(공식 호환 기준 26.0).' >&2; exit 1; fi
if ! command -v xcodegen >/dev/null; then echo 'brew install xcodegen 으로 XcodeGen을 설치해 주세요.' >&2; exit 1; fi
if ! command -v pod >/dev/null; then echo 'brew install cocoapods 으로 CocoaPods를 설치해 주세요.' >&2; exit 1; fi
if ! java -version >/dev/null 2>&1; then echo 'JDK 17 이상을 설치해 주세요.' >&2; exit 1; fi
python3 tools/sync_ios_core.py --check
python3 tools/export_ios_resources.py --check
cd iosApp
xcodegen generate --spec project.yml
# 진단 앱 Podfile은 보존한다. 새 앱은 별도 의존성 디렉터리를 쓴다.
mkdir -p TrexPods
cp Podfile.trex TrexPods/Podfile
python3 - <<'PY'
from pathlib import Path
p = Path('TrexPods/Podfile')
p.write_text(p.read_text().replace("project 'Trex.xcodeproj'", "project '../Trex.xcodeproj'").replace("workspace 'Trex.xcworkspace'", "workspace '../Trex.xcworkspace'"))
PY
cd TrexPods
pod install
printf '\n사용 Xcode: %s\n' "$task_xcode_version"
printf '\n열 파일: %s/iosApp/Trex.xcworkspace\n' "$task_root"
