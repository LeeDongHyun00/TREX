# -*- coding: utf-8 -*-
"""휴대폰 렙 카운트 수집 프로토콜 생성기 → REP_VALIDATION.md + 무작위 계획표 CSV (설계 §7 Gate A/B, §14).

research/aihub_fitness/DEVICE_VALIDATION.md(device_validation_plan.py)와 같은 관례: 문서는 이 스크립트가 만들고,
계획표 CSV 를 순서대로 찍은 뒤 채점기(score_phone_reps.py)가 같은 종목의 로그를 시각 순서로 계획 행과 짝짓는다.
필요 세트 수는 score_phone_reps.py 의 Clopper-Pearson 함수로 계산해 문서에 넣는다(손으로 적은 숫자가 아니다).

사용법
    python rep_validation_plan.py                                   # REP_VALIDATION.md 다시 쓰기
    python rep_validation_plan.py --csv pilot  --persons 1 --out-dir <폴더>
    python rep_validation_plan.py --csv gateA  --persons 3 --out-dir <폴더> [--with-veryslow]
    python rep_validation_plan.py --csv gateB  --persons 5 --out-dir <폴더>
계획표는 개인 측정 기록이 되므로(집계 칸을 채운다) 저장소가 아니라 data/ 아래(기본 ../../data/phone_rep/)에 둔다.
"""
from __future__ import annotations

import argparse
import csv
import random
import sys
from pathlib import Path

from score_phone_reps import cp_lower, cp_upper, sets_needed_for_lower, sets_needed_for_upper

HERE = Path(__file__).resolve().parent
DOC = HERE / "REP_VALIDATION.md"
DEFAULT_OUT = HERE.parents[1] / "data" / "phone_rep"

# 앱 이름(postureExerciseMap 키) · AIHub 종목 · 권장 배치(DEVICE_VALIDATION §5 최적 뷰) · 앱 기본 페이스(WorkoutPacing)
EXERCISES = [
    {"app": "바벨 스쿼트", "aihub": "바벨 스쿼트", "place": "C", "placeName": "정면", "pace": 4},
    {"app": "런지", "aihub": "스텝 포워드 다이나믹 런지", "place": "B", "placeName": "앞 비스듬히 · 사용자 오른쪽", "pace": 4},
    {"app": "덤벨 컬", "aihub": "덤벨 컬", "place": "D", "placeName": "앞 비스듬히 · 사용자 왼쪽", "pace": 3},
]

# 조건 — 무엇을 재려는가(설계 절)와 사용자 지시문
CONDITIONS = {
    "normal": {"reps": 10, "tempo": None, "why": "기본 정확도(§1)",
               "say": "평소 속도로 {reps}회. 휴식이 끝나면 평소처럼 자리로 가서 시작 표지 → 반복 → 끝 표지"},
    "fast": {"reps": 10, "tempo": 1.8, "why": "빠른 템포 — 반복당 샘플 < 6 (§4.6)",
             "say": "빠르게 {reps}회 (반복당 약 {tempo} s — 멈추지 말고 이어서)"},
    "slow": {"reps": 8, "tempo": 6.0, "why": "느린 템포 — 옛 4 s 창이 무너지던 구간(§13), 8 s 절벽 안쪽",
             "say": "천천히 {reps}회 (반복당 약 {tempo} s — 내려가기 3 s, 올라오기 3 s)"},
    "pause": {"reps": 10, "tempo": None, "why": "마지막 반복 전 5 s 멈춤(§13 hold5)",
              "say": "{reps}회. 9회째를 마친 뒤 시작 자세(선 자세·팔 편 자세)로 5 s 멈췄다가 마지막 1회"},
    "prep": {"reps": 10, "tempo": None, "why": "세트 앞 준비 동작 헛카운트(§14 — 가장 큰 위험)",
             "say": "WORK 가 시작되면 먼저 준비 동작 약 8 s(스쿼트·런지: 발 위치 고치기 + 얕게 한두 번 굽혀 보기 / "
                    "컬: 바닥이나 받침에서 덤벨 집어 들고 한두 번 들어 올려 그립 확인) → 시작 표지 → {reps}회"},
    "short": {"reps": 3, "tempo": None, "why": "짧은 세트 — 시작 확정(첫 쌍)의 비용(§13 trunc)",
              "say": "{reps}회만 하고 끝 표지"},
    "negative": {"reps": 0, "tempo": None, "why": "반복 없는 세트 경계 행동 — 모든 카운트가 헛카운트(§14)",
                 "say": "반복하지 않는다. 약 20 s 동안: 폰 쪽으로 걸어갔다 돌아오기, 덤벨(또는 물병) 집었다 내려놓기, "
                        "스트레칭 한 번 → ✓"},
    "veryslow": {"reps": 5, "tempo": 9.0, "why": "8 s 절벽 확인(§13.4, 결정 표 #14·#17) — 알려진 한계라 판정에서 뺀다",
                 "say": "아주 천천히 {reps}회 (반복당 약 {tempo} s)"},
}

# 게이트별 종목당 조건 구성(사람 한 명 기준)
MIX = {
    "pilot": {"normal": 1, "fast": 1, "slow": 1, "pause": 1, "prep": 1},
    "gateA": {"normal": 5, "fast": 2, "slow": 2, "pause": 2, "prep": 2, "short": 1, "negative": 1},
    "gateB": {"normal": 7, "fast": 1, "slow": 1, "pause": 1, "prep": 2, "negative": 1},
}
FIELDS = ["plan_id", "gate", "person", "block", "set_in_block", "exercise_app", "exercise", "placement", "condition",
          "planned_reps", "tempo_s_per_rep", "optional", "instructions", "tally_reps", "set_id", "skip", "notes"]


def make_plan(gate: str, persons: int, seed: int = 20260924, with_veryslow: bool = False) -> list[dict]:
    """사람마다 종목 블록 순서를 돌리고(라틴 방진), 블록 안의 조건 순서를 무작위로 섞는다. 블록 = 앱에서 한 운동(여러 세트)."""
    rows = []
    for p in range(persons):
        rng = random.Random(f"{seed}-{gate}-{p}")
        order = [EXERCISES[(p + k) % len(EXERCISES)] for k in range(len(EXERCISES))]
        for b, ex in enumerate(order, 1):
            conds = [c for c, n in MIX[gate].items() for _ in range(n)]
            rng.shuffle(conds)
            if gate != "pilot" and conds and conds[0] == "negative":
                # 음성 세트가 블록 첫 세트(준비 카운트다운 뒤)면 '휴식 뒤 바로 시작' 조건을 재지 못한다 — 뒤로 돌린다
                conds.append(conds.pop(0))
            if with_veryslow and gate == "gateA":
                conds.append("veryslow")
            for k, c in enumerate(conds, 1):
                cd = CONDITIONS[c]
                rows.append({
                    "plan_id": f"{gate}-P{p + 1}-B{b}-{k:02d}", "gate": gate, "person": f"P{p + 1}", "block": b, "set_in_block": k,
                    "exercise_app": ex["app"], "exercise": ex["aihub"], "placement": f"{ex['place']} ({ex['placeName']})",
                    "condition": c, "planned_reps": cd["reps"], "tempo_s_per_rep": cd["tempo"] or ex["pace"],
                    "optional": "yes" if c == "veryslow" else "",
                    "instructions": cd["say"].format(reps=cd["reps"], tempo=cd["tempo"] or ex["pace"]),
                    "tally_reps": "", "set_id": "", "skip": "", "notes": ""})
    return rows


def write_csv(rows: list[dict], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as h:
        w = csv.DictWriter(h, fieldnames=FIELDS)
        w.writeheader()
        for r in rows:
            w.writerow({k: r.get(k, "") for k in FIELDS})


def per_person_counts(gate: str) -> tuple[int, int]:
    n = sum(MIX[gate].values())
    return n, n * len(EXERCISES)


def doc() -> str:
    need = [sets_needed_for_lower(0.90, k) for k in range(4)]
    over0 = sets_needed_for_upper(0.02, 0)
    lead0, lead1 = sets_needed_for_upper(0.05, 0), sets_needed_for_upper(0.05, 1)
    a_ex, a_all = per_person_counts("gateA")
    b_ex, b_all = per_person_counts("gateB")
    b_main = b_ex - MIX["gateB"].get("negative", 0)
    pilot = make_plan("pilot", 1)
    L = ["# 휴대폰 렙 카운트 검증 프로토콜 — 파일럿 · Gate A · Gate B (설계 §7·§14)", "",
         "- 생성: `rep_validation_plan.py` (필요 세트 수는 `score_phone_reps.py` 의 Clopper-Pearson 계산 — 이 표를 손으로 고치지 말 것)",
         "- 대상: 바벨 스쿼트(앱 '바벨 스쿼트') · 스텝 포워드 다이나믹 런지(앱 '런지') · 덤벨 컬 — 새 코어(`RepHysteresis`)의 첫 대상 세 종목",
         "- 채점: `setlog_captures.py`(로그 → 피처 캡처) → `score_phone_reps.py`(재생·짝짓기·지표). 재생 파리티는 `run_replay.py`, 잡음은 `phone_noise.py`",
         "- **휴대폰 연결 전에는 실행할 수 없다.** 이 문서와 도구는 연결되는 날 바로 쓰도록 준비해 둔 것이다(2026-09-24 기준 휴대폰 미연결).", "",
         "## 0. 무엇을 재나", "",
         "| 단계 | 사람 × 세트 | 빌드 | 목적 | 보고 방식 |", "|---|---|---|---|---|",
         f"| 파일럿 | 1명 × {len(pilot)}세트 (종목당 {sum(MIX['pilot'].values())}) | 개발 빌드 | 절차·로그·짝짓기·재생 파리티가 도는지. 세트 경계 크기의 첫 감 | 숫자 보고 안 함 |",
         f"| Gate A | 2~3명 × {a_all}세트 (종목당 {a_ex}) | 개발 빌드, 새 코어 켬 또는 그림자 | 튜닝·귀속: 세트 앞뒤 헛카운트, 튐 빈도, 추론 간격·발열, 런지 두 신호, 느린 템포 | 점추정 + 원인. 성능 주장 아님 |",
         f"| Gate B | 5명 이상(Gate A 불참) × {b_all}세트 (종목당 {b_ex}, 음성 {MIX['gateB'].get('negative', 0)} 포함) | **고정 빌드**(해시 기록) | 판정 | Clopper-Pearson 단측 95%, 사람별 |", "",
         "새 코어는 앱에서 꺼져 있다(`RepSignals` 의 세 종목 `polarity = null`). 파일럿·Gate A 는 개발 빌드에서 켜거나, 레거시와 나란히 그림자로 돌려 **둘 다 로그**한다.",
         "어느 쪽이든 로그의 프레임 피처로 두 카운터를 다시 셀 수 있으므로(`run_replay.py` 의 live · hysteresis) 앱이 무엇을 보여 줬는지와 독립으로 비교된다.", "",
         "## 1. 판정 기준과 필요한 세트 수 (설계 §1·§7 — Gate B 에서만 판정)", "",
         "| 지표 | 기준 | 필요한 세트 수 (종목별) |", "|---|---|---|",
         f"| 정확 일치 하한 | ≥ 0.90 | 틀린 세트 0개 {need[0]} · 1개 {need[1]} · 2개 {need[2]} · 3개 {need[3]} |",
         f"| 과다 카운트 상한 | ≤ 2% | 0개여도 {over0} — 현실적이지 않으므로 **관측 수 + 상한을 함께 보고**(예: 60세트 0개 → 상한 {cp_upper(0, 60):.1%}) |",
         f"| 세트 앞 헛카운트가 있는 세트 상한 | ≤ 5% | 0개 {lead0} · 1개 {lead1} |",
         "| 사람별 정확 일치 점추정 | 모두 ≥ 0.80 | 사람별 표 |",
         "| 한 사람의 비중 | ≤ 세트의 30% | 5명이면 사람당 20% |", "",
         f"Gate B 구성(사람당 종목별 판정 세트 {b_main}) × 5명 = 종목당 {b_main * 5}세트 → 정확 일치는 **틀린 세트 1개까지**({need[1]}) 하한 0.90 을 넘고, "
         f"세트 앞 헛카운트 기준은 0개일 때({lead0}) 넘는다. 사람이 더 필요하면 6명(종목당 {b_main * 6}세트)으로 늘린다.",
         f"예: 종목당 60세트 중 59 정확 → 하한 {cp_lower(59, 60):.3f}, 58 정확 → {cp_lower(58, 60):.3f}(미달).", "",
         "## 2. 준비", "",
         "1. 앱: 세 운동을 한 세션에 넣는다 — '바벨 스쿼트', '런지', '덤벨 컬'. 목표 횟수는 **99회**(목표 도달 자동 진행이 절대 일어나지 않게 — 세트는 ✓ 로만 끝낸다),",
         "   세트 수 = 계획표 블록의 세트 수, 휴식 60 s(스쿼트 기본 120 s 는 줄인다 — 휴식 길이가 아니라 '휴식 카운트다운이 끝난 직후' 가 측정 대상이다).",
         "2. **음성 끄기**(음소거). 음성 코칭은 사용자의 동작을 바꾸고(원칙 #6) 반복 템포를 흔든다.",
         "3. 폰: 허리 높이, 세로, 사용자에게서 2.5~3 m, 전신이 화면에 들어오게, 삼각대. 배치는 계획표의 `placement`(DEVICE_VALIDATION §2 정의, 좌우는 사용자 기준).",
         "4. 독립 집계자: 다른 사람이 앱 화면을 보지 않고 반복마다 집계표에 표시한다. 혼자라면 두 번째 폰으로 영상을 찍고 나중에 영상에서 센다.",
         "   집계는 '완전히 내려갔다 올라온 1회' 를 1회로. 교대 종목은 **한쪽 1회 = 1회** — 런지는 **한 걸음(앞으로 딛고 돌아옴) = 1회**",
         "   (왼·오른 5+5 = 10), 교대 컬은 한 팔 1회 = 1회, 양팔 동시는 1회(설계 §4.4, 앱 준비 안내의 정의와 같다). 애매한 반복은 `notes` 에 적는다.",
         "5. 계획표 CSV(`--csv`)를 인쇄하거나 태블릿에 띄운다. **CSV 순서대로** 찍는다 — 채점기는 같은 종목의 로그를 시각 순서로 계획 행과 짝짓는다.", "",
         "## 3. 운영 규칙 (모든 세트)", "",
         "| 규칙 | 이유 |", "|---|---|",
         "| 목표 99회, 세트는 끝 표지 뒤 ✓ 로만 끝낸다 | 자동 진행(§42)이 카운트를 끊으면 세트 뒤 헛카운트·마지막 반복을 못 잰다 |",
         "| WORK 중 일시정지·카메라 전환·화면 이탈 금지 | 둘 다 `resetCycle()` 로 진행 중 반복(새 코어는 확정 대기 사이클도)을 버린다 — 측정하려는 조건이 아니다. spec §58 빌드는 리셋을 `reps.resets`(누른 시각 + 직전에 처리한 프레임 `after_t_ms`)에 남겨 재생이 앱과 같은 순서로 따라가지만, 그 이전 빌드는 남기지 않아 재생 파리티가 깨진다 |",
         "| 시작 표지: 첫 반복 직전 시작 자세로 **3 s 가만히** | 실제 반복 구간의 시작을 신호에서 찾는 기준. 준비 동작은 그 **앞**에 한다 |",
         "| 끝 표지: 마지막 반복 뒤 **3 s 가만히** → ✓ | 세트 뒤 헛카운트 창의 기준. ✓ 는 표지 뒤 바로 |",
         "| 휴식이 끝나면 평소처럼 시작한다 | 2세트부터는 준비 카운트다운이 없다(`buildSessionSteps`) — 그 '자연스러운 시작' 이 §14 의 측정 대상 |",
         "| 완료 화면에서 실제 횟수 입력 = 집계표 값 | 스테퍼로 맞추면 `edited`, 앱 숫자와 같으면 '맞아요'(`confirmed`, 순환 — 채점은 집계표를 정답으로 쓴다) |",
         "| 망친 세트는 같은 조건으로 바로 다시 | 첫 시도의 set_id 를 `skip` 에 적거나 `--skip` 으로 뺀다 |",
         "| 집계 칸(`tally_reps`)은 세트 직후 채운다 | 기억에 의존하지 않는다 |", "",
         "시작 표지의 한계: 3 s 정지는 레거시 카운터(`ReturnRepTracker`)의 기준 자세를 도와 레거시를 실제 사용보다 좋게 보이게 한다. "
         "새 코어는 정지를 요구하지 않으므로 Gate B(새 코어 판정)에는 영향이 작다. 레거시와의 비교를 읽을 때 이 편향을 기억한다.", "",
         "## 4. 조건", "", "| 조건 | 계획 횟수 | 템포 | 재려는 것 | 지시 |", "|---|---:|---|---|---|"]
    for c, cd in CONDITIONS.items():
        tempo = f"{cd['tempo']} s/회" if cd["tempo"] else ("—" if cd["reps"] == 0 else "앱 페이스")
        L.append(f"| {c} | {cd['reps']} | {tempo} | {cd['why']} | "
                 f"{cd['say'].format(reps=cd['reps'], tempo=cd['tempo'] or '—')} |")
    L += ["", "블록 = 앱의 한 운동(같은 종목 여러 세트). 블록 첫 세트만 준비 카운트다운 뒤에 시작한다. 음성(negative) 세트는 블록 첫 세트가 되지 않게 섞는다.",
          "사람마다 종목 블록 순서를 돌린다(P1 스쿼트→런지→컬, P2 런지→컬→스쿼트, …). 조건 순서는 블록 안에서 무작위(시드 고정).", "",
          "## 5. 파일럿 계획 (1명 × 15세트) — 이 순서대로", "",
          "| # | 블록·세트 | 종목(앱) | 배치 | 조건 | 계획 | 지시 |", "|---|---|---|---|---|---:|---|"]
    for i, r in enumerate(pilot, 1):
        L.append(f"| {i} | B{r['block']}-{r['set_in_block']} | {r['exercise_app']} | {r['placement']} | {r['condition']} | {r['planned_reps']} | {r['instructions']} |")
    L += ["", "파일럿 확인 목록: 세트마다 로그 1줄이 생기는가, `setlog_captures.py` 가 `reps` 블록과 피처를 읽는가, `run_replay.py` 의 live 카운트가 "
          "앱이 로그한 카운트와 일치하는가(파리티 표), 시작·끝 표지가 경계로 잡히는가(`score_phone_reps.py` 의 '경계를 찾지 못한 세트' 0), "
          "WORK 시작부터 첫 반복까지 몇 초인가.", "",
          f"## 6. Gate A (2~3명 × {a_all}세트)", "",
          "| 조건 | 종목당 세트 (사람 한 명) |", "|---|---:|"]
    L += [f"| {c} | {n} |" for c, n in MIX["gateA"].items()]
    L += ["| veryslow (선택, `--with-veryslow`) | 1 — 판정에서 뺀다 |", "",
          f"사람당 약 {a_all}세트 × (작업 ~1 분 + 휴식 1 분) ≈ {round(a_all * 2 / 60 * 1.1, 1)} 시간. 한 번에 다 하기 어려우면 블록 단위로 나눠 찍는다(블록 중간에 끊지 않는다).",
          "나가는 조건(설계 §7): 세 종목 모두 §1 지표의 점추정이 기준을 넘고, 놓침·헛카운트마다 원인이 붙어 있다. 원인 귀속에는 로그 재생(`run_replay.py --series`)과",
          "잡음 통계(`phone_noise.py`: 추론 간격·발열 대리 지표·휴식 σ·튐)를 쓴다. 이 단계에서 상수·정책을 바꿀 수 있으므로 **결과를 성능으로 보고하지 않는다**.",
          "Gate A 가 가장 먼저 답할 것(§14): WORK 시작 → 첫 실제 반복 시간, 그 사이 발표된 반복 수, 마지막 반복 뒤 발표된 반복 수.", "",
          f"## 7. Gate B (5명 이상 × {b_all}세트, 고정 빌드)", "",
          "| 조건 | 종목당 세트 (사람 한 명) |", "|---|---:|"]
    L += [f"| {c} | {n} |" for c, n in MIX["gateB"].items()]
    L += ["", "- 코드·상수·정책을 고정하고 빌드 해시를 계획표 `notes` 첫 행에 적는다. 판정 절차(이 문서 §1)는 수집 **전에** 확정한다 — 결과를 보고 바꾸지 않는다.",
          "- 참여자는 Gate A 에 참여하지 않은 사람. 한 사람이 세트의 30% 를 넘지 않는다.",
          "- 채점: `python score_phone_reps.py <logs> --plan <gateB.csv> --gate B --headline hysteresis --out <결과>`.",
          "  판정 표는 정확 하한·과다 상한(관측 수 함께)·세트 앞 헛카운트 상한·사람별 정확·사람 비중을 한 줄에 싣고, 세트가 모자라면 '판정 불가(세트 부족)' 로 적는다.",
          "- 통과 전까지 카운트는 beta 다(원칙 #2). 통과해도 새 코어를 켜는 결정과 세트 경계 해법(§14 a~f)은 사용자 결정이다(결정 표 #13·#15).", "",
          "## 8. 회수와 채점", "", "```bash",
          "# 회수 (adb 경로는 pull_logs.py 에 하드코딩 — 환경에 맞게)",
          "python ../aihub_fitness/pull_logs.py",
          "# 로그 → 피처 캡처 + 재생 파리티(앱 카운트와 지금 빌드 재생의 일치)",
          "python setlog_captures.py ../aihub_fitness/outputs/logs --out ../../data/phone_rep/captures --since 2026-10-01 --session-only",
          "python run_replay.py ../../data/phone_rep/captures --out ../../data/phone_rep/replay",
          "# 채점 (계획표의 tally_reps 를 채운 뒤)",
          "python score_phone_reps.py ../aihub_fitness/outputs/logs --plan ../../data/phone_rep/plan_gateA.csv --gate A --out ../../data/phone_rep/score_gateA",
          "# 잡음 (추론 간격·발열 대리·휴식 σ·튐·끊김)",
          "python phone_noise.py ../aihub_fitness/outputs/logs --out ../../data/phone_rep/noise",
          "```", "",
          "## 9. 이 프로토콜이 답하지 못하는 것", "",
          "- 다른 폰·다른 장소·다른 조명. 한 폰에서 모은 Gate B 는 그 폰의 판정이다(`delegate`·추론 간격은 로그에 남는다).",
          "- ROM(깊이) 판정의 맞음. 이 프로토콜은 **횟수**만 본다 — ROM 은 재보정(스펙 §9) 전까지 '참고' 다(설계 §4.5).",
          "- 1회 세트: 시작 확정 정책은 1회 세트를 구조적으로 0회로 센다(설계 §13). 수용할지는 사용자 결정(결정 표 #16)이라 여기서 재지 않는다.",
          "- 반복당 8 s 를 넘는 템포: `veryslow`(선택)로 크기만 본다. 창 확장은 다음 사전 등록(결정 표 #14)."]
    return "\n".join(L) + "\n"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--csv", choices=("pilot", "gateA", "gateB"), default=None, help="계획표 CSV 를 만든다")
    ap.add_argument("--persons", type=int, default=None)
    ap.add_argument("--seed", type=int, default=20260924)
    ap.add_argument("--with-veryslow", action="store_true")
    ap.add_argument("--out-dir", type=Path, default=DEFAULT_OUT)
    ap.add_argument("--doc", type=Path, default=DOC, help="문서 경로 (기본 REP_VALIDATION.md)")
    args = ap.parse_args()
    if args.csv:
        persons = args.persons or {"pilot": 1, "gateA": 3, "gateB": 5}[args.csv]
        rows = make_plan(args.csv, persons, args.seed, args.with_veryslow)
        path = args.out_dir / f"plan_{args.csv}.csv"
        write_csv(rows, path)
        print(f"{len(rows)} rows -> {path}")
        return 0
    args.doc.write_text(doc(), encoding="utf-8")
    print(f"wrote {args.doc}")
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    raise SystemExit(main())
