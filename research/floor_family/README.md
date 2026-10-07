# 바닥 계열 설계의 측정 스크립트 (2026-10-06)

`docs/FLOOR_FAMILY_DESIGN.md` §3 의 수치를 낸 일회성 스크립트다. 설계 워크플로(조사 6 · 설계 3 · 검증 3 · 통합 1)의 에이전트들이 썼고, 임시 폴더에서 이곳으로 옮겼다.

- **실행은 데이터 사본에서 한다**: `C:/Users/hp276/Desktop/trex/data/floor_family/<폴더>/<스크립트>` (gitignore). 같은 폴더에 입력 parquet(앱 모델로 재추출한 AIHub 바닥 C·E 랜드마크 `aihub_local_agent/mp_full/`, MM-Fit 윗몸일으키기 `rel_designer/m2_situps.parquet` 등)이 있고, 여러 스크립트가 `__file__` 기준 상대 경로로 읽는다.
- 이 저장소 사본은 검토·이관용이다. 구현 1단계(설계 §10)에서 `research/external_rep_replay/floor_scorecard.py`·`aihub_floor_captures.py` 로 합치고 이 폴더는 지운다.
- 폴더 = 쓴 에이전트: `aihub_local_agent`(AIHub 원본 재추출·전체 보고), `design`·`designer`·`rel_designer`(설계안 셋), `validator`·`validator_user`(반박 검증).
- 조사 단계의 일부 스크립트(MM-Fit 게이트 통과율, `head_ground` '고개만' 89 %, 앉아서 시작한 크런치 0회 시뮬레이션, 09-12 플랭크 집계)는 임시 폴더 정리 때 유실됐다 — 설계 문서에 '재현 필요' 로 표시돼 있다.
