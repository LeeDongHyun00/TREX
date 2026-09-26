#!/usr/bin/env python
"""실기기 세트 로그 vs AIHub — 시점 격차의 첫 실측, 그리고 재배치가 필요한가.

지금까지 '임계값이 서있는 높이 카메라에 묶여 있다'는 주장의 근거는 AIHub 뷰 간 비교(각도만)였다.
바닥 높이 거치에서의 격차는 AIHub 로 검증 불가였다. 실기기 로그가 그 축의 첫 데이터다.

각 바닥 규칙에 대해:
  device  — 실기기 세트에서 계산한 통계값 (앱이 실제로 판정에 쓰는 값)
  normal  — AIHub 채택 뷰 정상 클립의 중앙값 (규칙의 normal_median)
  thr     — AIHub 임계값
  z       — (device − normal) / AIHub 정상 IQR 환산 스케일. |z| 가 크면 시점이 다르다는 뜻
  raw 판정 — 현재 규칙이 이 세트를 위반으로 부르는가

핵심 진단: 실기기 값이 AIHub 정상 분포에서 얼마나 벗어나 있는가.
  - 벗어남이 작다  → raw 임계값이 그대로 쓸 만하다 (재배치 불필요, 내 주장 반증)
  - 벗어남이 크다  → raw 판정은 신뢰 불가, 재배치 필요 (주장 지지)
라벨이 없으므로 '정확도'는 못 재고 **분포 정합성과 판정 쏠림**만 잰다 — 그게 이 데이터로 정직하게
말할 수 있는 전부다. (사용자가 정자세로 찍었다면 위반 플래그가 쏟아지는 것 자체가 오탐 신호다.)

출력: outputs/DEVICE_GAP.md, outputs/device_gap.csv
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
from export_floor_rules import JOINTS, MIN_FRAMES, SRC, aggregate_clip, frame_features_stream  # noqa: E402

RULES = HERE.parent.parent / "app" / "src" / "main" / "assets" / "posture" / "rules_floor_v0.json"
LOGS = HERE / "outputs" / "device_logs"
# 앱 운동명 → AIHub 규칙 종목 (PostureLive.postureExerciseMap 의 바닥 부분)
APP2AIHUB = {"푸시업": "푸시업", "푸쉬업": "푸시업", "플랭크": "플랭크", "니푸쉬업": "니푸쉬업",
             "크런치": "크런치", "라잉 레그 레이즈": "라잉 레그 레이즈", "힙쓰러스트": "힙쓰러스트",
             "시저크로스": "시저크로스", "Y - Exercise": "Y - Exercise"}


def stat_of(vals: list[float], stat: str) -> float:
    a = np.asarray([v for v in vals if np.isfinite(v)], dtype=float)
    if len(a) < MIN_FRAMES:
        return np.nan
    return {"mean": a.mean, "min": a.min, "max": a.max, "std": a.std}.get(stat, lambda: a.max() - a.min())()


def load_device() -> list[dict]:
    out = []
    for f in sorted(LOGS.glob("*.jsonl")):
        for line in f.read_text(encoding="utf-8").splitlines():
            if line.strip():
                out.append(json.loads(line))
    return out


def aihub_normal_spread(rules) -> dict[tuple[str, str], tuple[float, float]]:
    """규칙별 AIHub 채택 뷰 **정상 클립**의 (중앙값, IQR) — 격차를 스케일 없는 z 로 바꾸기 위해."""
    cols = ["clip_id", "view_letter", "frame_idx"] + [f"{j}_{a}" for j in JOINTS for a in "xy"]
    clips = pd.read_parquet(SRC / "clips.parquet")[["clip_id", "exercise"]]
    conds = pd.read_parquet(SRC / "conditions.parquet")
    exs = sorted({r["exercise"] for r in rules})
    k2 = pd.read_parquet(SRC / "kp2d.parquet", columns=cols).merge(clips, on="clip_id")
    k2 = k2[k2.exercise.isin(exs)].sort_values(["clip_id", "view_letter", "frame_idx"])
    feat_rows = []
    for (cid, view), d in k2.groupby(["clip_id", "view_letter"]):
        if len(d) < MIN_FRAMES:
            continue
        frames = np.stack([d[[f"{j}_x", f"{j}_y"]].to_numpy(dtype=np.float64) for j in JOINTS], axis=1)
        r = aggregate_clip(frame_features_stream(frames, JOINTS))
        r.update(clip_id=cid, view=view, exercise=d.exercise.iloc[0])
        feat_rows.append(r)
    feats = pd.DataFrame(feat_rows)
    out = {}
    for r in rules:
        ex, feat, v = r["exercise"], r["feature"], r["view_best_front"]
        yv = conds[(conds.exercise == ex) & (conds.condition == r["condition"])] \
            .drop_duplicates("clip_id").set_index("clip_id")["value"]
        d = feats[(feats.exercise == ex) & (feats.view == v)]
        y = yv.reindex(d.clip_id)
        x = d.loc[(y == True).to_numpy(), feat].to_numpy(float)   # noqa: E712  정상(value=True)
        x = x[np.isfinite(x)]
        if len(x) >= 10:
            out[(ex, r["condition"])] = (float(np.median(x)), float(np.subtract(*np.percentile(x, [75, 25]))))
    return out


def main():
    rules = json.load(open(RULES, encoding="utf-8"))["rules"]
    logs = load_device()
    spread = aihub_normal_spread(rules)
    rows = []
    for g in logs:
        ex_app = g.get("exercise", "")
        ex = APP2AIHUB.get(ex_app)
        if not ex:
            continue
        frames = g.get("frames", [])
        for r in rules:
            if r["exercise"] != ex:
                continue
            base, stat = r["base_feature"], r["stat"]
            vals = [fr["features"][base] for fr in frames if base in fr.get("features", {})]
            v = stat_of(vals, stat)
            if not np.isfinite(v):
                continue
            nm = r.get("normal_median")
            med, iqr = spread.get((ex, r["condition"]), (nm, np.nan))
            z = (v - med) / iqr if (iqr and np.isfinite(iqr) and iqr > 0) else np.nan
            flag = (v > r["threshold"]) if r["op"] == ">" else (v < r["threshold"])
            rows.append(dict(
                set_id=g.get("set_id", "")[:15], created=g.get("created_at", "")[:19], exercise=ex,
                note=g.get("note"), rules_version=g.get("rules_version"), tilt=g.get("tilt_deg"),
                condition=r["condition"], feature=r["feature"], op=r["op"], threshold=r["threshold"],
                device=v, aihub_normal=med, aihub_iqr=iqr, z=z, raw_flag=bool(flag),
                n_frames=len([x for x in vals if np.isfinite(x)]),
            ))
    out = pd.DataFrame(rows)
    if out.empty:
        sys.exit("[err] 바닥 종목 세트가 없다")
    out.to_csv(HERE / "outputs" / "device_gap.csv", index=False, encoding="utf-8-sig")

    n_sets = out.set_id.nunique()
    L = ["# 실기기 로그 vs AIHub — 시점 격차의 첫 실측\n",
         f"- 실기기 세트 {n_sets}개 ({', '.join(sorted(out.exercise.unique()))}), 규칙×세트 {len(out)}건",
         "- `z` = (실기기값 − AIHub 정상중앙값) / AIHub 정상 IQR. **|z|>1.5 면 그 규칙의 정상 분포 밖**",
         "- 라벨이 없으므로 정확도는 못 잰다 — 분포 정합성과 판정 쏠림만 본다\n",
         "## 1. 규칙별 격차\n",
         "| 종목 | 조건 | 통계량 | 실기기 | AIHub 정상 | IQR | z | 임계값 | raw 판정 |",
         "|---|---|---|---|---|---|---|---|---|"]
    for _, r in out.sort_values("z", key=abs, ascending=False).iterrows():
        L.append(f"| {r.exercise} | {r.condition[:14]} | `{r.feature.rsplit('__',1)[1]}` | {r.device:.2f} | "
                 f"{r.aihub_normal:.2f} | {r.aihub_iqr:.2f} | **{r.z:+.1f}** | {r.op} {r.threshold:g} | "
                 f"{'⚠위반' if r.raw_flag else '정상'} |")
    absz = out.z.abs()
    L += ["",
          f"- |z| 중앙값 **{absz.median():.1f}**, |z|>1.5 인 규칙×세트 **{int((absz > 1.5).sum())}/{len(out)}건**",
          f"- raw 임계값 위반 플래그율 **{out.raw_flag.mean()*100:.0f}%** ({int(out.raw_flag.sum())}/{len(out)})",
          "",
          "## 2. 해석\n"]
    if absz.median() > 1.5:
        L += [f"**실기기 값이 AIHub 정상 분포 밖에 있다(|z| 중앙값 {absz.median():.1f}).** 같은 동작을 재는데 값이 이만큼 다르다는 것은 "
              "시점(높이·각도)이 다르다는 뜻이고, AIHub 임계값을 그대로 적용한 판정은 신뢰할 수 없다. "
              "→ **정상-앵커 재배치(v0.2)가 필요하다는 첫 실측 근거.**"]
    else:
        L += [f"**실기기 값이 AIHub 정상 분포 안에 있다(|z| 중앙값 {absz.median():.1f}).** 적어도 이 세트들에서는 raw 임계값이 크게 어긋나지 않는다 — "
              "재배치의 필요성은 이 데이터로는 입증되지 않는다."]
    L += ["",
          "## 3. 한계 (중요)\n",
          "- **라벨이 없다.** 이 세트들이 정자세였는지 위반이었는지 기록이 없으므로, 위반 플래그가 오탐인지 정탐인지 구분 못 한다. z 는 '분포가 다르다'만 말한다.",
          "- 세트 수가 적고 **한 사람**이다 — 일반화 불가.",
          "- 이 로그는 `floor_v0.1` 시절 앱이 만들었다. 피처 정의는 v0.2 와 동일(임계값·앵커만 추가)이라 값 비교는 유효하다.",
          "- 폰 기울기(tilt_deg)가 세트마다 다르다 — 같은 사용자라도 거치가 매번 다르면 재배치도 매번 필요하다는 뜻(기준선 유효기간 문제).",
          "",
          "## 4. 다음 단계\n",
          "정자세 라벨이 붙은 기준선 세트(k=3)를 찍으면 (a) 앵커가 생겨 재배치 판정이 가능해지고, "
          "(b) '정자세인데 raw 로는 위반' 케이스가 몇 건인지 세어 **오탐 감소량을 직접 측정**할 수 있다."]
    (HERE / "outputs" / "DEVICE_GAP.md").write_text("\n".join(L), encoding="utf-8")
    print("\n".join(L))


if __name__ == "__main__":
    main()
