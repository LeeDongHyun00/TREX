# 실검증 도구 (개발자 빌드 전용)

자세 교정·횟수 세기를 실제 휴대폰에서 검증하기 위한 기록 기능입니다. **이 저장소의 코드로 직접 빌드하면 켜져 있고, 사용자에게 배포하는 빌드에서는 빠집니다.**
자세 판정·횟수 세기·음성 코칭은 두 빌드가 같습니다. 설계 근거는 [정본 스펙 §116](../research/aihub_fitness/KOTLIN_PORTING_SPEC.md)입니다.

| 기능 | 저장 위치(Android 기준 `/sdcard/Android/data/com.example.trex_kotlin/files/`) | 용도 |
|---|---|---|
| 세트 로그 | `posture_logs/sets-YYYYMMDD.jsonl` | 세트마다 원본 프레임 피처·관절 좌표·카운터 기록. 재생·재보정 데이터 |
| 음성 기록 | `posture_logs/feedback-YYYYMMDD.jsonl` | 판정·화면 상태와 실제 음성 발화 시각 비교 |
| 자가 라벨 | `posture_logs/labels/set_labels.jsonl`, `posture_logs/rep_truth.csv` | 운동 완료 화면에서 세트를 펼쳐 실제 횟수·자세 자평을 입력. 횟수 정답 |
| 횟수 검증 모드 | 표시 파일 `rep_validation.on`(앱 전용 폴더 최상위) | 파일이 있으면 화면의 자동 횟수를 숨기고 자동 진행·음성을 끈다. 횟수를 사람이 따로 셀 때 |

검증 모드 켜고 끄기와 로그 회수(macOS 예):

```bash
ADB=~/Library/Android/sdk/platform-tools/adb
D=/sdcard/Android/data/com.example.trex_kotlin/files
$ADB shell touch $D/rep_validation.on      # 켜기 — 끄려면 rm
$ADB pull $D/posture_logs ./posture_logs   # 세트 로그·음성 기록·자가 라벨(labels/, rep_truth.csv)
```

iPhone은 앱의 `Documents/posture_logs`에 같은 형식으로 남습니다(Xcode의 기기 컨테이너 내려받기로 회수).

## 개발자 빌드와 배포 빌드 구분

| | 개발자 빌드(기본) | 배포 빌드 |
|---|---|---|
| Android | `./gradlew :app:assembleRelease` | `./gradlew :app:assembleRelease -Ptrex.distribution=true` |
| iPhone | Xcode 실행·`xcodebuild …`(설정 없음)·Sideloadly 설치 | App Store·TestFlight 설치본(자동) 또는 `xcodebuild … TREX_DEV_TOOLS=NO` |
| 버전 이름(Android) | `1.3.0-preview.7-dev` 처럼 `-dev`가 붙음 | 붙지 않음 |

- iPhone은 App Store·TestFlight로 설치된 앱이면 빌드 설정과 상관없이 꺼집니다(그 설치본에는 프로비저닝 파일이 없음). Xcode의 Product → Archive로 올려도 사용자에게는 실검증 도구가 없습니다(스펙 §120).
- 개발자 빌드의 버전 이름은 세트 로그의 `app_version`에도 남아 어떤 빌드로 센 세트인지 구분됩니다.
- `scripts/publish_release.sh`는 버전 이름이 `-dev`로 끝나는 APK를 올리지 않습니다(aapt2로 확인 — `ANDROID_HOME` 필요).
- `trex.distribution=true`를 `local.properties`에 넣으면 그 PC의 빌드는 모두 배포 빌드가 됩니다.
