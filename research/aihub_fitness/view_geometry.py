#!/usr/bin/env python
"""AIHub 카메라 코드(A~E)가 실제로 어느 방향인지 관측으로 확정한다 (spec §26).

규칙 JSON 의 `view_best_front` 는 데이터셋 카메라 코드라 사용자에게 의미가 없다.
'정면/측면/사선' 으로 번역하려면 각 코드가 실제로 어느 방향인지 알아야 하는데,
**서서 하는 종목과 바닥 종목에서 같은 코드가 다른 뜻**이다 — 카메라는 방에 고정인데
사람이 누우면 서 있을 때 정면이던 카메라가 몸의 측면을 보게 되기 때문이다.

지표:
  서서 — front_ratio: LShoulder 가 화면 오른쪽에 있는 비율(사람이 카메라를 마주보면 그렇다).
         sh_ratio: 어깨폭/몸통길이. 어깨선을 정면에서 볼수록 크고, 옆에서 볼수록 작다.
  바닥 — body_sh: (어깨→발목 길이)/어깨폭. 몸을 옆에서 볼수록 몸 축이 화면에 길게 펼쳐져 커진다.

출력: outputs/view_geometry.md
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

HERE = Path(__file__).resolve().parent
OUT = HERE / "outputs"
FLOOR = ["크런치", "푸시업", "니푸쉬업", "플랭크", "라잉 레그 레이즈", "힙쓰러스트", "시저크로스", "바이시클 크런치", "Y - Exercise"]
J = ["LShoulder", "RShoulder", "LHip", "RHip", "LAnkle", "RAnkle", "Nose", "Neck"]


def main():
    cols = ["clip_id", "view_letter"] + [f"{j}_{a}" for j in J for a in "xy"]
    clips = pd.read_parquet(OUT / "clips.parquet")[["clip_id", "exercise"]]
    k2 = pd.read_parquet(OUT / "kp2d.parquet", columns=cols).merge(clips, on="clip_id")

    st = k2[~k2.exercise.isin(FLOOR)]
    sh_w = np.hypot(st.LShoulder_x - st.RShoulder_x, st.LShoulder_y - st.RShoulder_y)
    torso = np.hypot((st.LShoulder_x + st.RShoulder_x) / 2 - (st.LHip_x + st.RHip_x) / 2,
                     (st.LShoulder_y + st.RShoulder_y) / 2 - (st.LHip_y + st.RHip_y) / 2)
    g1 = pd.DataFrame(dict(view=st.view_letter.values,
                           front=(st.LShoulder_x - st.RShoulder_x).to_numpy() > 0,
                           sh_ratio=(sh_w / np.maximum(torso, 1e-6)).to_numpy()))
    a1 = g1.groupby("view").agg(front_ratio=("front", "mean"), sh_ratio=("sh_ratio", "median")).round(3)

    fl = k2[k2.exercise.isin(FLOOR)]
    shm = np.stack([(fl.LShoulder_x + fl.RShoulder_x) / 2, (fl.LShoulder_y + fl.RShoulder_y) / 2], 1)
    anm = np.stack([(fl.LAnkle_x + fl.RAnkle_x) / 2, (fl.LAnkle_y + fl.RAnkle_y) / 2], 1)
    body = np.hypot(*(anm - shm).T)
    shw2 = np.hypot(fl.LShoulder_x - fl.RShoulder_x, fl.LShoulder_y - fl.RShoulder_y).to_numpy()
    g2 = pd.DataFrame(dict(view=fl.view_letter.values, exercise=fl.exercise.values,
                           body_sh=body / np.maximum(shw2, 1e-6)))
    a2 = g2.groupby("view").body_sh.median().round(2)

    def name_standing(v):
        r = a1.loc[v]
        if r.front_ratio < 0.5:
            return "뒤 비스듬히 (후방 사선)"
        return "정면" if r.sh_ratio >= a1.sh_ratio.max() - 1e-9 else "앞 비스듬히 (전방 사선)"

    def name_floor(v):
        x = a2[v]
        if x >= 10:
            return "측면 (몸 옆)"
        return "머리·발 쪽 (몸 축 방향)" if x <= a2.min() + 1e-9 else "측면 비스듬히"

    L = ["# 카메라 코드 → 실제 촬영 방향 (관측 확정)\n",
         "규칙 JSON 의 `view_best_front`(A~E)는 AIHub 카메라 코드다. 사용자에게 '정면/측면'으로 보여주려면",
         "각 코드의 실제 방향을 알아야 하는데, **서서 하는 종목과 바닥 종목에서 뜻이 다르다** —",
         "카메라는 방에 고정이고 사람이 누우면 서 있을 때 정면이던 카메라가 몸의 측면을 보게 되기 때문이다.\n",
         "## 서서 하는 종목\n",
         "- `front_ratio` = 왼어깨가 화면 오른쪽에 오는 비율 (사람이 카메라를 마주보면 그렇다)",
         "- `sh_ratio` = 어깨폭/몸통. 어깨선을 정면으로 볼수록 크다\n",
         "| 코드 | front_ratio | sh_ratio | 판정 |", "|---|---|---|---|"]
    for v in a1.index:
        L.append(f"| {v} | {a1.loc[v, 'front_ratio']:.3f} | {a1.loc[v, 'sh_ratio']:.3f} | **{name_standing(v)}** |")
    L += ["", "## 바닥 종목\n",
          "- `body_sh` = (어깨→발목)/어깨폭. 몸을 옆에서 볼수록 몸 축이 길게 펼쳐져 커진다\n",
          "| 코드 | body_sh | 판정 |", "|---|---|---|"]
    for v in a2.index:
        L.append(f"| {v} | {a2[v]:.2f} | **{name_floor(v)}** |")
    L += ["", "### 종목별 body_sh\n", "| 종목 | " + " | ".join(a2.index) + " |", "|---" * (len(a2) + 1) + "|"]
    piv = g2.pivot_table(index="exercise", columns="view", values="body_sh", aggfunc="median").round(2)
    for ex, row in piv.iterrows():
        L.append(f"| {ex} | " + " | ".join(f"{row[v]:.2f}" for v in a2.index) + " |")
    L += ["", "## 결론 — 앱 표기\n",
          "| 코드 | 서서 하는 종목 | 바닥 종목 |", "|---|---|---|",
          "| C | 정면 | **측면** |", "| B, D | 앞 비스듬히 (±40°) | 측면 비스듬히 |",
          "| A | 뒤 비스듬히 | 머리·발 쪽 |", "| E | 뒤 비스듬히 | 측면 비스듬히 |",
          "", "> C 가 서서는 '정면'인데 바닥에서는 '측면'이다. 코드를 그대로 문구로 옮기면 **정확히 반대로 안내**하게 된다.",
          "> 앱 구현은 `PostureViewGuide.kt`(ViewGuide) 가 단일 출처."]
    (OUT / "view_geometry.md").write_text("\n".join(L), encoding="utf-8")
    print(a1.to_string())
    print(a2.to_string())
    print(f"[done] → {OUT / 'view_geometry.md'}")


if __name__ == "__main__":
    main()
