# Android 체험판 1.3.0-preview.2

2026-09-27 · `main` · 태그 `v1.3.0-preview.2`

[APK 다운로드](https://github.com/LeeDongHyun00/TREX/releases/download/v1.3.0-preview.2/TREX-1.3.0-preview.2.apk) · [릴리스](https://github.com/LeeDongHyun00/TREX/releases/tag/v1.3.0-preview.2)

## 배포 대상

- 패키지 `com.example.trex_kotlin`, versionCode `7`, versionName `1.3.0-preview.2`.
- Android 8.0(API 26) 이상. `assembleRelease` 산출물을 기존 체험판과 같은 개발 인증서로 서명했다. 운영 배포용 별도 서명키는 사용하지 않았다.
- 기존 체험판과 인증서 SHA-256이 같아 서명 충돌 없이 업데이트할 수 있다. 앱을 삭제하면 기기 기록이 지워질 수 있다.
- APK와 `SHA256SUMS.txt`는 GitHub 사전 릴리스 자산으로 제공한다. APK·키·기기 로그·로컬 연구 산출물은 Git에 넣지 않는다.

## 변경 내용

- 기본 스쿼트·런지·덤벨 컬의 `운동 방법`에 자동 재생 GIF, 시작 자세·운동 동작·호흡 설명, 주의사항을 추가했다. GIF는 2프레임 시안이며 연속 동작 교육 영상이나 전문가 검수 자료는 아니다.
- 준비 화면은 촬영 그림과 방향 배치 안내를 중심으로 줄였다. 운동 수정에서는 세트·횟수/시간·세트 간 휴식을 숫자 휠로 조절하며 저장값을 같은 휠 상태에서 읽는다. 운동 목록 맨 아래에 `운동 추가하기`를 배치했다.
- 런지 휴식 수정값 저장 경로를 정리하고 JVM 테스트로 0초·95초·125초를 확인했다. 바벨 컬·바벨 런지에 반복 검사 파일럿을 확장했으며 검증 범위와 유보 정책은 `docs/EXERCISE_TIERS.md`와 자세 평가 스펙을 따른다.
- 근육 모드 시안은 로컬 `outputs/`의 PC 연구 결과이며 이번 Android APK 기능이 아니다.

## 검증과 한계

- `:app:testDebugUnitTest`: 450건 통과, 실패·오류·건너뜀 0건.
- `:app:assembleDebug`, `:app:assembleRelease`, `:app:assembleDebugAndroidTest`: 성공. Android 테스트는 소스 컴파일·패키징만 확인했다. 릴리스 APK 서명 및 `apksigner verify` 성공.
- 실휴대폰 설치·실행·화면 조작은 사용자 요청에 따라 수행하지 않았다. 실제 휠 조작감, 화면 배치, GIF 교육 적합성, 종목별 자세 정확도는 이번 배포 확인 범위가 아니다.
- 인증서 SHA-256: `3027771596e23100d4203da03cd44c4a81f5e3d4cf197fdb7fb2ba4e4dc751ef`.
- APK SHA-256: `438294d23c1aa50cb9055dbb63fd0157e98a161526c7021bd41be397898c01c5`.

## 재현

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
```

`app/build/outputs/apk/release/app-release-unsigned.apk`는 설치용이 아니다. 공개 자산은 이 파일을 개발 인증서로 서명한 `TREX-1.3.0-preview.2.apk`다. 운영 배포용 키를 설정하기 전까지는 이 체험판을 운영 서명 릴리스로 취급하지 않는다.
