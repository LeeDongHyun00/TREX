#!/usr/bin/env python
"""실기기 검증 프로토콜 생성 — 어떤 종목을, 몇 세트, 폰을 어디에 두고 찍고, 그 배치에서 무엇이 나와야 하는가 (spec §33 실기기 대조).

프로토콜을 손으로 쓰지 않고 규칙 JSON 에서 뽑는 이유: 검증의 기대값(어느 배치에서 어느 규칙이 판정/유보되는가)이 `views_ok` 에서
나오므로, JSON 이 바뀌면 프로토콜도 같이 바뀌어야 한다. 배치 좌우는 전부 **사용자 기준**이다(§33: B = 사용자 오른쪽 앞).

입력  rules/rules_mp_v0.json, ../../app/src/main/java/com/example/trex_kotlin/PostureLive.kt (postureExerciseMap: 앱 이름 → AIHub 이름)
출력  DEVICE_VALIDATION.md (사람이 읽는 프로토콜), outputs/device_validation_plan.csv (세트 순서표 — device_validation_check.py 가 로그와 대조)
"""
from __future__ import annotations

import csv
import json
import re
import sys
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

HERE = Path(__file__).resolve().parent
OUT = HERE / "outputs"
RULES = HERE / "rules" / "rules_mp_v0.json"
LIVE_KT = HERE.parent.parent / "app" / "src" / "main" / "java" / "com" / "example" / "trex_kotlin" / "PostureLive.kt"

# 배치 정의 — 좌우는 사용자 기준. 코드는 추정기 등급(ViewEstimator.ViewClass.letter).
PLACEMENTS = {
    "C": ("정면", "사용자 정면. 사용자가 폰을 마주 본다"),
    "B": ("앞 비스듬히 · 사용자 오른쪽", "정면 자리에서 사용자의 **오른쪽**으로 약 40° 돌아간 위치. 사용자의 오른어깨가 폰에 더 가깝다"),
    "D": ("앞 비스듬히 · 사용자 왼쪽", "정면 자리에서 사용자의 **왼쪽**으로 약 40° 돌아간 위치. 사용자의 왼어깨가 폰에 더 가깝다"),
    "SIDE_B": ("옆 · 사용자 오른쪽", "사용자의 오른쪽 옆 90°"),
    "SIDE_D": ("옆 · 사용자 왼쪽", "사용자의 왼쪽 옆 90°"),
    "A": ("뒤 비스듬히 · 사용자 오른쪽", "등 뒤 자리에서 사용자의 오른쪽으로 약 40°"),
    "E": ("뒤 비스듬히 · 사용자 왼쪽", "등 뒤 자리에서 사용자의 왼쪽으로 약 40°"),
    "R": ("뒤", "사용자 등 뒤 정면"),
}
FRONT = ("C", "B", "D")
ALL_ORDER = ["C", "B", "D", "SIDE_B", "SIDE_D", "A", "E", "R"]

# 장비별 코스. 앱 이름(postureExerciseMap 키) 기준. 여기 없는 앱 종목은 전체 코스 끝에 붙는다.
TIERS = [
    ("맨몸", ["기본 스쿼트", "런지", "사이드 런지", "크로스 런지", "스탠딩 니업", "스탠딩 사이드 크런치", "굿모닝"]),
    ("덤벨·가벼운 중량", ["덤벨 컬", "바벨 컬", "프런트 레이즈", "사이드 레터럴 레이즈", "오버헤드 프레스", "업라이트로우"]),
    ("기구·바벨", ["딥스", "랫풀 다운", "바벨 데드리프트", "바벨 런지", "행잉 레그 레이즈"]),
]
REFERENCE = "기본 스쿼트"     # 추정기 8방향 전수 검증 종목
SKIP = {"바벨 스쿼트"}        # 기본 스쿼트와 같은 AIHub 매핑 — 중복

# §32 에서 임계값을 MP 재적합한 규칙 — 정자세 1세트 + 의도적 위반 1세트로 임계값이 살아 있는지 본다
VIOLATION_HOWTO = {
    "딥스|이완 시 팔꿈치 각도 90도": "팔꿈치를 90°까지 굽히지 말고 얕게 내려갔다 올라오기",
    "바벨 데드리프트|바벨 궤적과 몸 밀착": "바를 정강이·허벅지에서 10cm 이상 떨어뜨린 채 들기",
    "바벨 데드리프트|발과 무릎의 방향 일치": "무릎을 안쪽으로 모으며(발끝보다 안쪽) 들기",
    "바벨 런지|뒤다리 무릎 각도 90도": "뒷무릎을 충분히 내리지 않고 얕게",
    "스탠딩 사이드 크런치|양 손이 머리 뒤에 위치": "손을 머리 뒤가 아니라 몸 옆에 내린 채",
}
REP_HINT = "6회 이상, 20초 이상 (첫 렙 완료 뒤 집계가 시작되고 8프레임 이상 필요)"


def app_map() -> list[tuple[str, str]]:
    src = LIVE_KT.read_text(encoding="utf-8")
    block = src.split("val postureExerciseMap")[1].split(")\n")[0]
    return re.findall(r'"([^"]+)"\s+to\s+"([^"]+)"', block)


def active_rules(rules: list[dict], exercise: str) -> list[dict]:
    """PostureRuleSet.rulesFor(includeBeta=true) 와 같은 필터: ship+beta, 하위유형이 있으면 [all] 제거."""
    lst = [r for r in rules if r["exercise"] == exercise and r["status"] in ("ship", "beta")]
    if any(r.get("subtype") not in (None, "all") for r in lst):
        lst = [r for r in lst if r.get("subtype") != "all"]
    return lst


def rule_name(r: dict) -> str:
    return r["condition"] + (f"[{r['subtype']}]" if r.get("subtype") else "")


def expected(rules_ex: list[dict], placement: str) -> tuple[list[str], list[str]]:
    """앱 evaluate 와 같은 순서: 기준선 필수(§28e)면 유보, views_ok 가 있고 배치가 목록 밖이면 유보(§33), views_ok 가 없으면 배치 무관 판정."""
    judged, abst = [], []
    for r in rules_ex:
        name = rule_name(r) + ("(beta)" if r["status"] == "beta" else "")
        if (r.get("personal_baseline") or {}).get("required"):
            abst.append(name + "(기준선 필요)")
            continue
        ok = r.get("views_ok")
        if ok is None:
            judged.append(name + "(뷰 무관)")
        elif placement in ok:
            judged.append(name)
        else:
            abst.append(name)
    return judged, abst


def best_front(rules_ex: list[dict]) -> tuple[str, dict]:
    ship = [r for r in rules_ex if r["status"] == "ship"] or rules_ex
    cnt = {c: sum(1 for r in ship if c in (r.get("views_ok") or [])) for c in FRONT}
    best = max(FRONT, key=lambda c: (cnt[c], c == "C"))
    return best, cnt


def main():
    doc = json.load(open(RULES, encoding="utf-8"))
    rules = doc["rules"]
    floor_ex = {r["exercise"] for r in json.load(open(HERE / "rules" / "rules_floor_v0.json", encoding="utf-8"))["rules"]}
    amap = [(a, b) for a, b in app_map() if b not in floor_ex and a not in SKIP]
    by_app = dict(amap)
    tier_of = {a: t for t, names in TIERS for a in names}
    ordered = [a for _, names in TIERS for a in names if a in by_app] + [a for a, _ in amap if a not in tier_of]

    rows = []
    detail = {}
    for app in ordered:
        ex = by_app[app]
        rx = active_rules(rules, ex)
        best, cnt = best_front(rx)
        ship_n = sum(1 for r in rx if r["status"] == "ship")
        # 어느 배치를 찍나
        if app == REFERENCE:
            placements = list(ALL_ORDER)
        else:
            need = sorted({c for r in rx if r["status"] == "ship" for c in (r.get("views_ok") or []) if c in FRONT}, key=FRONT.index)
            placements = [best] if cnt[best] >= ship_n or not need else need
            if not placements:
                placements = [best]
        tier = tier_of.get(app, "기타")
        course = "min" if tier == "맨몸" else "full"
        for p in placements:
            j, a = expected(rx, p)
            rows.append(dict(app_name=app, aihub_name=ex, tier=tier, course=course, placement=p, placement_text=PLACEMENTS[p][0],
                             form="정자세", target_rule="", expected_class=p, expected_judged="; ".join(j), expected_abstained="; ".join(a),
                             reps=REP_HINT))
        # 재적합 규칙: 홈 배치에서 의도적 위반 1세트
        for r in rx:
            how = VIOLATION_HOWTO.get(r["id"])
            if how and "threshold_before_s32" in r:
                home = r.get("view_best_front") or best
                j, a = expected(rx, home)
                rows.append(dict(app_name=app, aihub_name=ex, tier=tier, course=course, placement=home, placement_text=PLACEMENTS[home][0],
                                 form="의도적 위반: " + how, target_rule=r["id"], expected_class=home, expected_judged="; ".join(j),
                                 expected_abstained="; ".join(a), reps=REP_HINT))
        detail[app] = (ex, rx, best, cnt, ship_n)
    for i, r in enumerate(rows, 1):
        r["order"] = i
    OUT.mkdir(exist_ok=True)
    cols = ["order", "course", "tier", "app_name", "aihub_name", "placement", "placement_text", "expected_class", "form", "target_rule",
            "expected_judged", "expected_abstained", "reps"]
    with open(OUT / "device_validation_plan.csv", "w", encoding="utf-8-sig", newline="") as f:
        w = csv.DictWriter(f, fieldnames=cols)
        w.writeheader()
        for r in rows:
            w.writerow({k: r[k] for k in cols})
    write_md(rows, detail, doc)
    print(f"[plan] 세트 {len(rows)}개 (최소 코스 {sum(1 for r in rows if r['course']=='min')}) → {OUT/'device_validation_plan.csv'}, {HERE/'DEVICE_VALIDATION.md'}")


def write_md(rows, detail, doc):
    n_min = sum(1 for r in rows if r["course"] == "min")
    L = ["# 실기기 검증 프로토콜 — 촬영 방향 추정·뷰 게이팅·§32 임계값 (spec §33)\n",
         f"- 생성: `device_validation_plan.py` (규칙 JSON `{doc.get('version')}` 의 `views_ok` 에서 기대값을 뽑는다. JSON 이 바뀌면 다시 생성)",
         f"- 세트 수: **최소 코스(맨몸) {n_min}세트 ≈ {n_min*1.5:.0f}분**, 전체 {len(rows)}세트 ≈ {len(rows)*1.5:.0f}분. 세트마다 {REP_HINT}.",
         "- 채점: `python pull_logs.py` → `python device_validation_check.py --since <오늘 날짜>`\n",
         "## 0. 무엇을 검증하나 · 합격 기준\n",
         "| 항목 | 방법 | 합격 |", "|---|---|---|",
         "| **추정기** — 로그 `view.class` 가 실제 배치와 맞는가 | 기본 스쿼트를 8방향에서 1세트씩 | 정면·앞 비스듬히 3방향 등급 일치, 옆·뒤 5방향은 전/후·좌우 방향이 맞음 (옆은 SIDE_B/SIDE_D, 뒤는 A/E/R 중 하나) |",
         "| **게이팅** — 배치에 따라 규칙이 판정/유보되는가 | 각 세트의 리포트 카드와 로그 `results` | 아래 표의 '판정될 규칙'·'유보될 규칙' 과 일치. 옆(SIDE)은 미검증 등급이라 뷰 게이팅되는 규칙은 **전부 유보**, 뒤 비스듬히(A/E)는 데이터가 허용한 규칙만 판정 |",
         "| **§32 재적합 임계값** — 죽었던 규칙이 살아났는가 | 정자세 1세트 + 의도적 위반 1세트 | 정자세 OK(스튜디오 오탐률만큼은 틀릴 수 있음), 위반 세트에서 해당 규칙 VIOLATION |",
         "| **강등 확인** — 스쿼트 '고개 정면'이 beta 로 침묵하는가 | 정면 스쿼트 세트 | 음성으로 지적하지 않고 카드에 호박색 '참고'로만 |",
         "", "## 1. 준비\n",
         "1. 새 APK 설치 (기기의 현재 빌드는 §32·§33 이전이라 `view` 필드가 없다). 같은 디버그 키라 삭제 없이 덮어쓴다:",
         "```bash", "adb install -r app/build/outputs/apk/debug/app-debug.apk", "```",
         "2. 앱에서 세션을 **코치 모드**로 진행한다(모드는 로그 필드일 뿐 판정은 같다). 음성은 꺼도 된다. 세트가 끝나면 자가 라벨은 '좋았음'(정자세) / '의도적 변형'(위반 세트)으로 남긴다.",
         "3. 이 표의 **순서대로** 찍는다. 채점기는 같은 종목의 세트를 시각 순서로 계획과 짝짓는다. 한 세트를 망쳤으면 같은 배치로 바로 다시 찍고, 채점 뒤 첫 시도를 버리면 된다(`--skip` 옵션).",
         "4. 폰: 허리 높이, 세로, 사용자에게서 2.5~3 m, 전신이 화면에 들어오게. 앵커(첫 렙 완료)가 잡히기 전 3초간 가만히 서 있으면 준비 동작 오염이 줄어든다.",
         "", "## 2. 배치 정의 — 좌우는 전부 **사용자 기준**\n",
         "데이터셋 코드 B 는 '전방사선L' 이지만 그것은 카메라 쪽에서 본 왼쪽이다. 추정기 부호로 따지면 B 는 **사용자의 오른어깨가 폰에 더 가까운** 배치다. 앱 문구·리포트·이 표 모두 사용자 기준으로 통일했다(§33).\n",
         "| 코드 | 이름 | 폰 위치 |", "|---|---|---|"]
    for k in ALL_ORDER:
        L.append(f"| {k} | {PLACEMENTS[k][0]} | {PLACEMENTS[k][1]} |")
    L += ["", "```",
          "            A(뒤·오른쪽)   R(뒤)   E(뒤·왼쪽)",
          "                    \\      |      /",
          "   SIDE_B(옆·오른쪽) ---  사용자  --- SIDE_D(옆·왼쪽)      ← 사용자가 정면(C)을 바라봄",
          "                    /      |      \\",
          "            B(앞·오른쪽)   C(정면)  D(앞·왼쪽)",
          "```", ""]
    for course, title in (("min", "## 3. 최소 코스 — 맨몸 (장비 없이 오늘 바로)"), ("full", "## 4. 전체 코스 — 덤벨·기구 (최소 코스 다음에 이어서)")):
        sub = [r for r in rows if r["course"] == course]
        L += [title + "\n", f"{len(sub)}세트.\n", "| # | 종목(앱 이름) | 배치 | 폼 | 기대 등급 | 판정될 규칙 | 유보될 규칙 |", "|---|---|---|---|---|---|---|"]
        for r in sub:
            L.append(f"| {r['order']} | {r['app_name']} | {r['placement_text']} ({r['placement']}) | {r['form']} | {r['expected_class']} | {r['expected_judged'] or '(없음)'} | {r['expected_abstained'] or '(없음)'} |")
        L.append("")
    L += ["## 5. 종목별 근거 — 왜 그 배치인가\n", "| 종목 | AIHub 이름 | 활성 규칙(ship/beta) | 한 배치에서 판정되는 ship 수 C/B/D | 최적 | 비고 |", "|---|---|---|---|---|---|"]
    for app, (ex, rx, best, cnt, ship_n) in detail.items():
        note = "추정기 8방향 전수" if app == REFERENCE else ("규칙이 뷰를 나눠 가짐 → 배치 2개 이상" if cnt[best] < ship_n else "")
        L.append(f"| {app} | {ex} | {sum(1 for r in rx if r['status']=='ship')}/{sum(1 for r in rx if r['status']=='beta')} | {cnt['C']}/{cnt['B']}/{cnt['D']} (ship {ship_n}) | {best} | {note} |")
    L += ["", "## 6. 회수와 채점\n", "```bash", "python pull_logs.py", "python device_validation_check.py --since 2026-09-06   # 촬영한 날짜", "```",
          "결과: `outputs/DEVICE_VALIDATION_RESULT.md` — 세트별 (계획 배치 vs 로그 `view.class`, 판정/유보 일치, 위반 세트의 대상 규칙 verdict) 와 요약.",
          "", "## 7. 이 프로토콜이 답하지 못하는 것\n",
          "- 임계값이 **맞는지**는 못 본다 — 정자세 세트가 OK 로 나와도 스튜디오 기준 오탐률(중앙값 0.13)만큼은 우연이다. 그것은 §9 재보정(종목당 12~30 라벨 세트)의 몫이다.",
          "- 한 사람·한 폰·한 장소다. 추정기의 좌우·반구가 맞는지는 이걸로 충분하지만, 정확도 수치는 아니다.",
          "- 바닥 종목은 이 프로토콜 밖이다(뷰 추정을 쓰지 않는다)."]
    (HERE / "DEVICE_VALIDATION.md").write_text("\n".join(L), encoding="utf-8")


if __name__ == "__main__":
    main()
