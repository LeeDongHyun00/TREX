#!/usr/bin/env python
"""바닥 운동 측정 요구사항 표 생성 (spec §25b).

rules_floor_v0.1 의 각 규칙이 **무엇을 재는지(변수)** 와 **그걸 재려면 화면에 무엇이 있어야 하는지(부위)** 를
한 표로 만든다. 앱의 PostureFloorCoverage.kt(FLOOR_FEATURE_PARTS)와 같은 정의를 쓰며,
이 스크립트가 그 매핑의 누락·불일치를 검사한다(앱은 유닛 테스트로도 검사).

출력: FLOOR_REQUIREMENTS.md
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

HERE = Path(__file__).resolve().parent
RULES = HERE.parent.parent / "app" / "src" / "main" / "assets" / "posture" / "rules_floor_v0.json"

# PostureFloorCoverage.kt 의 FLOOR_FEATURE_PARTS 와 **동일해야 한다**
PARTS = {
    "hip_dev_ankle": ["어깨", "골반", "발목"],
    "hip_dev_knee": ["어깨", "골반", "무릎"],
    "knee_dev": ["골반", "무릎", "발목"],
    "shoulder_dev": ["어깨", "골반", "손목"],
    "elbow_ang": ["어깨", "팔꿈치", "손목"],
    "knee_ang": ["골반", "무릎", "발목"],
    "hip_ang": ["어깨", "골반", "무릎"],
    "trunk_ankle_ang": ["어깨", "골반", "발목"],
    "head_trunk_ang": ["머리", "골반"],
    "shoulder_arm_ang": ["골반", "어깨", "팔꿈치"],
    "hand_shoulder_off": ["손목", "어깨", "골반"],
    "wrist_shoulder_d": ["손목", "어깨", "골반"],
    "knee_shoulder_d": ["무릎", "어깨", "골반"],
    "ankle_hip_d": ["발목", "어깨", "골반"],
    "elbow_width": ["어깨", "팔꿈치", "손목"],
    "knee_gap2d": ["무릎", "어깨", "골반"],
    "ankle_gap2d": ["발목", "어깨", "골반"],
    "shoulder_asym2d": ["어깨", "골반"],
    "shoulder_ground": ["어깨", "골반", "발목"],
    "hip_ground": ["골반", "발목"],
    "knee_ground": ["무릎", "골반", "발목"],
    "ankle_ground": ["발목", "골반"],
    "head_ground": ["머리", "골반", "발목"],
}

# 변수가 실제로 무엇을 재는지 (사람 말로)
MEANING = {
    "hip_dev_ankle": "어깨→발목 직선에서 골반이 위/아래로 벗어난 정도 (허리 처짐·엉덩이 들림)",
    "hip_dev_knee": "어깨→무릎 직선에서 골반이 벗어난 정도",
    "knee_dev": "골반→발목 직선에서 무릎이 벗어난 정도",
    "shoulder_dev": "골반→손목 직선에서 어깨가 벗어난 정도 (손 위치 대비 어깨)",
    "elbow_ang": "팔꿈치 각도 (어깨-팔꿈치-손목)",
    "knee_ang": "무릎 각도 (골반-무릎-발목)",
    "hip_ang": "몸통-허벅지 각도 (어깨-골반-무릎)",
    "trunk_ankle_ang": "몸통-다리 정렬 각도 (어깨-골반-발목)",
    "head_trunk_ang": "고개 각도 (코-귀-골반) — 젖힘/숙임",
    "shoulder_arm_ang": "상완-몸통 각도 (골반-어깨-팔꿈치)",
    "hand_shoulder_off": "몸통축 대비 손 위치 이탈",
    "wrist_shoulder_d": "손목↔어깨 거리 (몸통 길이로 정규화) — 가슴 이동량",
    "knee_shoulder_d": "무릎↔어깨 거리 (정규화)",
    "ankle_hip_d": "발목↔골반 거리 (정규화)",
    "elbow_width": "팔꿈치가 어깨-손목 선에서 벌어진 정도",
    "knee_gap2d": "양 무릎 간격 (정규화)",
    "ankle_gap2d": "양 발목 간격 (정규화)",
    "shoulder_asym2d": "양 어깨 높이 비대칭",
    "shoulder_ground": "지면(접지선) 대비 어깨 높이",
    "hip_ground": "지면 대비 골반 높이",
    "knee_ground": "지면 대비 무릎 높이",
    "ankle_ground": "지면 대비 발목 높이",
    "head_ground": "지면 대비 머리 높이",
}

STAT_KO = {"mean": "평균", "min": "최소", "max": "최대", "std": "표준편차", "range": "범위(최대-최소)"}


def main():
    doc = json.load(open(RULES, encoding="utf-8"))
    rules = doc["rules"]
    missing = sorted({r["base_feature"] for r in rules if r["base_feature"] not in PARTS})
    if missing:
        sys.exit(f"[err] 부위 매핑 누락: {missing} — PostureFloorCoverage.kt 와 함께 채우세요")

    by_ex: dict[str, list] = {}
    for r in rules:
        by_ex.setdefault(r["exercise"], []).append(r)

    L = [f"# 바닥 운동 측정 요구사항 ({doc['version']})\n",
         "각 규칙이 **무엇을 재는지(변수)** 와 **재려면 화면에 무엇이 있어야 하는지(부위)**. "
         "앱은 이 표를 코드로 갖고 있고(`PostureFloorCoverage.FLOOR_FEATURE_PARTS`), "
         "필요 부위가 화면에 없으면 그 규칙만 **판정 보류**하고 사용자에게 해결책을 안내한다(spec §25b).\n",
         "> 핵심: 필요 부위는 **규칙에서 역산**한다. 종목마다 '전신이 다 보여야 한다'고 요구하지 않는다 — "
         "실측에서 푸시업 규칙 3개가 발목을 쓰지도 않으면서 발목 때문에 프레임의 85%를 버린 적이 있다(§25a).\n"]

    for ex in sorted(by_ex, key=lambda e: -len(by_ex[e])):
        rs = by_ex[ex]
        need_all = sorted({p for r in rs for p in PARTS[r["base_feature"]]})
        view = rs[0].get("view_best_front_desc") or rs[0].get("view_best_front", "")
        L += [f"\n## {ex} — 규칙 {len(rs)}개\n",
              f"- 촬영: **{view}**",
              f"- 이 종목에서 화면에 필요한 부위(합집합): **{' · '.join(need_all)}**\n",
              "| 조건 | 재는 변수 | 통계 | 필요 부위 | AUC | MP 충실도 |",
              "|---|---|---|---|---|---|"]
        for r in sorted(rs, key=lambda x: -x["cv_auc"]):
            bf = r["base_feature"]
            rho = r.get("mp_fidelity")
            L.append(f"| {r['condition']} | {MEANING[bf]} | {STAT_KO.get(r['stat'], r['stat'])} | "
                     f"{' · '.join(PARTS[bf])} | {r['cv_auc']:.2f} | {rho if rho is not None else '—'} |")

    # 부위별 역인덱스 — '이 부위가 안 보이면 무엇을 못 재나'
    L += ["\n## 부위가 안 보이면 무엇을 못 재나\n",
          "| 부위 | 판정 불가한 조건 |", "|---|---|"]
    inv: dict[str, list[str]] = {}
    for r in rules:
        for p in PARTS[r["base_feature"]]:
            inv.setdefault(p, []).append(f"{r['exercise']}·{r['condition']}")
    for p in sorted(inv, key=lambda x: -len(inv[x])):
        items = inv[p]
        shown = ", ".join(items[:6]) + (f" 외 {len(items) - 6}개" if len(items) > 6 else "")
        L.append(f"| **{p}** | {len(items)}개 — {shown} |")

    L += ["\n## 안내 로직 (앱 구현)\n",
          "| 상황 | 판정 | 사용자 안내 |", "|---|---|---|",
          "| 좌표가 화면 밖 · 한 방향 | 프레임 문제 | \"{부위}가 화면 {방향}으로 벗어났어요. 폰을 그쪽으로 옮기거나 반대로 이동하세요\" |",
          "| 좌표가 화면 밖 · 여러 방향/부위 | 전신 미포함 | \"몸 전체가 안 들어와요. 폰을 더 멀리(2~3걸음) 두거나 가로로 놓으세요\" |",
          "| 화면 안인데 가시성 낮음 | 가림 | \"{부위}가 몸에 가려졌어요. 폰을 몸 옆으로 옮겨 옆모습이 보이게 하세요\" |",
          "",
          "MediaPipe 는 화면 밖 관절도 외삽 좌표를 내므로 위 두 원인을 구분할 수 있다. "
          "전면 카메라는 좌우 반전으로 보여주므로 안내의 좌/우도 뒤집는다. "
          "1초(3프레임) 연속으로 막힐 때만 표시하고, 음성은 8초 간격으로 제한한다."]

    out = HERE / "FLOOR_REQUIREMENTS.md"
    out.write_text("\n".join(L), encoding="utf-8")
    print(f"[done] 규칙 {len(rules)}개 / 종목 {len(by_ex)}개 → {out}")
    for ex in sorted(by_ex):
        need = sorted({p for r in by_ex[ex] for p in PARTS[r["base_feature"]]})
        print(f"  {ex:14s} 규칙 {len(by_ex[ex])}  필요부위: {' '.join(need)}")


if __name__ == "__main__":
    main()
