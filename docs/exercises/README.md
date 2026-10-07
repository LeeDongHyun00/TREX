# 종목별 문서

- 공통 원칙·함정: 저장소 루트 `CLAUDE.md`
- 남은 종목을 계열로 묶은 등급표·온보딩 절차: `docs/EXERCISE_TIERS.md`
- 종목별 함정: `squat.md`(바벨 스쿼트) · `curl.md`(덤벨 컬) · `lunge.md`(런지류) · `single_leg.md`(한 발 떠남 계열 — 크로스·사이드 런지·니업·사이드 크런치, §96) · `leg_raise.md`(라잉 레그 레이즈 — 양다리 판별·바닥 준비 기준, §99) · `floor_trial.md`(크런치·레그 레이즈·플랭크 **자세 교정 시험 단계** — 표시 위치·이유·해제 조건, 2026-10-08)
- 자세 교정 대사 목록(생성 문서): `FAULT_LINES.md` — `research/external_rep_replay/fault_lines.py` 로 다시 만든다
- 첫 반복의 모집단 사전값: 생성 파일 `posture/RepFormPriorTable.kt` — `research/external_rep_replay/rep_priors.py --write` (spec §90)
- 종목의 반복 검사 목록(코드가 정본): `research/external_rep_replay/replay-jvm` 의 `trex-rep-replay --checks <AIHub 종목 이름>`

## 규칙셋의 실제 분포 (CLAUDE.md 에서 옮김, 2026-09-27 시점)

**규칙셋의 실제 분포** — 세션 규칙셋 = `rules_mp_v0.json`(AIHub 서서) + `rules_floor_v0.json`(바닥) + **`RepFormSpecs.asRules()`(반복별 자세 검사, §62a·§62b·§62c — 코드가 정본, 스쿼트 11검사: ship 4 '상체 숙임'·'무릎 안쪽 모임'·'발 간격'·'발끝 방향'(반복); 덤벨 컬 9검사: ship 5 '상체 숙임'(스쿼트와 같은 1단 정책, C/D, 세트 최소 기준 SET_MIN_DELTA +20°, 반복 없이 4초 숙여도 말함 — `liveEvent`)·'팔꿈치 뜸'·'팔꿈치 옆 벌림'(정면 C)·'팔꿈치 앞 이탈'·'팔꿈치 몸에서 떨어짐'(사선 B/D, **카메라 쪽 팔 하나** — §62c 후속 7) + beta '팔꿈치 앞뒤(옆)'(SIDE 전용) — 옆 벌림·앞 이탈·몸에서 떨어짐은 2단(코칭·차단 임계가 다르다); 런지 4검사(§63): ship 3 '앞무릎 깊이'(차단 90°)·'상체 숙임'(2단)·'무릎 쏠림'(코칭 전용) + beta '어깨 기울기'. 검사의 `views` 는 **반복마다 그 반복 창의 방향으로** 적용된다(`RepFormRep.view` — 세트 누적 뷰는 옆으로 돌아선 구간 하나에 끌려가 정면 반복까지 유보시켰다), 위반 부위는 `RuleHighlight.forRepForm` 으로 스켈레톤에 칠한다)**. `PostureRuleSet.plusRepForm()` 이 붙이고 스쿼트 `발과 무릎의 방향 일치` 창 규칙을 beta 로 낮춘다(`RepFormSpecs.supersedes`). `rules_version` 은 `mp_v0.1+floor_v0.4+repform_v0.3`. 발 너비·발끝은 **이미지 2D 피처**(`Stance2d.kt`: `ankle_sep_2d`·`shoulder_sep_2d`·`toe2d_*`)로 잰다 — 월드 3D 는 발끝 회전에 흔들리고 z 가 추정치라(§62b) 쓰지 않는다. `rules_mp_v0.json` 141규칙 = ship **51** · beta **20** · exclude **70**(절반이 못 보는 규칙, §32 게이트 이후). `rules_floor_v0.json` 14규칙/8종목은 **전부 beta**. 헤더 `counts` 는 §32 부터 `rule_confidence.py --apply` 가 실제 분포로 갱신하지만 JSON 을 손으로 고치면 다시 어긋난다 — sanity check 는 `rules` 배열 집계가 정본. ship/beta 규칙의 `confidence` 필드(정상 오탐률·검출률·AUC 95% 구간)는 **스튜디오 기준**이다(§32).
