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

# 앱 이름(postureExerciseMap 키) · AIHub 종목 · 권장 배치(DEVICE_VALIDATION §5 최적 뷰) · 카운터 사이클당 페이스(s) ·
# 표시 단위(앱 ExerciseProfile.repUnit — 사용자 결정 2026-09-24: 런지류는 "왼쪽과 오른쪽을 한 번씩 = 1회", 사이클 둘 = 1회).
# 런지의 pace 4 s 는 **한 걸음**(카운터 사이클 하나)이다 — 앱 WorkoutPacing 은 1회(좌우 한 쌍)를 4 × 2 = 8 s 로 잡는다.
EXERCISES = [
    {"app": "바벨 스쿼트", "aihub": "바벨 스쿼트", "place": "C", "placeName": "정면", "pace": 4, "unit": "cycle"},
    {"app": "런지", "aihub": "스텝 포워드 다이나믹 런지", "place": "B", "placeName": "앞 비스듬히 · 사용자 오른쪽", "pace": 4,
     "unit": "side_pair"},
    {"app": "덤벨 컬", "aihub": "덤벨 컬", "place": "D", "placeName": "앞 비스듬히 · 사용자 왼쪽", "pace": 3, "unit": "cycle"},
]
UNIT_CYCLES = {"cycle": 1, "side_pair": 2}

# 조건 — 무엇을 재려는가(설계 절)와 사용자 지시문. reps 는 **카운터 사이클** 수(런지 = 걸음 수)다 — 몸의 부담을 종목 사이에 같게 두고
# MM-Fit 연구 수치(세트당 약 10걸음)와 견줄 수 있게. 화면 수(planned_reps)는 사이클 // 표시 단위(런지 10걸음 = 5회).
# 지시문 자리: {n} = 횟수 말("10회" / "10걸음(왼·오른 번갈아 — 앱 표시 5회)"), {per} = "반복"/"걸음", {tempo} = 템포.
# say_side 가 있으면 좌우 짝 종목(런지)에 그 문장을 쓴다.
CONDITIONS = {
    "normal": {"reps": 10, "tempo": None, "why": "기본 정확도(§1)",
               "say": "평소 속도로 {n}. 휴식이 끝나면 평소처럼 자리로 가서 시작 표지 → 반복 → 끝 표지"},
    "fast": {"reps": 10, "tempo": 1.8, "why": "빠른 템포 — 반복당 샘플 < 6 (§4.6)",
             "say": "빠르게 {n} ({per}당 약 {tempo} s — 멈추지 말고 이어서)"},
    "slow": {"reps": 8, "tempo": 6.0, "why": "느린 템포 — 옛 4 s 창이 무너지던 구간(§13), 8 s 절벽 안쪽",
             "say": "천천히 {n} ({per}당 약 {tempo} s — 내려가기 3 s, 올라오기 3 s)"},
    "pause": {"reps": 10, "tempo": None, "why": "마지막 반복 전 5 s 멈춤(§13 hold5). 런지는 마지막 쌍의 두 쪽 사이 멈춤 — 반쪽이 멈춤을 건너 이어지는가",
              "say": "{n}. 9회째를 마친 뒤 시작 자세(선 자세·팔 편 자세)로 5 s 멈췄다가 마지막 1회",
              "say_side": "{n}. 9걸음째(마지막 쌍의 첫 쪽)를 마친 뒤 선 자세로 5 s 멈췄다가 마지막 한 걸음(반대쪽) — "
                          "이 멈춤은 한 쌍의 두 쪽 사이다"},
    "prep": {"reps": 10, "tempo": None, "why": "세트 앞 준비 동작 헛카운트(§14 — 가장 큰 위험). 런지는 헛사이클이 홀수면 짝의 위상이 한 걸음 밀린다",
             "say": "WORK 가 시작되면 먼저 준비 동작 약 8 s(스쿼트·런지: 발 위치 고치기 + 얕게 한두 번 굽혀 보기 / "
                    "컬: 바닥이나 받침에서 덤벨 집어 들고 한두 번 들어 올려 그립 확인) → 시작 표지 → {n}"},
    "short": {"reps": 3, "tempo": None, "why": "짧은 세트 — 시작 확정(첫 쌍)의 비용(§13 trunc). 런지는 짝 없이 끝나는 한쪽(세지 않음)도 함께 잰다",
              "say": "{n}만 하고 끝 표지"},
    "negative": {"reps": 0, "tempo": None, "why": "반복 없는 세트 경계 행동 — 모든 카운트가 헛카운트(§14)",
                 "say": "반복하지 않는다. 약 20 s 동안: 폰 쪽으로 걸어갔다 돌아오기, 덤벨(또는 물병) 집었다 내려놓기, "
                        "스트레칭 한 번 → ✓"},
    "veryslow": {"reps": 5, "tempo": 9.0, "why": "8 s 절벽 확인(§13.4, 결정 표 #14·#17) — 알려진 한계라 판정에서 뺀다",
                 "say": "아주 천천히 {n} ({per}당 약 {tempo} s)"},
    "sideblock": {"reps": 10, "tempo": None, "only": "side_pair", "alternate": False,
                  "why": "한쪽 몰아 하기 — 앱의 짝은 번갈아 한다는 가정 위에 있다(README §11 Q2). 세트 끝 수는 맞아야 하고 "
                         "'반대쪽 차례' 는 블록 중간에 틀린다. 번갈아 하지 않는 사용자를 재는 조건이라 판정에서 뺀다",
                  "say": "{n}",
                  "say_side": "한쪽 다리로만 {half}걸음을 이어서 → 반대쪽 {half}걸음 = {n}. 집계자는 걸음 순서를 적는다(예: RRRRRLLLLL)"},
    # 번갈아 하는 컬 — 앱은 컬을 사이클 = 1회(두 팔 평균 신호)로 세고 짝을 짓지 않지만, 사용자 정의(좌우 한 번씩 = 1회)로는 쌍이 정답이다.
    # 판정 세트(양팔 동시)가 재지 않는 사용 방식을 Gate A 에서 크기만 잰다(설계 §15 #25). tally = "side_pair": 팔별 집계, 계획 쌍 = 팔 반복 // 2.
    "altcurl": {"reps": 10, "tempo": None, "only": "덤벨 컬", "tally": "side_pair",
                "why": "번갈아 하는 컬 — 앱은 컬을 두 팔 평균 신호의 사이클 = 1회로 세고 쪽을 가리지 못한다(README §11 Q3). 사용자 정의"
                       "(좌우 한 번씩 = 1회 = min(왼팔, 오른팔))와 앱 표시가 얼마나 다른지 크기만 잰다(설계 §15 #25) — 판정에서 뺀다",
                "say": "{n}",
                "say_tally": "양팔을 번갈아 {n}. 집계자는 팔별로 센다 — 사용자 기준 왼팔 `tally_left`, 오른팔 `tally_right`, 순서 `tally_order`"
                             "(예: LRLRLRLRLR). 완료 화면에는 min(왼팔, 오른팔) 을 넣는다"},
}

# 게이트별 종목당 조건 구성(사람 한 명 기준)
MIX = {
    "pilot": {"normal": 1, "fast": 1, "slow": 1, "pause": 1, "prep": 1},
    "gateA": {"normal": 5, "fast": 2, "slow": 2, "pause": 2, "prep": 2, "short": 1, "negative": 1},
    "gateB": {"normal": 7, "fast": 1, "slow": 1, "pause": 1, "prep": 2, "negative": 1},
}
# 표시 단위별 추가 조건(그 단위의 종목에만) — 좌우 짝: Gate A 에 한쪽 몰아 하기 1세트
EXTRA = {"side_pair": {"gateA": {"sideblock": 1}}}
# 종목별 추가 조건(앱 이름) — 덤벨 컬: Gate A 에 번갈아 하는 컬 1세트(판정 세트는 양팔 동시 — §2)
EXTRA_BY_APP = {"덤벨 컬": {"gateA": {"altcurl": 1}}}
# planned_reps = 화면 수(표시 단위), planned_cycles = 카운터 사이클(런지 = 걸음). tempo_s_per_rep 은 사이클당(런지는 걸음당).
# 집계: tally_reps = 화면 단위의 독립 집계(스쿼트·컬). 런지는 tally_left·tally_right(왼·오른 걸음 수)를 채우고, 영상으로 셀 때는
# tally_order(걸음 순서, 사용자 기준 L/R — 예 RLRLRLRLRL)도 적는다. 런지의 tally_reps 는 비워 둔다(채우면 옛 규약 = 걸음 수로 읽는다).
FIELDS = ["plan_id", "gate", "person", "block", "set_in_block", "exercise_app", "exercise", "placement", "condition",
          "rep_unit", "planned_reps", "planned_cycles", "tempo_s_per_rep", "optional", "instructions",
          "tally_reps", "tally_left", "tally_right", "tally_order", "set_id", "skip", "notes"]


def count_phrase(unit: str, cycles: int, alternate: bool = True) -> str:
    """지시문의 횟수 말. 좌우 짝은 걸음 수와 앱이 보일 수(짝 없는 한쪽은 세지 않는다)를 함께."""
    if unit != "side_pair":
        return f"{cycles}회"
    pairs, half = divmod(cycles, UNIT_CYCLES[unit])
    return (f"{cycles}걸음(" + ("왼·오른 번갈아 — " if alternate else "") + f"앱 표시 {pairs}회"
            + (" + 세지 않는 한쪽" if half else "") + ")")


def instruction(cond: str, ex: dict) -> str:
    cd = CONDITIONS[cond]
    unit = ex["unit"]
    tempo = cd["tempo"] or ex["pace"]
    if cd.get("tally") == "side_pair" and unit != "side_pair":
        # 앱은 사이클로 세지만 사용자 정의(좌우 한 번씩 = 1회)로 팔별 집계하는 조건 — 번갈아 하는 컬
        pairs = cd["reps"] // UNIT_CYCLES["side_pair"]
        return cd["say_tally"].format(n=f"{cd['reps']}번(왼팔·오른팔 번갈아 — 좌우 한 번씩 = 1회로 {pairs}회. 앱 표시 수는 이 정의를 약속하지 않는다)")
    text = cd.get("say_side") if unit == "side_pair" and cd.get("say_side") else cd["say"]
    return text.format(n=count_phrase(unit, cd["reps"], cd.get("alternate", True)), per="걸음" if unit == "side_pair" else "반복", tempo=tempo,
                       half=cd["reps"] // 2)


def extra_conditions(gate: str, ex: dict) -> dict[str, int]:
    """그 종목의 추가 조건(표시 단위별 + 종목별) — 블록 끝에 붙고 판정에서 빠진다."""
    out = dict(EXTRA.get(ex["unit"], {}).get(gate, {}))
    for c, n in EXTRA_BY_APP.get(ex["app"], {}).get(gate, {}).items():
        out[c] = out.get(c, 0) + n
    return out


def conditions_for(gate: str, ex: dict) -> dict[str, int]:
    mix = dict(MIX[gate])
    for c, n in extra_conditions(gate, ex).items():
        mix[c] = mix.get(c, 0) + n
    return mix


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
            # 추가 조건은 블록 **끝**에 — 한쪽 몰아 하기·번갈아 하는 컬을 해 본 뒤의 세트가 그 습관을 이어받지 않게
            conds += [c for c, n in extra_conditions(gate, ex).items() for _ in range(n)]
            if with_veryslow and gate == "gateA":
                conds.append("veryslow")
            for k, c in enumerate(conds, 1):
                cd = CONDITIONS[c]
                # 계획 화면 수의 단위 — 종목의 표시 단위, 단 팔별로 집계하는 조건(번갈아 하는 컬)은 사용자 정의의 쌍
                cpr = UNIT_CYCLES[cd.get("tally") or ex["unit"]]
                rows.append({
                    "plan_id": f"{gate}-P{p + 1}-B{b}-{k:02d}", "gate": gate, "person": f"P{p + 1}", "block": b, "set_in_block": k,
                    "exercise_app": ex["app"], "exercise": ex["aihub"], "placement": f"{ex['place']} ({ex['placeName']})",
                    "condition": c, "rep_unit": ex["unit"], "planned_reps": cd["reps"] // cpr, "planned_cycles": cd["reps"],
                    "tempo_s_per_rep": cd["tempo"] or ex["pace"],
                    "optional": "yes" if c == "veryslow" else "",
                    "instructions": instruction(c, ex),
                    "tally_reps": "", "tally_left": "", "tally_right": "", "tally_order": "", "set_id": "", "skip": "", "notes": ""})
    return rows


def write_csv(rows: list[dict], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as h:
        w = csv.DictWriter(h, fieldnames=FIELDS)
        w.writeheader()
        for r in rows:
            w.writerow({k: r.get(k, "") for k in FIELDS})


def per_person_counts(gate: str) -> tuple[int, int]:
    """(종목당 세트 — 표시 단위 추가 조건을 뺀 공통 수, 사람당 전체 세트)."""
    n = sum(MIX[gate].values())
    return n, sum(sum(conditions_for(gate, ex).values()) for ex in EXERCISES)


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
         "- **런지의 횟수 단위(사용자 결정 2026-09-24, spec §59)**: 앱은 런지를 **왼쪽과 오른쪽을 한 번씩 = 1회**로 보인다(`RepUnit.SIDE_PAIR`). 카운터는 여전히 "
         "걸음마다 한 사이클을 세고(무릎 신호가 걸음마다 한 번 내려간다), 앱이 두 사이클을 1회로 묶는다 — 한쪽만 했을 때는 수가 오르지 않고 화면에 "
         "'반대쪽 차례' 가 뜨며, 반대쪽까지 해야 1회가 오른다. 목표 도달 자동 진행(spec §42)은 유지되므로 세트는 두 쪽이 모두 목표에 닿은 뒤 넘어간다. "
         "앱은 **어느 다리인지 모른다** — 번갈아 한다는 가정 위에서 연속한 두 걸음을 짝짓는다. 그래서 이 프로토콜은 런지를 두 단위로 잰다: "
         "걸음(카운터 사이클 — 카운터 자체의 정확도)과 쌍(화면 수 — 사용자가 본 수이자 자동 진행이 쓴 수). 집계는 왼·오른 걸음을 따로 센다(§2).",
         "- 채점: `setlog_captures.py`(로그 → 피처 캡처) → `score_phone_reps.py`(재생·짝짓기·지표). 재생 파리티는 `run_replay.py`, 잡음은 `phone_noise.py`",
         "- **휴대폰 연결 전에는 실행할 수 없다.** 이 문서와 도구는 연결되는 날 바로 쓰도록 준비해 둔 것이다(2026-09-24 기준 휴대폰 미연결).", "",
         "## 0. 무엇을 재나", "",
         "| 단계 | 사람 × 세트 | 빌드 | 목적 | 보고 방식 |", "|---|---|---|---|---|",
         f"| 파일럿 | 1명 × {len(pilot)}세트 (종목당 {sum(MIX['pilot'].values())}) | 개발 빌드 | 절차·로그·짝짓기·재생 파리티가 도는지. 세트 경계 크기의 첫 감 | 숫자 보고 안 함 |",
         f"| Gate A | 2~3명 × {a_all}세트 (종목당 {a_ex}, 런지 +{sum(EXTRA['side_pair']['gateA'].values())}, 덤벨 컬 +{sum(EXTRA_BY_APP['덤벨 컬']['gateA'].values())}) | 개발 빌드, 새 코어 켬 또는 그림자 | 튜닝·귀속: 세트 앞뒤 헛카운트, 튐 빈도, 추론 간격·발열, 런지 두 신호, 느린 템포, 번갈아 하는 컬의 크기 | 점추정 + 원인. 성능 주장 아님 |",
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
         "**런지는 두 단위 모두 판정표에 싣고, 둘 다 통과해야 통과로 읽는다.** 쌍 단위 정답은 집계의 min(왼, 오른)(사용자 정의 '좌우 한 번씩 = 1회'), "
         "예측은 사이클 // 2(앱 누적기와 같다 — 세트 안에서 반쪽을 버리지 않는다). 쌍 단위만 통과하는 것은 내림이 한 걸음의 오차를 **숨긴** 것일 수 있다"
         "(README §11 Q1: MM-Fit 새 코어에서 쌍 단위 이득은 전부 한 걸음 과다를 숨긴 것이었고, 지금 앱 경로에서는 한 걸음 과소를 숨긴 세트도 있었다). "
         "쌍 단위 표에는 두 가지를 더 싣는다 — **위상 밀림**(세트 앞 헛사이클이 홀수라 "
         "세트 내내 '반대쪽 차례' 가 한 걸음 어긋나는 세트, CP 상한과 함께)과 **조기 자동 진행**(목표가 정답 쌍 수였다면 마지막 실제 걸음이 시작되기 "
         "전에 목표에 닿았을 세트 — 목표 99 로 찍으므로 반사실 계산이다). 걸음 단위 표에도 같은 반사실 조기 자동 진행을 싣는다.", "",
         "## 2. 준비", "",
         "1. 앱: 세 운동을 한 세션에 넣는다 — '바벨 스쿼트', '런지', '덤벨 컬'. 목표 횟수는 **99회**(목표 도달 자동 진행이 절대 일어나지 않게 — 세트는 ✓ 로만 끝낸다.",
         "   런지의 99회는 99쌍 = 198걸음이다 — 자동 진행은 두 쪽이 모두 목표에 닿아야 일어나므로 여전히 일어나지 않는다),",
         "   세트 수 = 계획표 블록의 세트 수, 휴식 60 s(스쿼트 기본 120 s 는 줄인다 — 휴식 길이가 아니라 '휴식 카운트다운이 끝난 직후' 가 측정 대상이다).",
         "2. **음성 끄기**(음소거). 음성 코칭은 사용자의 동작을 바꾸고(원칙 #6) 반복 템포를 흔든다.",
         "3. 폰: 허리 높이, 세로, 사용자에게서 2.5~3 m, 전신이 화면에 들어오게, 삼각대. 배치는 계획표의 `placement`(DEVICE_VALIDATION §2 정의, 좌우는 사용자 기준).",
         "4. 독립 집계자: 다른 사람이 앱 화면을 보지 않고 반복마다 집계표에 표시한다. 혼자라면 두 번째 폰으로 영상을 찍고 나중에 영상에서 센다.",
         "   집계는 '완전히 내려갔다 올라온 1회' 를 1회로 — 스쿼트·컬은 `tally_reps` 에.",
         "   **런지는 왼·오른 걸음을 따로 센다**: 한 걸음 = 앞으로 딛고 돌아옴, 디딘 다리(사용자 기준 왼/오른)로 `tally_left`·`tally_right` 에 적는다.",
         "   영상으로 셀 때는 걸음 순서도 `tally_order` 에 적는다(예: `RLRLRLRLRL`, 사용자 기준). 채점은 걸음 정답 = 왼 + 오른(카운터 사이클, 로그의 `reps.count` 와 견준다),",
         "   쌍 정답 = min(왼, 오른)(앱 정의 '왼쪽과 오른쪽을 한 번씩 = 1회' 그대로 — 번갈아 했다면 화면 수와 같고, 한쪽이 더 많으면 남는 걸음은 쌍이 아니다).",
         "   순서는 앱이 쓰지 않는다 — 나중에 걸음별 좌우 판별(README §11 Q2)을 이 순서로 맞춰 보기 위한 것이다. 런지의 `tally_reps` 는 비워 둔다(채우면 옛 규약 = 걸음 수로 읽는다).",
         "   덤벨 컬의 판정 세트는 **양팔 동시**로 한다: 두 팔이 함께 한 번 = 1회. 앱의 덤벨 컬은 두 팔 평균 신호(elbow_mean)의 사이클 하나를 1회로 세고 좌우 짝을 짓지 않는다",
         "   (양팔 동시면 이미 '좌우 한 번씩 = 1회' 와 같다). **번갈아 하는 컬은 Gate A 의 `altcurl` 1세트로만** 잰다 — 팔별로 `tally_left`·`tally_right`(사용자 기준)에 세고,",
         "   정답은 사용자 정의대로 min(왼팔, 오른팔) 쌍이다(팔 반복 합은 참고). 앱이 쪽을 가리지 못해 판정에서 뺀다 — 그래서 Gate B 의 컬 판정은 **양팔 동시 컬에 대한 판정**이다(§9, 설계 §15 #25).",
         "   애매한 반복은 `notes` 에 적는다.",
         "5. 계획표 CSV(`--csv`)를 인쇄하거나 태블릿에 띄운다. **CSV 순서대로** 찍는다 — 채점기는 같은 종목의 로그를 시각 순서로 계획 행과 짝짓는다.", "",
         "## 3. 운영 규칙 (모든 세트)", "",
         "| 규칙 | 이유 |", "|---|---|",
         "| 목표 99회, 세트는 끝 표지 뒤 ✓ 로만 끝낸다 | 자동 진행(§42)이 카운트를 끊으면 세트 뒤 헛카운트·마지막 반복을 못 잰다. 런지는 99쌍 — 자동 진행은 두 쪽이 모두 목표에 닿아야 하므로 역시 일어나지 않는다 |",
         "| 런지는 평소처럼 왼·오른을 번갈아 한다(`sideblock` 세트만 예외) | 앱의 짝은 번갈아 한다는 가정 위에 있다 — 쪽을 모르고 연속한 두 걸음을 1회로 묶는다 |",
         "| 런지 화면의 '반대쪽 차례' 는 따르지도 무시하지도 않는다 — 계획대로 한다 | 쪽을 모르는 화면 전용 힌트다(음성으로 말하지 않는다). 세트 앞 헛사이클이 홀수면 세트 내내 한 걸음 어긋난다 — 그것이 재려는 것이다 |",
         "| WORK 중 일시정지·카메라 전환·화면 이탈 금지 | 둘 다 `resetCycle()` 로 진행 중 반복(새 코어는 확정 대기 사이클도)을 버린다 — 측정하려는 조건이 아니다. spec §58 빌드는 리셋을 `reps.resets`(누른 시각 + 직전에 처리한 프레임 `after_t_ms`)에 남겨 재생이 앱과 같은 순서로 따라가지만, 그 이전 빌드는 남기지 않아 재생 파리티가 깨진다 |",
         "| 시작 표지: 첫 반복 직전 시작 자세로 **3 s 가만히** | 실제 반복 구간의 시작을 신호에서 찾는 기준. 준비 동작은 그 **앞**에 한다 |",
         "| 끝 표지: 마지막 반복 뒤 **3 s 가만히** → ✓ | 세트 뒤 헛카운트 창의 기준. ✓ 는 표지 뒤 바로 |",
         "| 휴식이 끝나면 평소처럼 시작한다 | 2세트부터는 준비 카운트다운이 없다(`buildSessionSteps`) — 그 '자연스러운 시작' 이 §14 의 측정 대상 |",
         "| 완료 화면에서 실제 횟수 입력 = 집계표 값 | 스테퍼로 맞추면 `edited`, 앱 숫자와 같으면 '맞아요'(`confirmed`, 순환 — 채점은 집계표를 정답으로 쓴다). **런지는 쌍 = min(왼, 오른)** 을 넣는다 — 스테퍼는 화면 단위(쌍)다. 짝 없이 끝난 한쪽은 넣지 않는다(화면도 세지 않았다). 그래서 자가 라벨은 걸음 수를 한 걸음 모른다(`setlog_captures.py` 의 truthCyclesMax) — 걸음 단위 정답은 집계표에서만 나온다 |",
         "| 망친 세트는 같은 조건으로 바로 다시 | 첫 시도의 set_id 를 `skip` 에 적거나 `--skip` 으로 뺀다 |",
         "| 집계 칸(`tally_reps`, 런지는 `tally_left`·`tally_right`)은 세트 직후 채운다 | 기억에 의존하지 않는다 |", "",
         "시작 표지의 한계: 3 s 정지는 레거시 카운터(`ReturnRepTracker`)의 기준 자세를 도와 레거시를 실제 사용보다 좋게 보이게 한다. "
         "새 코어는 정지를 요구하지 않으므로 Gate B(새 코어 판정)에는 영향이 작다. 레거시와의 비교를 읽을 때 이 편향을 기억한다.", "",
         "## 4. 조건", "", "| 조건 | 계획 횟수 | 템포 | 재려는 것 | 지시 |", "|---|---:|---|---|---|"]
    squat = EXERCISES[0]
    by_app = {e["app"]: e for e in EXERCISES}
    for c, cd in CONDITIONS.items():
        tempo = f"{cd['tempo']} s/사이클" if cd["tempo"] else ("—" if cd["reps"] == 0 else "앱 페이스")
        only = cd.get("only")
        say = ("(런지 전용 — 아래 표)" if only == "side_pair" else f"({only} 전용) " + instruction(c, by_app[only])) if only else instruction(c, squat)
        L.append(f"| {c} | {cd['reps']} | {tempo} | {cd['why']} | {say} |")
    L += ["", "계획 횟수는 **카운터 사이클** 수다. 런지는 걸음 수이고 앱 화면 수(`planned_reps`)는 그 절반(내림)이다 — 10걸음 = 5회, 3걸음 = 1회 + 세지 않는 한쪽. "
          "몸의 부담을 종목 사이에 같게 두고 MM-Fit 수치(세트당 약 10걸음)와 견주기 위해서다. 템포(`tempo_s_per_rep`)도 사이클당이다(런지는 걸음당 — 앱 기본 페이스 1회 8 s 의 절반).",
          "",
          "| 조건 | 런지 지시 |", "|---|---|"]
    lunge = next(e for e in EXERCISES if e["unit"] == "side_pair")
    L += [f"| {c} | {instruction(c, lunge)} |" for c in CONDITIONS if c != "negative" and CONDITIONS[c].get("only") in (None, "side_pair")]
    L += ["", "블록 = 앱의 한 운동(같은 종목 여러 세트). 블록 첫 세트만 준비 카운트다운 뒤에 시작한다. 음성(negative) 세트는 블록 첫 세트가 되지 않게 섞는다. "
          "`sideblock`(런지 전용, Gate A)과 `altcurl`(덤벨 컬 전용, Gate A)은 블록 **끝**에 둔다 — 한쪽 몰아 하기·번갈아 하는 컬을 해 본 뒤의 세트가 그 습관을 이어받지 않게. "
          "`altcurl` 의 계획 화면 수(`planned_reps`)는 사용자 정의의 쌍(팔 반복 10번 = 5회)이다 — 앱이 그 수를 보인다는 뜻이 아니다.",
          "사람마다 종목 블록 순서를 돌린다(P1 스쿼트→런지→컬, P2 런지→컬→스쿼트, …). 조건 순서는 블록 안에서 무작위(시드 고정).", "",
          "## 5. 파일럿 계획 (1명 × 15세트) — 이 순서대로", "",
          "| # | 블록·세트 | 종목(앱) | 배치 | 조건 | 계획 (화면 수) | 지시 |", "|---|---|---|---|---|---:|---|"]
    for i, r in enumerate(pilot, 1):
        planned = f"{r['planned_reps']}" + (f" ({r['planned_cycles']}걸음)" if r["rep_unit"] == "side_pair" else "")
        L.append(f"| {i} | B{r['block']}-{r['set_in_block']} | {r['exercise_app']} | {r['placement']} | {r['condition']} | {planned} | {r['instructions']} |")
    L += ["", "파일럿 확인 목록: 세트마다 로그 1줄이 생기는가, `setlog_captures.py` 가 `reps` 블록과 피처를 읽는가, `run_replay.py` 의 live 카운트가 "
          "앱이 로그한 카운트와 일치하는가(파리티 표), 시작·끝 표지가 경계로 잡히는가(`score_phone_reps.py` 의 '경계를 찾지 못한 세트' 0), "
          "WORK 시작부터 첫 반복까지 몇 초인가.", "",
          f"## 6. Gate A (2~3명 × {a_all}세트)", "",
          "| 조건 | 종목당 세트 (사람 한 명) |", "|---|---:|"]
    L += [f"| {c} | {n} |" for c, n in MIX["gateA"].items()]
    L += [f"| {c} (런지만) | {n} — 블록 끝, 판정에서 뺀다 |" for c, n in EXTRA["side_pair"]["gateA"].items()]
    L += [f"| {c} ({app}만) | {n} — 블록 끝, 판정에서 뺀다 |" for app, g in EXTRA_BY_APP.items() for c, n in g.get("gateA", {}).items()]
    L += ["| veryslow (선택, `--with-veryslow`) | 1 — 판정에서 뺀다 |", "",
          f"사람당 약 {a_all}세트 × (작업 ~1 분 + 휴식 1 분) ≈ {round(a_all * 2 / 60 * 1.1, 1)} 시간. 한 번에 다 하기 어려우면 블록 단위로 나눠 찍는다(블록 중간에 끊지 않는다).",
          "나가는 조건(설계 §7): 세 종목 모두 §1 지표의 점추정이 기준을 넘고, 놓침·헛카운트마다 원인이 붙어 있다. 원인 귀속에는 로그 재생(`run_replay.py --series`)과",
          "잡음 통계(`phone_noise.py`: 추론 간격·발열 대리 지표·휴식 σ·튐)를 쓴다. 이 단계에서 상수·정책을 바꿀 수 있으므로 **결과를 성능으로 보고하지 않는다**.",
          "Gate A 가 가장 먼저 답할 것(§14): WORK 시작 → 첫 실제 반복 시간, 그 사이 발표된 반복 수, 마지막 반복 뒤 발표된 반복 수.",
          "런지는 여기에 더해: 세트 앞 헛사이클이 홀수인 세트의 비율(쌍 위상 밀림 — MM-Fit 새 코어 16/62), 사용자가 실제로 번갈아 하는가(`tally_order`), "
          "`sideblock` 에서 세트 끝 쌍 수가 min(왼, 오른)과 같은가. 덤벨 컬은 `altcurl` 에서 앱이 보인 수가 사용자 정의의 쌍 min(왼팔, 오른팔)과 얼마나 다른가"
          "(쌍 단위 표 — 채점기가 팔별 집계가 있는 컬 세트도 싣는다).", "",
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
          "- 반복당 8 s 를 넘는 템포: `veryslow`(선택)로 크기만 본다. 창 확장은 다음 사전 등록(결정 표 #14).",
          "- 런지 걸음의 좌우 판별. 앱은 쪽을 모르고 번갈아 한다는 가정으로 짝짓는다. `tally_order` 는 나중에 판별을 맞춰 볼 자료일 뿐 이 프로토콜의 판정이 아니다.",
          "  번갈아 하지 않는 사용자(한쪽 몰아 하기)는 `sideblock` 으로 크기만 본다 — 그때 '반대쪽 차례' 는 블록 중간에 틀린다(화면 전용, 음성 없음).",
          "- 번갈아 하는 덤벨 컬의 판정. 앱의 컬 신호(두 팔 평균)는 한 팔 반복을 한 사이클로 잡는다는 보장이 없고 쪽을 가리지 못한다(README §11 Q3) — Gate B 의 컬 판정은",
          "  양팔 동시 컬에 대한 판정이다. 번갈아 하는 컬은 Gate A `altcurl` 로 크기만 본다(판정 밖, 설계 §15 #25).",
          "- 다른 런지류(바벨 런지·사이드 런지·크로스 런지)는 같은 좌우 짝 단위지만 여기서 재지 않는다. 사이드·크로스 런지가 걸음마다 무릎 신호를 한 번 내리는지는 확인되지 않았다."]
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
