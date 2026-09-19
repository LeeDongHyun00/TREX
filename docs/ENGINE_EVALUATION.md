# 평가 앱의 실행 엔진 확인

2026-09-19 · `feature/pose-evaluation-engine`

일반 앱과 평가 앱이 같은 이름과 버전·화면을 사용하면 어떤 엔진을 시험하는지 구분할 수 없다. `-PpostureReplay=true` 빌드는 패키지 분리에 더해 이름을 **TREX 엔진 평가**, 버전 접미사를 **-engine-eval**로 표시한다. 일반 앱의 이름·버전 정책은 유지한다.

평가 앱의 운동 목록에는 개발 엔진 검증용임을 표시한다. 카메라 준비 화면과 운동 HUD는 실제 생성된 엔진 식별자를 바탕으로 새 횟수 엔진, 유지 시간 관측, 직접 기록을 구분한다. 준비 화면은 자세 교정에 기존 규칙을 함께 사용한다는 사실도 알린다. 이 표시는 정확도 검증 완료나 새 자세 검출기 전체 구현을 의미하지 않는다.

## 실제 연결

| 계층 | 현재 실행 경로 | 의미 |
|---|---|---|
| 영상 → 관절 | MediaPipe Pose Landmarker FULL | 기존 추론 기반을 재사용 |
| 횟수 | `return-bilateral/1` | 연속 출발·이탈·반전·복귀, 좌우 분리·동시 짝짓기 |
| 요청 26종 프로필 | `exercise-rep-profiles/1:<운동명>` | 25종 반복 및 플랭크 유지 관측 계약 |
| 추가 운동 신호 | `legacy-signal-adapter` | 새 복귀 카운터에 종전 공통 신호를 연결 |
| 자세 규칙 | `rule-coach/1:<규칙셋 버전>` | 기존 규칙과 관측 경계·전달 후 회복 정책 사용 |

`PostureLive`는 실제 tracker 생성 이후 `EngineProvenance`를 만들고 `TrexEngine` 진단 로그에 남긴다. 같은 구조를 세트 JSON 최상위 `engine`에 저장한다. `application_id`, `rep_engine`, `rep_profile`, `rep_pattern`, `form_engine`, `pose_estimator`가 실행 경로의 근거다. 과거 기록에 이 필드가 없으면 과거 엔진을 새 엔진으로 추정하지 않는다.

## 과거 피처 기록 재생

`RecordedRepReplayTest#replayRecordedFeaturesWithoutRetimingOrImputation`은 평가 앱의 `rep_replay/input.jsonl`을 읽어 `results.jsonl`로 저장한다. 준비할 입력은 세트별 `set_id`, `exercise`, `frames[{t_ms,features}]`이다. 선택한 `pattern` 또는 `reps.pattern`과 촬영 경계가 있으면 반영한다. 없는 방식은 종목 기본값을 사용했다고 명시하며, 없는 준비 프레임·신호를 만들지 않는다.

이 경로는 실제 Kotlin 프로필, 기존 물리범위 제약, 최근 방향 창, 복귀/좌우 카운터를 실행한다. 원래 시각·순서·결측을 보존하고, 미지원 운동·방식과 등척성은 사유와 `counts:null`을 남긴다. 프레임별 원시 신호·관측 유보 사유·방향·완료 이벤트·극값을 저장해 누락 원인을 조사한다.

현재 보유한 과거 기기 자료는 26세트·6운동·3,085프레임이며 사용자 확인 반복 정답은 0건이다. 과거 `reps.count`는 이전 엔진의 추정치이므로 정답으로 채점하지 않는다. 준비 방향 예열 기록이 없어 재생의 처음 방향 창은 비어 있고, 일부 과거 로그에는 준비 움직임도 포함돼 있다. 따라서 원래 라이브와 완전히 같은 초기 상태의 재현이나 실제 사람 정확도 검증으로 해석하지 않는다.

AIHub JPG 라벨에도 촬영 시각·FPS·반복 정답이 없다. 합성 600ms 등으로 실행한 결과는 진단이며 실제 운동 속도 또는 횟수 정확도의 증거가 아니다. 해당 과거 설명은 `REP_SIGNALS.md`에서도 정정했다.

## 검증 범위

JVM 386건 통과, 실패·오류·생략 0건 및 평가 앱·검사 APK 빌드 성공을 확인했다. 실제 설치 파일의 이름·버전·해시, 현재 화면의 엔진 표시, `TrexEngine` 실행 로그를 대조한다. 실행 증거는 Codex 작업 폴더 `outputs/engine-recorded-replay/`에 보존한다. 실제 사람의 횟수·교정 효과는 별도 사용자 확인 자료와 비교해야 하며, 시험 지시의 ‘5회’를 실제 수행 정답으로 간주하지 않는다.
