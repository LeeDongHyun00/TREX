#!/usr/bin/env python
"""'모든 신체 자세를 파악할 수 있는가' — 해부학적 자유도별 관측 가능성 조사.

AIHub 의 전체 조건(종목×조건)을 해부학적 자유도(DOF)로 분류하고, 각 DOF 가
  (1) GT 3D(완벽한 좌표)로 관측되는가  — rule_engine_v0 의 화이트리스트 단일 규칙 AUC
  (2) MediaPipe 로 전이되는가          — experiment_a_refit 의 최적 뷰 AUC
  (3) 뷰에 얼마나 의존하는가            — 정면(C) vs 전방사선(B/D) AUC 차
를 집계한다. 랜드마크 자체가 없는 DOF(척추 곡률·견갑·골반경사·축회전·긴장/부하)는 별도로 표시.

출력: outputs/OBSERVABILITY.md, outputs/observability_inventory.csv
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

import numpy as np
import pandas as pd

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

HERE = Path(__file__).resolve().parent
OUT = HERE / "outputs"

# 해부학적 자유도 분류 — 조건명 키워드 (앞에 있는 것이 우선)
DOF_RULES: list[tuple[str, str, str]] = [
    ("축회전", r"외회전|내회전|회내|회외|엄지손가락|손목의 중립|손목의 각도|손목 꺾", "팔·다리가 제 축을 중심으로 도는 것 (상완 내/외회전, 전완 회내/회외, 손목 비틀림)"),
    ("견갑·골반", r"견갑|숄더패킹|승모|으쓱|어깨와 귀|골반 경사|숄더 패킹", "어깨뼈(견갑)와 골반의 자체 움직임 — 관절 중심 좌표로는 거의 안 움직임"),
    ("척추 곡률", r"척추|허리 휨|등의 굽힘|등 아치|경추", "요추·흉추의 굽음 정도 — 랜드마크가 목·어깨·골반뿐"),
    ("긴장·부하", r"긴장|힘 ?[빼배]|툭|반동|깔짝|브레이싱|복압", "근육 긴장·중량·복압 — 좌표에 정보가 없음(반동은 시계열로 일부 가능)"),
    ("외전·내전", r"방향 일치|방향일치|무릎 모|무릎이 몸통 측면|다리 사이|모아줌|벌리|팔꿈치.*(벌|모)|몸에서|밀착|궤적|팔꿈치-몸통|팔꿈치 위치|팔꿈치와 몸통", "사지가 정중선에서 멀어지거나 모이는 것 (무릎 내/외, 팔꿈치 벌어짐/모임)"),
    ("굴곡·신전", r"각도|90도|구부린|펴짐|펴고|다 펴|충분히 올라|깊이|올리기|가까움|높이|숙임|젖힘|리드", "관절이 굽고 펴지는 것 (무릎·팔꿈치·고관절 각도)"),
    ("몸통 정렬", r"상체|몸통|체스트|균형|흔들림|벤치 고정|기울", "몸통 전체의 기울기·회전"),
    ("머리·시선", r"고개|시선|머리", "머리 피치/요, 시선 방향"),
    ("발·접지", r"발바닥|뒤꿈치|지면 고정|스탠스", "발의 접지와 뒤꿈치 들림"),
]

# 랜드마크가 아예 없어 원리적으로 불가능한 것 (MediaPipe 33점 기준)
STRUCTURAL_GAPS = [
    ("척추 곡률(요추/흉추)", "목·어깨·골반만 있고 척추 중간 지점이 없음 → 굽음 정도를 직접 못 잼", "AIHub GT 는 Back/Waist 가 있어 부분 가능했으나 MediaPipe 에는 없음"),
    ("견갑골 움직임", "어깨 랜드마크는 관절 중심 근사 — 견갑의 전인/후인/거상/하강이 좌표를 거의 안 바꿈", "'견갑대 고정' GT 0.75 / '숄더패킹' 0.62~0.71 로 최저권"),
    ("골반 전·후방 경사", "좌우 골반점만 있어 골반이 앞뒤로 기울어도 두 점은 그대로", "요추 굴곡 판정이 막히는 주된 이유"),
    ("사지 축회전", "팔·다리가 제 축으로 돌아도 끝점(팔꿈치·손목·무릎) 위치가 거의 안 변함", "'상완의 외회전' 은 실제로 '팔꿈치 모으기'(외전) 로 연기돼 그 프록시로만 잡힘"),
    ("근육 긴장·복압", "좌표에 힘 정보가 없음", "'이완 시 팔 긴장 유지' GT AUC 0.58~0.73 — 실제 연기는 '툭 내려놓기'(템포)"),
    ("중량·부하", "같은 동작이면 20kg 과 140kg 이 동일한 좌표", "위험도 판정의 근본 공백"),
    ("발 회내·회외, 족궁", "발목·뒤꿈치·발끝 3점뿐 — 발의 내전/외전 미세 변화 불가", "AIHub 조건에도 없음"),
    ("좌우 체중 분배", "지면 반력이 없어 어느 발에 더 실렸는지 직접 못 봄", "발목 높이·골반 좌우 이동으로 간접 추정만"),
]


def classify(cond: str) -> tuple[str, str]:
    for name, pat, desc in DOF_RULES:
        if re.search(pat, cond):
            return name, desc
    return "기타", ""


def main():
    rv = pd.read_csv(OUT / "rules_v0.csv")
    rv["subtype"] = rv["subtype"].fillna("")
    rv["qc_flag"] = rv["qc_flag"].fillna("")
    ref = pd.read_csv(OUT / "expA_refit.csv")
    ref["subtype"] = ref["subtype"].fillna("")
    doc = json.load(open(HERE / "rules" / "rules_mp_v0.json", encoding="utf-8"))
    status = {(r["exercise"], r["condition"]): r["status"] for r in doc["rules"] if not r.get("subtype")}

    base = rv[(rv.subtype == "") & (rv.qc_flag != "3D불량") & rv.wl_auc.notna()].copy()
    base["dof"], base["dof_desc"] = zip(*base.base_condition.map(classify))
    # MP 전이: 뷰별 최적 / 정면 / 전방사선
    single = ref[ref.view.isin(list("ABCDE")) & (ref.subtype == "")]
    best = single.sort_values("mp_refit_auc", ascending=False).drop_duplicates(["exercise", "condition"])[["exercise", "condition", "mp_refit_auc", "view"]]
    front = single[single.view == "C"].groupby(["exercise", "condition"])["mp_refit_auc"].max().rename("mp_front")
    obliq = single[single.view.isin(["B", "D"])].groupby(["exercise", "condition"])["mp_refit_auc"].max().rename("mp_oblique")
    base = (base.merge(best.rename(columns={"condition": "base_condition", "mp_refit_auc": "mp_best", "view": "mp_best_view"}),
                       on=["exercise", "base_condition"], how="left")
                .merge(front.rename_axis(["exercise", "base_condition"]).reset_index(), on=["exercise", "base_condition"], how="left")
                .merge(obliq.rename_axis(["exercise", "base_condition"]).reset_index(), on=["exercise", "base_condition"], how="left"))
    base["status"] = [status.get((e, c), "exclude") for e, c in zip(base.exercise, base.base_condition)]
    base["view_gap"] = base.mp_front - base.mp_oblique
    base.to_csv(OUT / "observability_inventory.csv", index=False, encoding="utf-8-sig")

    # 주의: 컬럼명 gt/mp 는 pandas Series 메서드(.gt/.mul 등)와 충돌하므로 접미사를 붙인다
    g = (base.groupby("dof")
         .agg(n=("base_condition", "size"), gt_auc=("wl_auc", "median"), mp_auc=("mp_best", "median"),
              front=("mp_front", "median"), oblique=("mp_oblique", "median"), gap=("view_gap", "median"),
              ship=("status", lambda s: int((s == "ship").sum())), excl=("status", lambda s: int((s == "exclude").sum())))
         .sort_values("gt_auc", ascending=False))

    L = ["# 모든 신체 자세를 파악할 수 있는가 — 해부학적 자유도별 관측 가능성\n",
         f"- AIHub 조건 {len(base)}개(3D 양호 종목, 기본 조건)를 해부학적 자유도로 분류.",
         "- **GT** = 완벽한 3D 좌표에서의 단일 규칙 AUC(관측 가능성의 상한) · **MP** = MediaPipe 최적 뷰 재적합 AUC · **정면/사선** = 뷰별",
         "- 답부터: **아니오. 관절이 굽고 펴지는 것과 사지가 벌어지고 모이는 것은 잘 보이지만, 축을 중심으로 도는 회전·견갑/골반·척추 곡률·긴장/부하는 원리적으로 안 보인다.**\n",
         "## 1. 자유도별 관측 가능성\n",
         "| 자유도 | 조건 수 | GT(상한) | MP(최적뷰) | GT→MP 손실 | ship 비율 | 판정 |",
         "|---|---|---|---|---|---|---|"]
    def f3(v):
        return f"{v:.3f}" if np.isfinite(v) else "-"

    def verdict(gt: float, mp: float, ship_rate: float) -> str:
        """실패 유형 3분류: 원리적 불가 / MP 에서 붕괴 / 사용 가능."""
        if ship_rate >= 0.4:
            return "✅ 사용 가능"
        if np.isfinite(gt) and gt < 0.75:
            return "❌ 원리적 관측 불가 (GT 로도 안 됨)"
        return "⚠ MP 에서 붕괴 (좌표 정밀도 부족)"

    g["ship_rate"] = g.ship / g.n
    g = g.sort_values(["ship_rate", "gt_auc"], ascending=False)
    for dof, r in g.iterrows():
        loss = r["gt_auc"] - r["mp_auc"] if np.isfinite(r["gt_auc"]) and np.isfinite(r["mp_auc"]) else np.nan
        L.append(f"| **{dof}** | {int(r['n'])} | {f3(r['gt_auc'])} | {f3(r['mp_auc'])} | "
                 f"{('%+.3f' % -loss) if np.isfinite(loss) else '-'} | {int(r['ship'])}/{int(r['n'])} ({r['ship_rate']*100:.0f}%) | "
                 f"{verdict(r['gt_auc'], r['mp_auc'], r['ship_rate'])} |")
    L += ["",
          "**실패 유형이 둘로 나뉜다**:",
          "- **원리적 관측 불가** — 척추 곡률(GT 0.674)·긴장/부하(0.670): 완벽한 3D 좌표로도 안 잡힌다. 정의가 좌표에 없는 것을 가리킨다.",
          "- **MP 에서 붕괴** — 축회전(GT 0.826 → MP 0.647)·견갑/골반(0.821 → 0.731): GT 에서는 그럴듯하지만 MediaPipe 정밀도로는 무너진다.",
          "",
          "> ⚠ **축회전 GT 0.826 은 부풀려진 값이다.** '상완의 외회전' 같은 조건이 실제로는 '팔꿈치 모으기'(외전)로 연기돼(§21 요건 3), "
          "회전이 아니라 그 프록시를 잰 것이다. 회전 자체의 관측 가능성은 이보다 훨씬 낮다 — ship 0/10 이 그 결과다.",
          ""]
    L.append("")
    for dof, _, desc in DOF_RULES:
        if dof in g.index and desc:
            L.append(f"- **{dof}**: {desc}")
    L.append("")

    # 사용자 예시 2개 상세
    L += ["## 2. 질문하신 두 예시\n",
          "### 무릎이 바깥으로 벌어졌나 안으로 모였나 (valgus / varus)\n",
          "가능하다. `knee_out` = 무릎이 고관절→발목 직선에서 좌우로 얼마나 벗어났는지를 다리 길이로 나눈 **부호 있는** 값(+바깥/−안쪽).\n",
          "| 종목 | 규칙 | GT AUC | MP 정면 | MP 사선 |", "|---|---|---|---|---|"]
    ko = base[base.wl_feature.str.startswith("knee_out", na=False)]
    for r in ko.sort_values("wl_auc", ascending=False).itertuples():
        L.append(f"| {r.exercise} | `{r.wl_rule}` | {r.wl_auc:.3f} | {('%.3f' % r.mp_front) if np.isfinite(r.mp_front) else '-'} | {('%.3f' % r.mp_oblique) if np.isfinite(r.mp_oblique) else '-'} |")
    L += ["",
          "- **단, 정면 촬영이 필수**: 스쿼트 MP AUC 정면 0.993 vs 전방사선 0.79~0.82, 데드리프트 정면 0.909 vs 사선 0.51~0.67.",
          "  무릎의 좌우 이탈은 카메라 광축과 직교해야 보이는 양이라 사선에서는 원근으로 뭉개진다(피처 충실도 r: 정면 0.64 → 후방사선 0.26).",
          "",
          "### 팔이 몸에서 멀어졌나 안으로 모였나\n",
          "부분적으로 가능하다. 세 가지를 구분해야 한다.\n",
          "| 무엇 | 피처 | 관측 | 근거 |", "|---|---|---|---|",
          "| 손이 몸통에서 앞으로/옆으로 떨어짐 | `palm_fwd_hip`, `palm_lat`, `grip_w` | **잘 됨** | 바벨 궤적 밀착 GT 0.948·MP 0.870, 업라이트로우 0.953, 그립 폭 MP 충실도 r=0.87(정면) |",
          "| 팔꿈치가 몸통에서 벌어짐(외전) | `elbow_torso`, `upperarm_vert` | **약함** | 딥스 '팔꿈치-몸통의 적당한 거리' GT **0.646**, 라잉 트라이셉스 '양 팔꿈치 모아줌' 0.731 |",
          "| 상완이 축으로 도는 것(내/외회전) | — | **불가** | 페이스 풀 '상완의 외회전' 은 실제로 '팔꿈치 모이게'(외전)로 연기돼 `grip_w` 프록시로 0.878. 회전 자체는 못 봄 |",
          "",
          "- 팔꿈치 외전이 손 위치보다 약한 이유: 팔꿈치는 몸통에 가려지기 쉽고(가시성), 어깨-골반 직선에서의 수직거리라 몸통 기울기와 섞인다.",
          "",
          "## 3. 원리적으로 불가능한 것 (MediaPipe 33 랜드마크 기준)\n",
          "| 자유도 | 왜 안 되는가 | 우리 데이터에서의 흔적 |", "|---|---|---|"]
    for name, why, trace in STRUCTURAL_GAPS:
        L.append(f"| **{name}** | {why} | {trace} |")
    L += ["",
          "## 4. 정리 — 무엇이 보이고 무엇이 안 보이나\n",
          "| | 보이는가 | 조건 |", "|---|---|---|",
          "| 관절 굴곡/신전 (무릎·팔꿈치·고관절 각도) | ✅ 잘 됨 | 뷰 무관, 체형 무관 |",
          "| 사지 외전/내전 (무릎 내/외, 손 위치) | ✅ 됨 | **정면 뷰 필요**(무릎), 팔꿈치는 약함 |",
          "| 몸통 기울기·회전 | ✅ 됨 | 부호 있는 피치가 절대 기울기보다 강건 |",
          "| 머리·시선 | ✅ 됨 | 미세 각은 MP 에서 약해짐 |",
          "| 발 접지·뒤꿈치 들림 | △ 부분 | 깊이 의존, MP 전이 손실 큼 |",
          "| 반동·흔들림 (시계열) | ✅ 됨 | std/range 통계, 샘플링 주기에 의존 |",
          "| **사지 축회전** | ❌ | 끝점 좌표가 안 변함 |",
          "| **견갑·골반 자체 움직임** | ❌ | 관절 중심 근사라 정보 없음 |",
          "| **척추 곡률** | ❌ (MP) | 중간 랜드마크 없음 — 동반 증상(목·머리) 프록시만 |",
          "| **근육 긴장·복압·중량** | ❌ | 좌표에 힘 정보 없음 |",
          "",
          "### 실무 함의",
          "- 앱이 말할 수 있는 것은 **'관절이 얼마나 굽었나 · 사지가 어디에 있나 · 몸통이 어디를 향하나 · 얼마나 흔들리나'** 네 가지다. 이 범위 안에서 규칙을 정의하면 잘 작동한다(§21).",
          "- **'힘을 주세요' · '견갑을 고정하세요' · '척추를 중립으로' 같은 지시는 앱이 검증할 수 없다** — 코칭 문구로 쓰더라도 판정 근거는 관측 가능한 프록시(목-어깨 간격, 머리 피치)임을 명시해야 한다.",
          "- 무릎 내/외를 보려면 **정면 거치를 UX 로 강제**해야 한다. 사선 뷰에서는 이 규칙을 유보(ABSTAIN)하는 편이 오탐보다 낫다.",
          "- 축회전이 중요한 종목(페이스 풀·외회전 계열)은 **회전 대신 그 결과로 나타나는 위치 변화**(팔꿈치·손 위치)로 조건을 다시 정의해야 한다(§21 요건 3)."]
    (OUT / "OBSERVABILITY.md").write_text("\n".join(L), encoding="utf-8")
    print("\n".join(L[:30]))
    print(f"\n[done] → {OUT/'OBSERVABILITY.md'}")


if __name__ == "__main__":
    main()
