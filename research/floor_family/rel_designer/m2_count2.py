# -*- coding: utf-8 -*-
"""M2b: 누운 기준(정지·LYING_MAX 이하) 필수 + 앉음 체류 기각 + 이탈 시 기준 무효화를 더한 원형."""
import warnings; warnings.filterwarnings("ignore")
import numpy as np, pandas as pd
exec(open("m2_count.py", encoding="utf-8").read().split("class FloorCycle")[0])

class FloorCycle2:
    def __init__(self, depart=5.0, ret=0.6, lift=5.0, lying_max=35.0, sit=55.0, sit_ms=1500, still_ms=600, still_tol=4.0,
                 dwell_ms=150, dwell_frames=2, min_cycle=800, max_cycle=8000, max_gap=750, away_ms=2000):
        self.__dict__.update(locals()); del self.__dict__["self"]
        self.base = None; self.still = []; self.moving = False; self.trough = None; self.peak = None; self.start = 0
        self.back_at = None; self.dwell = 0; self.last = None; self.sit_t = 0; self.away_since = None
        self.cycles = []; self.rejected = []
    def on(self, t, x):
        if x is None or not np.isfinite(x): return
        if self.last is not None and t - self.last > self.max_gap:
            self.moving = False; self.still = []; self.back_at = None; self.dwell = 0
        dt = 0 if self.last is None else t - self.last
        self.last = t
        # 정지 창 → 누운 기준(아래로는 언제든, 처음엔 LYING_MAX 이하만)
        self.still.append((t, x)); self.still = [(a, b) for a, b in self.still if t - a <= 1000]
        if not self.moving and len(self.still) >= 3 and self.still[-1][0] - self.still[0][0] >= self.still_ms:
            vs = [b for _, b in self.still]
            if max(vs) - min(vs) <= self.still_tol:
                m = float(np.median(vs))
                if m <= self.lying_max and (self.base is None or m < self.base - 2): self.base = m; self.trough = m
        if self.base is None: return
        if not self.moving:
            # 누운 영역을 오래 벗어나면(앉음·일어남) 기준을 버린다 — 다시 누워 정지해야 센다
            if x > self.lying_max:
                self.away_since = self.away_since or t
                if t - self.away_since >= self.away_ms: self.base = None; self.away_since = None; return
            else: self.away_since = None
            self.trough = x if self.trough is None else min(self.trough, x)
            if x >= self.trough + self.depart and self.trough <= self.base + 8:
                self.moving = True; self.peak = x; self.start = t; self.back_at = None; self.dwell = 0; self.sit_t = 0
            return
        self.peak = max(self.peak, x)
        if x >= self.base + self.sit: self.sit_t += dt
        if t - self.start > self.max_cycle:
            self.rejected.append((t, "timeout")); self.moving = False; self.trough = x; return
        exc = self.peak - self.trough
        if x <= self.peak - self.ret * exc:
            if self.back_at is None: self.back_at = t
            self.dwell += 1
            if t - self.back_at >= self.dwell_ms and self.dwell >= self.dwell_frames and t - self.start >= self.min_cycle:
                if self.sit_t >= self.sit_ms: self.rejected.append((t, "sat"))
                elif exc < self.lift: self.rejected.append((t, "shallow"))
                else: self.cycles.append((self.start, t, exc))
                self.moving = False; self.trough = x
                nb = float(np.median([x])); 
        else:
            self.back_at = None; self.dwell = 0

res = []
for (w, k), d in df.groupby(["w", "set"]):
    d = d.sort_values("t_ms"); sh, hp, kn, an, ear = near_side(d[d.in_set])
    d = d.assign(bin=d.t_ms // GRID).groupby("bin").head(1)
    ch = []
    for r in d.itertuples(index=False):
        rr = r._asdict()
        if not np.isfinite(rr.get(f"x{sh}", np.nan)) or min(rr[f"v{sh}"], rr[f"v{hp}"], rr[f"v{an}"]) < .5: ch.append(np.nan); continue
        P = lambda i: np.array([rr[f"x{i}"] * rr["W"], rr[f"y{i}"] * rr["H"]])
        ch.append(selev(P(sh) - P(hp), P(hp) - P(an)))
    d = d.assign(chord=ch)
    s0, s1 = d.t_ms[d.in_set].min(), d.t_ms[d.in_set].max()
    row = dict(w=w, set=k, reps=int(d.reps.iloc[0]))
    for name, kw in {"A": {}, "A_sit1000": dict(sit_ms=1000), "A_lift8": dict(lift=8)}.items():
        fc = FloorCycle2(**kw)
        for r in d.itertuples(index=False): fc.on(r.t_ms, r.chord)
        ins = sum(1 for c in fc.cycles if s0 - 1500 <= c[1] <= s1 + 1500); outs = len(fc.cycles) - ins
        row[name] = f"{ins}/{outs}/{','.join(r for _, r in fc.rejected) or '-'}"
        row[name + "_err"] = ins - row["reps"]; row[name + "_out"] = outs
    res.append(row)
o = pd.DataFrame(res)
print(o[["w", "set", "reps", "A", "A_sit1000", "A_lift8"]].to_string(index=False))
for n in ("A", "A_sit1000", "A_lift8"):
    e = o[n + "_err"].to_numpy()
    print(n, "정확", int((e == 0).sum()), "±1", int((abs(e) <= 1).sum()), "/15 | 세트 밖 헛사이클", int(o[n + "_out"].sum()), "| 오차", e.tolist())
