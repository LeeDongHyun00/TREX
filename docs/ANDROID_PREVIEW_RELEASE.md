# Android 체험판 1.1.0-preview.1

2026-09-12 · `feature/posture-coach-reliability` · 태그 `v1.1.0-preview.1`

[APK 다운로드](https://github.com/LeeDongHyun00/TREX/releases/download/v1.1.0-preview.1/TREX-1.1.0-preview.1.apk) · [릴리스](https://github.com/LeeDongHyun00/TREX/releases/tag/v1.1.0-preview.1)

## 배포 대상

- 패키지 `com.example.trex_kotlin`, versionCode `2`, versionName `1.1.0-preview.1`.
- Android 8.0(API 26) 이상. arm64-v8a / armeabi-v7a / x86 / x86_64 포함.
- 기존 개발 설치본과 같은 개발 서명의 debug 미리보기 APK. 스토어 정식 배포/서버 인증/운영 출시 검증을 의미하지 않는다.
- 코드·문서는 브랜치에, APK·SHA256SUMS는 GitHub 사전 릴리스에 배포한다. 원본 데이터·키·기기 로그·개인 기록은 배포하지 않는다.

## 변경 내용과 이유

촬영 방향을 모른 채 시작을 기다리지 않도록 운동별 권장 방향과 몸 회전 방법을 먼저 안내한다. 자동 준비가 인식 끊김으로 막히면 고정된 오른쪽 하단의 **준비 건너뛰기**로 같은 운동을 시작한다. 기존 **5초 후 시작**은 자리에 돌아갈 시간이 필요할 때 사용한다.

기존 **기록 모드**를 준비/운동 하단의 스위치로 복원했다. 종목별 설정을 유지하고 현재 세트 초반 대비 변화와 자동 횟수를 관찰한다. 모드를 바꾸어도 초반 기준·누적 횟수는 초기화하지 않는다. 변경된 자세의 원인을 피로로 단정하지 않는다.

앞서 구현한 운동별 목표/세트/휴식 편집, 순서 변경·삭제, 카메라 OFF 진행, 직접 조작, 최근 7일 운동·식단 조회를 함께 포함한다. 자세 규칙과 카운터의 정확도가 개선됐다는 주장은 하지 않는다.

## 검증

- JVM 단위 테스트 **244건 통과**, 실패·오류·건너뜀 0건.
- Note10+ 분리 앱 UI 테스트 **3건 통과**: 준비 안내·배경 전환 취소·회전·수동 5초 진입, 준비 건너뛰기·기록 모드 변경/재진입 저장, 카메라 OFF 횟수 진행·휴식·세트 건너뛰기 확인/취소.
- 준비를 건너뛴 직후 같은 운동/첫 세트가 유지되고 완료로 저장되지 않음을 확인했다. 세로·가로 준비 화면과 기록 모드 화면 캡처를 검토했다.
- 배포 APK와 분리 검사 APK의 DEX 및 자산 **16항목 바이트 일치**. 분리 패키지에서 검사하므로 사용자 앱의 루틴에 테스트 데이터를 쓰지 않았다.
- `apksigner verify` 통과. 서명 인증서 SHA-256: `3027771596e23100d4203da03cd44c4a81f5e3d4cf197fdb7fb2ba4e4dc751ef`.
- APK SHA-256: `facbd1c1ba8307a3897c285b7e4c7d5c473c8175ec033fa3a6a6c5150867089c`.
- 권장 방향이 5초 문구 앞에 있는 실제 TTS 제출 로그를 확인했다. 테스트 중 음소거·회전·수동 시작으로 안내를 끊으므로 이 검사는 전체 발화의 청취 품질 검사가 아니다.
- 이는 로컬 빌드/실기기 검사 결과다. CI 실행이나 다양한 체형·촬영 환경에서의 자동 준비 성공률/자세 정확도 검증을 대신하지 않는다.

로컬 증빙은 gitignore 대상 `research/aihub_fitness/outputs/release-preview/`의 `device-tests.log`, `package_parity.json`, `SHA256SUMS.txt`, 화면 캡처에 남긴다. 개인 기기 화면과 로그는 공개 릴리스에 첨부하지 않는다.

## 빌드

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest -PpostureReplay
# 분리 APK와 테스트 APK를 따로 보관한 후 일반 패키지로 다시 빌드
.\gradlew.bat :app:assembleDebug
```

일반 빌드는 `-PpostureReplay`를 쓰지 않는다. 출력 경로가 같으므로 분리 검사 APK를 먼저 보관한다. 두 APK의 실행 코드/자산 일치, 일반 패키지 이름·버전·서명·해시를 확인한다.

## 업데이트와 문제 발생 시

동일 서명의 기존 설치본은 삭제 없이 업데이트한다. 설치 전 기기 기록/설정을 백업하고 설치 직후 첫 실행 전 바이트 보존을 확인한다. 앱 실행/복귀 시 최근 7일보다 오래된 운동 기록을 정리하는 기존 정책은 README와 릴리스에 명시한다.

설치 실패, 실행 불가, 준비 탈출 불가, 기록 손실이 확인되면 해당 릴리스를 초안으로 전환해 신규 다운로드를 중단하고 원인을 수정한 더 높은 versionCode의 빌드를 낸다. 낮은 버전 강제 설치나 앱 삭제로 되돌리지 않는다. 개인 기록을 보존하고 같은 서명으로 수정 버전을 배포한다.
