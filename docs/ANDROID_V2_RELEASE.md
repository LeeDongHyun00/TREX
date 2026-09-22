# TREX v2 APK 배포

2026-09-23 · 대상 브랜치 `feature/pose-evaluation-engine`

## 배포 파일

- 태그: `v2.0.1-feedback-preview`
- APK: `TREX-v2-2.0.1-feedback-preview.apk`
- 앱 이름/패키지: `trex_v2` / `com.example.trex_kotlin.v2`
- 버전: `2.0.1-feedback-preview`, versionCode `4`, Android 8.0 이상
- GitHub 사전 릴리스에 APK, `SHA256SUMS.txt`, `BUILD_INFO.json`을 함께 제공한다.

이 APK는 공유 관측 엔진과 제한된 교정·점수를 기존 앱에 연결한 버전이다. `com.trex.engine.lab` 실험실 APK가 아니다. 기존 TREX와 별도로 설치되며 그 기록을 자동 이전하지 않는다. 기존 v2는 동일 서명일 때 업데이트 설치할 수 있다.

## 첫 버전과의 관계

첫 버전 `1.1.0-preview.2`의 다운로드와 `main` README는 유지한다. v2 소스는 `feature/pose-evaluation-engine`에 반영하고 해당 브랜치 README에서 새 다운로드를 안내한다. 첫 버전은 APK 교체 이력이 있으므로 태그명만으로 소스를 추정하지 않는다. v2는 릴리스의 소스 커밋과 해시로 비교 대상을 고정한다.

지원 범위는 [README](../README.md)의 26종 표와 [구현 문서](TREX_V2_IMPLEMENTATION.md)를 따른다. 25종 반복·플랭크 관측 시간, 그중 16종·30개 교정/점수 후보다. 나머지 10종의 교정·점수는 미제공이다.

## 빌드와 파일 검증

배포 APK는 커밋된 파일만 있는 별도 체크아웃에서 만든다. 사용자 작업 폴더의 미추적 이미지·개인 로그는 포함하지 않는다. SDK 위치만 로컬 설정으로 제공한다.

```powershell
.\gradlew.bat :pose-engine:test :app:testDebugUnitTest :app:assembleDebug
```

APK를 대상으로 `aapt dump badging`, `apksigner verify --print-certs`, SHA-256 계산을 수행한다. 앱 이름·패키지·버전·최소 Android 버전을 확인하고 APK 내 엔진 모델/규칙 파일을 검사한다. 소스 커밋·APK 크기·해시·서명 인증서·검사 결과는 `BUILD_INFO.json`에 기록한다. 비공개 서명 키나 사용자 데이터는 배포하지 않는다.

JVM 검사와 APK 검사를 사람의 자세 정확도 검사로 설명하지 않는다. §61 당시 설치본의 실기기 4건 통과와 이번 배포 파일의 검증 이력을 구분한다. GitHub Actions 검증을 실행하지 않았다면 CI 통과를 주장하지 않는다.

## 다운로드와 문제 발생 시

[v2 릴리스](https://github.com/LeeDongHyun00/TREX/releases/tag/v2.0.1-feedback-preview)에서 파일을 받는다. README 링크는 사전 릴리스의 파일 업로드·공개 확인 후 반영한다. 다운로드 파일의 해시를 `SHA256SUMS.txt`와 비교한다.

설치나 실행 문제가 있으면 기존 앱을 삭제해 기록을 지우지 않는다. 먼저 패키지·버전·서명을 확인한다. 심각한 배포 결함은 v2 다운로드를 중지하고 같은 패키지/서명의 더 높은 versionCode로 수정판을 제공한다. 첫 버전 릴리스와 `main`은 변경하지 않는다.
