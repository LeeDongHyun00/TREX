# 2026-10-08 폰 보고 조사 — 공용 맥락

작업 트리(코드 정본): `C:/Users/hp276/Desktop/trex/.claude/worktrees/exercise-posture-feedback-gaze-d02974`
이 트리에는 §100(교정 멘트·시선) 미커밋 변경이 들어 있고, **폰에 설치된 APK 가 바로 이 트리의 빌드**다(2026-10-08 13:15 KST 설치, `dumpsys` lastUpdateTime).

## 데이터 (이 폴더 = scratchpad/phone1008)
- `sets-20261008.jsonl` — 한 줄 = 한 세트(스키마 trex.posture.setlog). `created_at` 은 **세트 끝 시각(UTC)**, KST = UTC+9.
  - 줄 0~4 (09:13~09:22 KST) = **옛 빌드**(§100 이전). 줄 5~14 = **새 빌드**(사용자가 이번 문제를 보고한 세트).

| 줄 | 끝(KST) | 종목(로그 exercise) | 결과 |
|---|---|---|---|
| 5 | 13:46:38 | 바벨 스쿼트(앱 '기본 스쿼트') | count 14, invalid 7, note "정확 10 / 14회" |
| 6 | 13:48:11 | 덤벨 컬 | count 17, invalid 5, rejected 2 |
| 7 | 13:49:15 | 스탠딩 니업 | 21, rejected 11 (legcycle_v3_beta) |
| 8 | 13:50:43 | 스탠딩 사이드 크런치 | 20, rejected 11 |
| 9 | 13:52:49 | 스텝 포워드 다이나믹 런지(앱 '런지') | 9 |
| 10 | 13:54:27 | 사이드 런지 | 5, rejected 24 |
| 11 | 13:55:31 | 크로스 런지 | 0, rejected 7 |
| 12 | 13:56:44 | 크런치 | 10, rejected 3 |
| 13 | 13:58:08 | 라잉 레그 레이즈 | 5, rejected 6 |
| 14 | 13:59:15 | 플랭크 | 버틴 15 s / 경과 47 s |

- 프레임: `frames[]` = {t_ms(세트 상대), infer_ms, visible, vis[33], xy[66](정규화 화면 x,y), w[99](MediaPipe 월드 m), up(중력), features{...}}. 300 ms 판정 격자.
- `reps` 블록: count/t_ms/min/max/valid/rejected[](사유)/arms(컬)/sides(한 다리)/floor_reps(바닥, `face_rel` 포함 가능)/config.
- `results` = 규칙 판정, `note` = 세트 요약 문자열.
- `sets-20261007.jsonl` = 어제(옛 빌드) — 크런치·레그 레이즈·플랭크·스쿼트·런지 비교용.
- `feedback-20261008.jsonl` = 음성 이벤트(t_ms = epoch ms). `speech_timeline_1340_1400.txt` = 새 빌드 구간 음성 타임라인(KST) — speech_requested(요청 문장) · tts_done · tts_stop(interrupted=true 면 끊김) · plank_ui_state.

## 도구
- 재생기: `research/external_rep_replay/replay-jvm/build/install/trex-rep-replay/bin/trex-rep-replay.bat` (이미 이 트리 엔진으로 빌드됨). `--checks <종목>`, `--dump-features`, `--floor-clips`. 세트 로그 → 캡처는 `research/external_rep_replay/setlog_captures.py`, 한 다리 회별 표 `single_leg_diag.py`, 바닥 회별 표 `floor_diag.py`, 모집단 채점 `family_scorecard.py`.
- 모집단 캡처: `data/mmfit_mp_captures`, `data/rehab_mp_captures` (작업 트리의 `data` 는 메인 체크아웃 data 로 가는 정션 — **절대 지우지 말 것**).
- adb: `C:/Users/hp276/AppData/Local/Android/Sdk/platform-tools/adb.exe` (Git Bash 에서는 `MSYS_NO_PATHCONV=1`). 폰 R3CMB04LLNZ. **읽기 전용 명령만**(logcat -d, pull, dumpsys). install/uninstall/clear 금지.

## 규칙
- **저장소 파일을 고치지 않는다**(설계 단계). 임시 스크립트·출력은 `scratchpad/phone1008/work/<문제id>/` 에.
- 사용자는 어떤 회를 일부러 틀리게 했는지 라벨을 주지 않았다 — 데이터(회별 값)와 음성 타임라인으로 추론하고, 추론이면 추론이라고 쓴다.
- 근거는 `파일:줄` 과 실제 수치로. "아마"로 끝나는 원인은 원인이 아니다.
