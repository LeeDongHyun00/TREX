#!/usr/bin/env python
"""바닥 규칙 임계값이 '카메라 각도에 묶여 있다'를 정량화.

주장: rules_floor_v0.1 의 임계값은 **채택 뷰의 2D 투영**에서 적합됐다. 2D 피처(직선 대비 이탈,
정규화 거리, 투영 각도)는 시점이 바뀌면 같은 실제 자세라도 값이 달라진다. 따라서 앱이 요구하는
'바닥 높이·몸 옆' 시점에서는 그 숫자가 그대로 유효하지 않다.

검증: 채택 뷰에서 적합한 임계값을 **다른 뷰의 같은 종목 데이터**에 그대로 적용해, 위반으로
플래그되는 비율이 어떻게 변하는지 본다. 실제 위반율은 뷰와 무관(같은 클립을 5대가 동시 촬영)하므로,
플래그율이 뷰마다 흔들리면 그건 순전히 임계값의 시점 의존성이다.

한계: AIHub 5뷰는 **전부 서있는 높이**다. 바닥 높이 뷰는 이 데이터에 없다 — 여기서 보는 뷰 간
변동은 '같은 높이에서 각도만 다를 때'의 하한이고, 높이까지 바뀌면 최소 이만큼, 대개는 더 벌어진다.

출력: outputs/floor_threshold_view.csv, outputs/FLOOR_THRESHOLD_VIEW.md
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
sys.path.insert(0, str(HERE))
from export_floor_rules import (  # noqa: E402
    JOINTS, MIN_FRAMES, SRC, aggregate_clip, frame_features_stream,
)

RULES = HERE.parent.parent / "app" / "src" / "main" / "assets" / "posture" / "rules_floor_v0.json"


def build() -> pd.DataFrame:
    cols = ["clip_id", "view_letter", "frame_idx"] + [f"{j}_{a}" for j in JOINTS for a in "xy"]
    clips = pd.read_parquet(SRC / "clips.parquet")[["clip_id", "exercise"]]
    k2 = pd.read_parquet(SRC / "kp2d.parquet", columns=cols).merge(clips, on="clip_id")
    doc = json.load(open(RULES, encoding="utf-8"))
    exs = sorted({r["exercise"] for r in doc["rules"]})
    k2 = k2[k2.exercise.isin(exs)].sort_values(["clip_id", "view_letter", "frame_idx"])
    rows = []
    for (cid, view), d in k2.groupby(["clip_id", "view_letter"]):
        if len(d) < MIN_FRAMES:
            continue
        frames = np.stack([d[[f"{j}_x", f"{j}_y"]].to_numpy(dtype=np.float64) for j in JOINTS], axis=1)
        r = aggregate_clip(frame_features_stream(frames, JOINTS))
        r.update(clip_id=cid, view=view, exercise=d.exercise.iloc[0])
        rows.append(r)
    return pd.DataFrame(rows), doc["rules"]


def main():
    feats, rules = build()
    rows = []
    for r in rules:
        ex, feat, op, thr, adopted = r["exercise"], r["feature"], r["op"], r["threshold"], r["view_best_front"]
        d = feats[feats.exercise == ex]
        if feat not in d.columns:
            continue
        per = {}
        for view, g in d.groupby("view"):
            x = g[feat].to_numpy(dtype=np.float64)
            x = x[np.isfinite(x)]
            if len(x) < 20:
                continue
            flag = float((x > thr).mean() if op == ">" else (x < thr).mean())
            per[view] = dict(flag=flag, med=float(np.median(x)))
        if adopted not in per:
            continue
        base = per[adopted]["flag"]
        others = {v: p for v, p in per.items() if v != adopted}
        rows.append(dict(
            exercise=ex, condition=r["condition"], feature=feat, op=op, threshold=thr, view=adopted,
            flag_adopted=base,
            flag_min=min(p["flag"] for p in others.values()), flag_max=max(p["flag"] for p in others.values()),
            med_adopted=per[adopted]["med"],
            med_min=min(p["med"] for p in others.values()), med_max=max(p["med"] for p in others.values()),
            **{f"flag_{v}": per[v]["flag"] for v in "ABCDE" if v in per},
        ))
    out = pd.DataFrame(rows)
    out["swing"] = (out.flag_max - out.flag_min)
    out.to_csv(HERE / "outputs" / "floor_threshold_view.csv", index=False, encoding="utf-8-sig")

    L = ["# 바닥 임계값은 '어느 각도에서 찍었는가'에 묶여 있다\n",
         "각 규칙의 임계값은 **채택 뷰**에서 적합됐다. 같은 임계값을 같은 종목의 **다른 뷰**(같은 클립을 동시 촬영한 다른 카메라)에 그대로 적용하면 위반 플래그 비율이 얼마나 달라지는가.",
         "실제 위반율은 뷰와 무관하므로(같은 사람·같은 세트), 아래 변동은 전부 **임계값의 시점 의존성**이다.\n",
         "| 종목 | 조건 | 임계값 | 채택 뷰 | 채택 뷰 플래그율 | 다른 뷰 범위 | 변동폭 |",
         "|---|---|---|---|---|---|---|"]
    for _, r in out.sort_values("swing", ascending=False).iterrows():
        L.append(f"| {r.exercise} | {r.condition[:18]} | `{r.op} {r.threshold:g}` | {r.view} | "
                 f"**{r.flag_adopted*100:.0f}%** | {r.flag_min*100:.0f}~{r.flag_max*100:.0f}% | {r.swing*100:.0f}%p |")
    L += ["", f"- 플래그율 변동폭 중앙값 **{out.swing.median()*100:.0f}%p** (최대 {out.swing.max()*100:.0f}%p)",
          f"- 규칙 {int((out.swing >= 0.3).sum())}/{len(out)} 개는 뷰를 바꾸면 플래그율이 30%p 이상 흔들린다",
          "",
          "## 무슨 뜻인가\n",
          "2D 피처는 **투영값**이다. 카메라가 비스듬히 보면 실제 10cm 처짐이 화면에서는 6cm 로 눌려 보이고, 정면에서 보면 10cm 그대로 보인다. ",
          "피처를 몸통 길이로 정규화해도 몸통 자체가 같이 눌리므로 완전히는 상쇄되지 않는다. 그래서 **같은 자세라도 시점이 바뀌면 숫자가 바뀐다**.",
          "",
          "임계값은 그 숫자에 그은 선이다. 선을 그은 시점과 재는 시점이 다르면, 규칙이 '무엇을 위반으로 볼지'가 달라진다 — ",
          "위 표에서 같은 임계값이 어떤 뷰에서는 20% 를, 다른 뷰에서는 80% 를 위반으로 부르는 이유다.",
          "",
          "## 그래서 앱에서 무엇이 문제인가\n",
          "- 임계값을 적합한 AIHub 카메라: **서있는 사람 기준 높이**(약 허리~가슴), 바닥에 누운 사람을 **내려다봄**",
          "- 앱이 사용자에게 요구하는 거치: **바닥 높이, 몸 옆** (원근 단축이 가장 적은 시점 — §25 에서 이게 최선이라 결론)",
          "- 두 시점은 다르다. 그런데 규칙에 박힌 숫자는 전자에서 나왔다.",
          "",
          "**즉 지금 바닥 규칙은 '무엇을 재는가'(피처)와 '어느 방향이 위반인가'(부호)는 맞지만, '얼마부터 위반인가'(임계값)는 앱의 촬영 조건에서 검증된 적이 없다.** ",
          "그래서 전 규칙 status=beta 이고, 세트 로그 재보정(§14)이 필수 단계로 남아 있다.",
          "",
          "## 한계\n",
          "AIHub 5뷰는 전부 서있는 높이라 **바닥 높이 뷰는 이 데이터에 없다**. 위 변동폭은 '높이는 같고 각도만 다를 때'의 값이므로, ",
          "높이까지 바뀌는 실제 앱 시점에서는 최소 이만큼, 대개는 더 크게 벌어진다고 봐야 한다. 정확한 값은 실제 촬영으로만 알 수 있다."]
    (HERE / "outputs" / "FLOOR_THRESHOLD_VIEW.md").write_text("\n".join(L), encoding="utf-8")
    print("\n".join(L[:26]))
    print(f"\n[done] → outputs/FLOOR_THRESHOLD_VIEW.md")


if __name__ == "__main__":
    main()
