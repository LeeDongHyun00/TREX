# AIHub 휴대폰 재생 평가 — 실행 상태 (2026-09-11)

## 확인한 데이터

- 라벨 37,607클립 / 41종목. 앱 매핑은 26개 데이터셋 종목이다(앱 이름 중복은 별도).
- 전체 종목 목록과 종목별 Training/Validation 수는 `DEVICE_REPLAY_INVENTORY.md`.
- 사용자 제공 C:/T: 위치의 tar 36개를 직접 열어 파일명 항목 5,915,534개를 검사했다. Validation 첫 이미지 매칭 0건.
- Validation 촬영일 Day07_200929_F, Day19_201020_F, Day32_201104_F는 이 압축파일들에 없다. 바닥 Validation 1,064클립은 Day32에 해당한다.
- 현재 위치의 Validation 라벨은 존재하지만 원본 이미지가 없어 독립 검증 실행은 못 했다.

## 실제 실행

- Galaxy Note10+ SM-N976N 연결을 확인했다.
- `com.example.trex_kotlin.replay` 별도 패키지와 테스트 패키지를 설치했다. 기존 앱은 삭제·덮어쓰기하지 않았다.
- Training 바닥 8종목 × 정상 2클립/혼합 위반 2클립 × 5뷰 = 32클립, 160개 조합, 2,560장. 고정 난수 seed=20260911.
- 모든 JPEG를 tar offset으로 직접 읽어 약 399MB 재생 zip을 만들었다. 정답 관절을 모델 입력으로 사용하지 않았다.
- **160/160개 조합 전체 완료 및 PC 결과 회수·채점 완료.** USB 연결 해제 시 호스트 ADB 명령은 종료됐지만 휴대폰 계측은 계속 실행됐다. 재연결 후 88/160 진행을 확인했고 `--collect`로 중복 실행 없이 완료 결과를 회수했다.
- `complete.json`의 완료 수와 manifest SHA, results.jsonl의 전체 ID 일치를 확인했다. 원본 결과는 `outputs/device_replay/results.jsonl`, 채점은 `scores.json`, 요약은 `DEVICE_REPLAY_RESULTS.md`다.

## 결과와 해석

- 160개 조합 모두 GPU 실행. 추론 중앙값 102ms, p95 120ms(각 조합 첫 프레임 제외). JPEG 해독·추론·피처 계산 중앙값 133.81ms. 실시간 카메라 FPS나 렌더링 성능은 아니다.
- 피처 계산 2,510/2,560장(98.0%). 관절 검출/피처 계산 비율이며 자세 정확도가 아니다.
- 고정 권장 뷰의 window 조건 32사례에서 앱 앵커·종료 창은 10건 판정/22건 유보, 전체 프레임 창은 29건 판정/3건 유보. 약 9초짜리 합성 시간축의 결과이므로 실제 세션 판정률로 일반화하지 않는다.
- 전체 프레임 창에서 니푸쉬업·푸시업 고개 위반 각 1건 누락, 힙쓰러스트 고개 정상 1건 오탐. 앱 창에서는 다른 힙쓰러스트 정상 클립 1건이 오탐이었다. 정확한 ID·측정값은 결과 보고서에 남겼다.
- 플랭크 hold 20개 조합 전부 유보. 시작 앵커·초기 5초 기준 확보에 짧은 데이터이며 시간·반복 정답도 없다. 유지/반복 정확도는 미검증이다.
- 임계값을 고치지 않았다. 다음 단계는 불일치 이미지와 프레임별 라벨 검토, 누락 Validation 원본 확보, 실제 시간·반복 정답이 있는 세션 검증이다.

## 같은 코드로 실행하는 범위

카메라 YUV 변환 이후 진입점을 `PostureAnalyzer.analyzeBitmap`으로 공유했다. 같은 full.task, VIDEO, GPU 우선/CPU 폴백, 관절 변환·가시성·뷰 피처를 실행한다. 실제 delegate는 결과별 필드가 정본이다.

최종 세트 평가 코드는 `PostureAssessment.evaluate`로 추출해 실시간 화면과 재생기가 함께 호출한다. 앱의 floor 2D 추출기, 규칙 JSON, LiveCoach, RepCounter, 앵커·종료 컷도 사용한다. 기존 작업 트리의 FloorFeedback 등 사용자 수정은 보존했다. 빌드에 필요했던 TextButton import 한 건만 보완했다.

저장 이미지에는 IMU와 실제 촬영 시각이 없다. SCREEN_UP, 프레임 간격 600ms 가정을 명시했다. 따라서 반복/홀드 결과는 실행 관찰로만 남기고 정확도에서 제외한다. 카메라 획득·실시간 샘플링·UI/TTS 동작·실환경 정확도를 이 실행으로 입증하지 않는다.

clip_results는 원인 분리를 위한 전체 이미지 창 진단, live_results는 앱 앵커/종료 창 적용 결과다. 둘을 혼합 집계하지 않는다. Training 재사용 점수를 독립 검증 성능으로 보고하지 않는다.

## 연결이 끊긴 실행의 회수·재개 방법

이번 실행은 완료됐다. 아래는 다음 실행에서 연결이 끊겼을 때의 절차다.

1. 동일 기기를 USB 연결하고 디버깅을 허용한다.
2. `/sdcard/Android/data/com.example.trex_kotlin.replay/files/aihub_replay/progress.json`과 `complete.json` 존재 여부를 확인한다. 아직 instrumentation이 실행 중이면 새 실행을 겹치지 않는다.
3. 계측이 계속 실행 중이거나 완료 파일이 있으면 `--collect`로 기다려 회수한다. 중단된 상태일 때만 `--resume`으로 재개한다(설치/이미지 전송 생략, 완료된 ID 건너뜀). 기존 APK·재생 manifest를 바꾸지 않는다.

```powershell
.\.venv312\Scripts\python.exe -u research/aihub_fitness/run_device_replay.py --collect
# 실제 계측이 중단된 경우에만 위 명령 대신 사용
.\.venv312\Scripts\python.exe -u research/aihub_fitness/run_device_replay.py --resume
.\.venv312\Scripts\python.exe research/aihub_fitness/score_device_replay.py
```

scorer는 전체 ID 완결성과 유보 분모를 확인한다. 완료되지 않은 실행의 정확도를 임의로 계산하면 안 된다. 이번 결과의 manifest SHA-256은 `995435695852b22be798432a475d1d6b2f9eae2a0076aeac91378dc279295a05`다.

## 다시 준비할 때

```powershell
.\.venv312\Scripts\python.exe research/aihub_fitness/device_replay.py inventory
.\.venv312\Scripts\python.exe research/aihub_fitness/replay_archive_audit.py
.\.venv312\Scripts\python.exe research/aihub_fitness/device_replay.py pack --split 1.Training --per-class 2
.\gradlew.bat -PpostureReplay :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
.\.venv312\Scripts\python.exe -u research/aihub_fitness/run_device_replay.py
```

`-PpostureReplay` 빌드는 app-debug.apk의 applicationId를 평가용으로 바꾼다. 일반 사용자 APK가 아니다. 실행기는 package 이름을 확인해 기존 앱 설치를 거부한다.

## 검증

- 전체 기존 JVM 테스트 + 평가용 앱/계측 APK 빌드 성공(replay-build.log).
- 추가 공유 평가 테스트 2건 통과(replay-shared-tests.log): 준비/정리 프레임 제외, 앵커 없을 때 유보.
- 채점 유보 분모 검사 통과: 전부 유보면 정확도/오탐률 null, 유보 포함 위반 검출률과 판정 중 검출률 분리.
- git diff --check 통과. 휴대폰 재생 160/160개 완료 및 조건 라벨 채점 성공. 독립 Validation 정확도와 실제 카메라/시간/반복 정확도는 미검증.
- 실행 APK SHA-256은 outputs/device_replay/run.json, 소스·모델·규칙 해시는 source_sha256.json, 압축파일 감사는 archive_audit.json에 기록했다.
