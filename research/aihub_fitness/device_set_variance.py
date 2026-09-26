#!/usr/bin/env python
"""실기기 세트 간 변동 — 재배치(기준선 k=3 중앙값)가 성립할 만큼 안정적인가.

device_gap_analysis 에서 드러난 것: 같은 사람·같은 종목인데 세트마다 값이 크게 다르다
(푸시업 head_trunk_ang__mean 이 5.3° / 68.1° / 82.5°). 5° 는 코-귀-골반이 거의 일직선이라는
뜻이라 해부학적으로 불가능 — 자세 차이가 아니라 **측정 실패**다.

재배치는 '기준선 k세트의 중앙값이 그 사용자·그 시점의 대표값'이라는 가정 위에 선다.
세트 간 변동이 AIHub 정상 산포보다 크면 그 가정이 깨진다. 여기서 그걸 정량화하고,
변동의 원인이 (a) 측정 실패인지 (b) 거치 변화인지 (c) 자세 변화인지 진단 지표로 가른다.

출력: outputs/DEVICE_SET_VARIANCE.md
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

HERE = Path(__file__).resolve().parent
LOGS = HERE / "outputs" / "device_logs"
RULES = HERE.parent.parent / "app" / "src" / "main" / "assets" / "posture" / "rules_floor_v0.json"
GAP = HERE / "outputs" / "device_gap.csv"


def main():
    logs = []
    for f in sorted(LOGS.glob("*.jsonl")):
        for line in f.read_text(encoding="utf-8").splitlines():
            if line.strip():
                logs.append(json.loads(line))
    gap = pd.read_csv(GAP)
    rules = json.load(open(RULES, encoding="utf-8"))["rules"]

    # 1) 세트별 품질 지표
    rows = []
    for g in logs:
        fr = g.get("frames", [])
        if not fr:
            continue
        with_feat = [x for x in fr if x.get("features")]
        vis = [x.get("visible", 0) for x in fr]
        # 핵심 관절 가시성 (MP 인덱스: 어깨11/12 골반23/24 발목27/28, 머리 0/7/8)
        core, head = [], []
        for x in fr:
            v = x.get("vis")
            if not v:
                continue
            core.append(min(v[11], v[12], v[23], v[24], v[27], v[28]))
            head.append(min(v[0], v[7], v[8]))
        rows.append(dict(
            set_id=g.get("set_id", "")[:15], created=g.get("created_at", "")[:19],
            exercise=g.get("exercise"), note=g.get("note"), tilt=g.get("tilt_deg"),
            n_frames=len(fr), n_feat=len(with_feat), feat_rate=len(with_feat) / max(len(fr), 1),
            vis_joints_med=float(np.median(vis)) if vis else np.nan,
            core_vis_med=float(np.median(core)) if core else np.nan,
            head_vis_med=float(np.median(head)) if head else np.nan,
            up_flipped=g.get("up_flipped_frames"), up_verified=g.get("up_verified_frames"),
        ))
    q = pd.DataFrame(rows)

    # 2) 규칙별 세트 간 변동 vs AIHub 정상 IQR
    var_rows = []
    for (ex, cond), d in gap.groupby(["exercise", "condition"]):
        if len(d) < 2:
            continue
        vals = d.device.to_numpy(float)
        iqr_ai = float(d.aihub_iqr.iloc[0])
        spread = float(np.percentile(vals, 75) - np.percentile(vals, 25)) if len(vals) >= 3 else float(vals.max() - vals.min())
        var_rows.append(dict(exercise=ex, condition=cond, n_sets=len(d),
                             device_range=float(vals.max() - vals.min()), device_iqr=spread,
                             aihub_normal_iqr=iqr_ai, ratio=spread / iqr_ai if iqr_ai > 0 else np.nan,
                             values="/".join(f"{v:.2f}" for v in sorted(vals))))
    v = pd.DataFrame(var_rows)

    L = ["# 실기기 세트 간 변동 — 재배치의 전제가 성립하는가\n",
         "재배치는 '기준선 k세트 중앙값 = 그 사용자·그 시점의 대표값'을 전제한다. 세트 간 변동이 AIHub 정상 산포보다 크면 그 전제가 깨진다.\n",
         "## 1. 세트별 품질 지표\n",
         "| 세트 | 종목 | 프레임 | 피처율 | 관절수(중앙) | 코어가시성 | 머리가시성 | 기울기 | note |",
         "|---|---|---|---|---|---|---|---|---|"]
    for _, r in q.sort_values("created").iterrows():
        L.append(f"| {r.created[11:19]} | {r.exercise} | {r.n_frames} | {r.feat_rate*100:.0f}% | "
                 f"{r.vis_joints_med:.0f}/33 | {r.core_vis_med:.2f} | {r.head_vis_med:.2f} | "
                 f"{r.tilt:.0f}° | {r.note} |")
    L += ["", "## 2. 규칙별 세트 간 변동\n",
          "| 종목 | 조건 | 세트 | 실기기 값들 | 실기기 산포 | AIHub 정상 IQR | 비율 |",
          "|---|---|---|---|---|---|---|"]
    for _, r in v.sort_values("ratio", ascending=False).iterrows():
        L.append(f"| {r.exercise} | {r.condition[:16]} | {r.n_sets} | {r.values} | {r.device_iqr:.2f} | "
                 f"{r.aihub_normal_iqr:.2f} | **{r.ratio:.1f}×** |")
    ratio_med = v.ratio.median()
    L += ["",
          f"- 세트 간 산포 / AIHub 정상 IQR 중앙값 **{ratio_med:.1f}×**",
          "",
          "## 3. 진단\n"]
    bad = q[(q.head_vis_med < 0.5) | (q.feat_rate < 0.6)]
    L += [f"- **측정 실패 세트**: 머리 가시성 <0.5 또는 피처율 <60% 인 세트 {len(bad)}/{len(q)}개"
          + (f" — {', '.join(bad.created.str[11:19])}" if len(bad) else ""),
          f"- **거치 변화**: 기울기(tilt) {q.tilt.min():.0f}°~{q.tilt.max():.0f}° 로 세트마다 다르다 — 같은 사용자라도 매번 다른 시점",
          ""]
    if ratio_med > 1.0:
        L += [f"**세트 간 변동이 AIHub 정상 산포보다 {ratio_med:.1f}배 크다.** 이 상태에서는 k=3 중앙값 기준선도 흔들리므로 "
              "재배치가 시점을 교정하는 게 아니라 **노이즈를 옮기는 데 그칠 수 있다**. "
              "AIHub 시뮬레이션(동일-수행자 앵커 Δ+0.027)은 세트 간 변동이 작다는 가정에서 나온 값이고, 실기기는 그 가정을 만족하지 않는다.",
              "",
              "→ 재배치를 배포하기 전에 **세트 품질 게이트**가 먼저다: 머리·코어 가시성과 피처율이 기준 미달인 세트는 기준선에서 제외해야 한다. "
              "현재 BaselineCollector 는 '값이 있는 세트 2개 이상'만 요구하고 품질은 보지 않는다."]
    else:
        L += [f"세트 간 변동이 AIHub 정상 산포와 비슷하다({ratio_med:.1f}×) — k=3 기준선이 대표값 역할을 할 수 있다."]
    L += ["",
          "## 한계\n",
          "- 세트 5개, 한 사람, 라벨 없음 — 방향을 가리키는 진단이지 결론이 아니다.",
          "- 이 세트들은 **세션 로그**(자유 촬영)라 기준선 프로토콜(고정 거치·정자세 지시)보다 조건이 나쁠 수 있다. 기준선 세트는 더 안정적일 가능성이 있다.",
          "- 그러나 그 '가능성'을 확인하기 전까지 재배치 이득(Δ+0.027)은 실기기에서 입증되지 않았다."]
    (HERE / "outputs" / "DEVICE_SET_VARIANCE.md").write_text("\n".join(L), encoding="utf-8")
    print("\n".join(L))


if __name__ == "__main__":
    main()
