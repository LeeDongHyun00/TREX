# Android 체험판 1.2.0-preview.1

2026-09-23 · `codex/posture-action-model-preview` · 새 태그 `v1.2.0-preview.1`

[APK 다운로드](https://github.com/LeeDongHyun00/TREX/releases/download/v1.2.0-preview.1/TREX-1.2.0-preview.1.apk) · [릴리스](https://github.com/LeeDongHyun00/TREX/releases/tag/v1.2.0-preview.1)

이전 preview.2 자산을 교체하지 않고 새 버전으로 제공한다. 태그는 APK를 빌드한 소스 커밋을 가리키며 정확한 커밋은 릴리스 본문에도 기록한다.

## 배포 대상과 변경

- 패키지 `com.example.trex_kotlin`, versionCode `4`, versionName `1.2.0-preview.1`
- Android 8.0(API 26) 이상, 개발 서명의 debug 미리보기 APK. 기존 공개 APK와 인증서가 같아 삭제 없이 업데이트 가능
- AIHub 연결 26종목으로 선택/추천/세션을 제한. 기존 계획은 원문 백업 후 지원 종목으로 정리하고 과거 기록 보존
- 운동별 단계·좌우를 구분하는 M1 카운터. 관측 반복/가동범위 충족·미달·미확인을 분리하고 마지막 복귀에서도 완료 사건 생성
- 발끝 진행선 기준 좌우 무릎 방향을 참고로 표시. 새 beta 관측은 음성 교정으로 말하지 않음
- AIHub에서 실제 학습한 약 43KB TCN, 번들 무결성·비동기 CPU 실행·발열 시 중단·시험 로그 추가
- 준비/일시정지 화면에서 관절 기록을 명시적으로 켤 수 있음. 기본 꺼짐, 영상 저장/자동 업로드 없음

**학습 모델은 시험 실행만 한다.** 운동 종류 내부 정확도는 75.21%지만, 학습하지 않은 운동을 서비스 종목으로 잘못 수용한 비율이 62.11%였다. 따라서 모델의 횟수·점수·음성 권한은 비활성 상태다. 임의 행동 전체/모든 자세 오류를 교정하는 완료 버전이 아니다. [구현 범위·자료 분할·한계](POSTURE_ACTION_MODEL_IMPLEMENTATION.md)

## 검증

| 검사 | 결과 |
|---|---|
| JVM 회귀·입력·정책·카운터 | 310통과, 1조건부 건너뜀(새 실기기 반복 재생 입력 없음) |
| Python 관절 로그 내보내기 | 5통과 |
| Android API36.1 x86_64 가상 기기 모델 시험 | 2통과: 실제 Interpreter/Python 파리티, 번들 검증, 수집 opt-in/해제/세트 초기화 |
| 같은 가상 기기의 UI 회귀 | 2통과: 모드 전환/큰 글씨, 카메라 확장/횟수 HUD 유지 |
| APK 설치·최초 실행 | 설치 성공, MainActivity cold launch 성공 |
| APK 서명 | apksigner 검증 성공, 기존 공개 APK와 인증서 일치 |

- APK 크기: `120,841,300`바이트 (약 115.2MiB)
- APK SHA-256: `032fc325a6441edee0d76557a1133e6b0d2b1c43e05b402763e3f2487734e982`
- 인증서 SHA-256: `3027771596e23100d4203da03cd44c4a81f5e3d4cf197fdb7fb2ba4e4dc751ef`
- 모델 SHA-256: `2a0dbf4e3af29a7629ea9c941b9aebbeb24199ab3226f668fbe1ad352022ae62`

물리 휴대폰이 연결되지 않아 ARM 실행·사용자 촬영·실제 계수 정확도·전력·발열을 검증하지 않았다. 가상 기기 검사는 실제 사람 운동의 정확도나 반응 속도를 증명하지 않는다. CI 검증을 주장하지 않는다. 로그인은 서버 인증에 연결되지 않은 체험용이며 사진 자동 분석은 지원하지 않는다.

## 빌드와 게시

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
python -m unittest discover -s research/aihub_fitness -p test_action_export.py
```

일반 패키지는 `-PpostureReplay` 없이 빌드한다. `.venv-action/`, `research/aihub_fitness/outputs*/`, `release-artifacts/`, APK, 학습 중간 자료, 개인 로그와 키는 Git에서 제외한다. 모델 번들·합성 시험 픽스처·집계 보고서만 소스와 함께 공개한다.

`tools/publish_android_preview.py`는 깨끗한 소스 커밋/원격 저장소를 확인하고 새 draft 사전 릴리스에 APK·SHA256SUMS를 올린 뒤 공개 다운로드 해시를 검증한다. 기존 릴리스는 덮어쓰지 않는다. 인증 토큰은 Git credential helper에서 메모리로만 읽고 출력하지 않는다.

## 업데이트와 문제 발생 시

동일 서명의 기존 앱은 삭제 없이 업데이트한다. 운동 기록의 최근 7일 보관 정책은 유지한다. 다른 서명의 개발 APK가 있으면 기록 보존을 위해 먼저 백업하고 설치 문제를 확인한다.

설치/실행/진행 불가 또는 기록 손실이 확인되면 새 릴리스를 초안으로 되돌려 다운로드를 중단한다. 앱 삭제나 강제 다운그레이드 대신 같은 서명의 더 높은 versionCode로 수정판을 제공한다.
