# -*- coding: utf-8 -*-
"""M2d: 절대 '누움' 게이트 없이 — 진폭 히스테리시스(최소점 기준) + 최대 사이클 5 s + 첫 회 잠정(8 s 안 둘째 회 없으면 거둠) + 연속 2 s 정지 상단(앉음) 무효."""
import warnings; warnings.filterwarnings("ignore")
import numpy as np, pandas as pd
exec(open("m2_count3.py", encoding="utf-8").read().split("class FloorCycle3")[0])
from m2_count3 import chords

class FloorCycle4:
    def __init__(self, depart=5.0, ret=0.6, lift=5.0, dwell_ms=150, dwell_frames=2, min_cycle=800, max_cycle=5000, max_gap=750, confirm_ms=8000):
        self.__dict__.update(locals()); del self.__dict__["self"]
        self.moving = False; self.trough = None; self.peak = None; self.start = 0; self.back_at = None; self.dwell = 0; self.last = None
        self.cycles = []; self.rejected = []; self.provisional = None
    def on(self, t, x):
        if x is None or not np.isfinite(x): return
        if self.last is not None and t - self.last > self.max_gap:
            self.moving = False; self.back_at = None; self.dwell = 0; self.trough = None
        self.last = t
        if self.provisional is not None and t - self.provisional[1] > self.confirm_ms and len(self.cycles) == 1:
            self.rejected.append((t, "retracted")); self.cycles.clear(); self.provisional = None
        if not self.moving:
            self.trough = x if self.trough is None else min(self.trough, x)
            if x >= self.trough + self.depart:
                self.moving = True; self.peak = x; self.start = t; self.back_at = None; self.dwell = 0
            return
        self.peak = max(self.peak, x)
        if t - self.start > self.max_cycle:
            self.rejected.append((t, "timeout")); self.moving = False; self.trough = None; return
        exc = self.peak - self.trough
        if x <= self.peak - self.ret * exc:
            if self.back_at is None: self.back_at = t
            self.dwell += 1
            if t - self.back_at >= self.dwell_ms and self.dwell >= self.dwell_frames and t - self.start >= self.min_cycle:
                if exc < self.lift: self.rejected.append((t, "shallow"))
                else:
                    self.cycles.append((self.start, t, exc))
                    if len(self.cycles) == 1: self.provisional = self.cycles[0]
                    else: self.provisional = None
                self.moving = False; self.trough = x
        else:
            self.back_at = None; self.dwell = 0

res = []
for (w, k), d in df.groupby(["w", "set"]):
    d = chords(d.sort_values("t_ms"))
    s0, s1 = d.t_ms[d.in_set].min(), d.t_ms[d.in_set].max()
    row = dict(w=w, set=k, reps=int(d.reps.iloc[0]))
    for name, kw in {"C": {}, "C_ret5": dict(ret=0.5), "C_noconfirm": dict(confirm_ms=10**9), "C_lift8": dict(lift=8)}.items():
        fc = FloorCycle4(**kw)
        for r in d.itertuples(index=False): fc.on(r.t_ms, r.chord)
        ins = sum(1 for c in fc.cycles if s0 - 1500 <= c[1] <= s1 + 1500); outs = len(fc.cycles) - ins
        row[name] = f"{ins}/{outs}/{','.join(r for _, r in fc.rejected) or '-'}"; row[name + "_err"] = ins - row["reps"]; row[name + "_out"] = outs
    res.append(row)
o = pd.DataFrame(res)
print(o[["w", "set", "reps", "C", "C_ret5", "C_noconfirm", "C_lift8"]].to_string(index=False))
for n in ("C", "C_ret5", "C_noconfirm", "C_lift8"):
    e = o[n + "_err"].to_numpy()
    print(n, "정확", int((e == 0).sum()), "±1", int((abs(e) <= 1).sum()), "/15 | 세트 밖 헛사이클", int(o[n + "_out"].sum()), "| 오차", e.tolist())
