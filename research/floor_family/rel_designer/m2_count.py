# -*- coding: utf-8 -*-
"""M2: 제안 '바닥 굽힘 사이클' 추적기(파이썬 원형)를 MM-Fit 윗몸일으키기(앱 모델 2D)에 300 ms 판정 격자로 돌려 횟수·헛카운트를 잰다.
신호 = 보이는 쪽 어깨–골반 현이 발목→골반 선에서 들린 각(°). 비교: 앱 현행 head_ground(양쪽 게이트·스트리밍 접지선) 계산율."""
import numpy as np, pandas as pd
from pathlib import Path

S = Path(__file__).resolve().parent
df = pd.read_parquet(S / "m2_situps.parquet")
GRID = 300


def selev(v, g):
    L = max(np.hypot(*g), 1e-9); gx, gy = g[0] / L, g[1] / L
    nx, ny = -gy, gx
    if ny > 0: nx, ny = -nx, -ny
    return float(np.degrees(np.arctan2(v[0] * nx + v[1] * ny, v[0] * gx + v[1] * gy)))


def near_side(d):
    l = d[[f"v{i}" for i in (11, 23, 25, 27)]].min(1).mean(); r = d[[f"v{i}" for i in (12, 24, 26, 28)]].min(1).mean()
    return (12, 24, 26, 28, 8) if r > l else (11, 23, 25, 27, 7)


class FloorCycle:
    """정지 기준 → 출발(running 최소 + depart) → 정점 → 되돌아옴(진폭의 ret 만큼, 체류 2프레임·150 ms) → 판별(진폭 ≥ lift) → 재기준.
    LegCycleTracker 틀에 '진폭 기준 복귀'(긴장 유지형: 바닥까지 안 돌아와도 된다)를 더한 것."""
    def __init__(self, depart=5.0, ret=0.6, lift=5.0, dwell_ms=150, dwell_frames=2, min_cycle=800, max_cycle=8000, max_gap=750):
        self.p = dict(depart=depart, ret=ret, lift=lift, dwell_ms=dwell_ms, dwell_frames=dwell_frames, min_cycle=min_cycle, max_cycle=max_cycle, max_gap=max_gap)
        self.trough = None; self.moving = False; self.peak = None; self.start = 0; self.back_at = None; self.dwell = 0; self.last = None
        self.count = 0; self.rejected = []; self.cycles = []

    def on(self, t, x):
        p = self.p
        if x is None or not np.isfinite(x):
            return
        if self.last is not None and t - self.last > p["max_gap"]:
            self.moving = False; self.trough = None; self.back_at = None; self.dwell = 0
        self.last = t
        if not self.moving:
            self.trough = x if self.trough is None else min(self.trough, x)
            if x >= self.trough + p["depart"]:
                self.moving = True; self.peak = x; self.start = t; self.back_at = None; self.dwell = 0
            return
        self.peak = max(self.peak, x)
        if t - self.start > p["max_cycle"]:
            self.rejected.append((t, "timeout")); self.moving = False; self.trough = x; return
        exc = self.peak - self.trough
        if x <= self.peak - p["ret"] * exc:
            if self.back_at is None: self.back_at = t
            self.dwell += 1
            if t - self.back_at >= p["dwell_ms"] and self.dwell >= p["dwell_frames"] and t - self.start >= p["min_cycle"]:
                if exc >= p["lift"]: self.count += 1; self.cycles.append((self.start, t, exc))
                else: self.rejected.append((t, "shallow"))
                self.moving = False; self.trough = x
        else:
            self.back_at = None; self.dwell = 0


rows = []
for (w, k), d in df.groupby(["w", "set"]):
    d = d.sort_values("t_ms")
    sh, hp, kn, an, ear = near_side(d[d.in_set])
    # 300 ms 격자: 칸마다 첫 프레임
    d = d.assign(bin=d.t_ms // GRID).groupby("bin").head(1)
    chord = []; avail_near = []; avail_mid = []
    for r in d.itertuples(index=False):
        rr = r._asdict()
        if not np.isfinite(rr.get(f"x{sh}", np.nan)):
            chord.append(np.nan); avail_near.append(False); avail_mid.append(False); continue
        P = lambda i: np.array([rr[f"x{i}"] * rr["W"], rr[f"y{i}"] * rr["H"]])
        okn = min(rr[f"v{sh}"], rr[f"v{hp}"], rr[f"v{an}"]) >= .5
        avail_near.append(okn)
        avail_mid.append(min(rr["v27"], rr["v28"], rr["v0"], rr["v7"], rr["v8"]) >= .35 and min(rr["v11"], rr["v12"], rr["v23"], rr["v24"]) >= .2)
        chord.append(selev(P(sh) - P(hp), P(hp) - P(an)) if okn else np.nan)
    d = d.assign(chord=chord, an=avail_near, am=avail_mid)
    res = {}
    for name, kw in {"d5": dict(depart=5, lift=5), "d8": dict(depart=8, lift=8), "d5L10": dict(depart=5, lift=10)}.items():
        fc = FloorCycle(**kw)
        for r in d.itertuples(index=False):
            fc.on(r.t_ms, r.chord)
        ins = [c for c in fc.cycles if d.t_ms[d.in_set].min() - 1500 <= c[1] <= d.t_ms[d.in_set].max() + 1500]
        outs = len(fc.cycles) - len(ins)
        res[name] = (len(ins), outs, len(fc.rejected), np.round(np.median([c[2] for c in fc.cycles]), 1) if fc.cycles else np.nan)
    insd = d[d.in_set]
    rest = d[~d.in_set].chord
    rows.append(dict(w=w, set=k, reps=int(d.reps.iloc[0]), near_avail=round(insd.an.mean(), 2), mid_head_ground_avail=round(insd.am.mean(), 2),
                     chord_p10=round(np.nanquantile(insd.chord, .1), 1), chord_p90=round(np.nanquantile(insd.chord, .9), 1),
                     **{f"{n}_in/out/rej/amp": v for n, v in res.items()}))
out = pd.DataFrame(rows)
pd.set_option("display.width", 250); pd.set_option("display.max_columns", 20)
print(out.to_string(index=False))
for n in ("d5", "d8", "d5L10"):
    col = f"{n}_in/out/rej/amp"
    err = np.array([v[0] for v in out[col]]) - out.reps.to_numpy()
    print(n, "세트 정확(±0)", int((err == 0).sum()), "±1", int((np.abs(err) <= 1).sum()), "/", len(err), "| 여유 구간 헛사이클 합", int(sum(v[1] for v in out[col])), "| 오차", err.tolist())
