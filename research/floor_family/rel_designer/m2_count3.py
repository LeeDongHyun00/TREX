# -*- coding: utf-8 -*-
"""M2c: 누운 영역 기준(정지 불요, 최근 1.5 s 에 누운 영역 3프레임) + 사이클 최대 길이(일어나 앉았다 다시 눕기 배제) + 이탈 시 기준 무효화."""
import warnings; warnings.filterwarnings("ignore")
import numpy as np, pandas as pd
exec(open("m2_count.py", encoding="utf-8").read().split("class FloorCycle")[0])

class FloorCycle3:
    def __init__(self, depart=5.0, ret=0.6, lift=5.0, lying_max=35.0, near_base=8.0, dwell_ms=150, dwell_frames=2,
                 min_cycle=800, max_cycle=5000, max_gap=750, away_ms=2000):
        self.__dict__.update(locals()); del self.__dict__["self"]
        self.base = None; self.lying = []; self.moving = False; self.trough = None; self.peak = None; self.start = 0
        self.back_at = None; self.dwell = 0; self.last = None; self.away_since = None
        self.cycles = []; self.rejected = []
    def on(self, t, x):
        if x is None or not np.isfinite(x): return
        if self.last is not None and t - self.last > self.max_gap:
            self.moving = False; self.back_at = None; self.dwell = 0; self.trough = None
        self.last = t
        if x <= self.lying_max: self.lying.append((t, x))
        self.lying = [(a, b) for a, b in self.lying if t - a <= 1500]
        if not self.moving and len(self.lying) >= 3:
            m = float(np.median([b for _, b in self.lying]))
            if self.base is None or m < self.base: self.base = m
        if self.base is None: return
        if not self.moving:
            if x > self.lying_max:
                self.away_since = self.away_since or t
                if t - self.away_since >= self.away_ms: self.base = None; self.away_since = None; self.trough = None; self.lying = []; return
            else: self.away_since = None
            self.trough = x if self.trough is None else min(self.trough, x)
            if x >= self.trough + self.depart and self.trough <= self.base + self.near_base:
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
                else: self.cycles.append((self.start, t, exc))
                self.moving = False; self.trough = x
                # 재기준: 되돌아온 값이 기준보다 낮으면 내린다(올리는 것은 정지 누운 영역으로만)
        else:
            self.back_at = None; self.dwell = 0

def chords(d):
    sh, hp, kn, an, ear = near_side(d[d.in_set])
    d = d.assign(bin=d.t_ms // GRID).groupby("bin").head(1)
    ch = []
    for r in d.itertuples(index=False):
        rr = r._asdict()
        if not np.isfinite(rr.get(f"x{sh}", np.nan)) or min(rr[f"v{sh}"], rr[f"v{hp}"], rr[f"v{an}"]) < .5: ch.append(np.nan); continue
        P = lambda i: np.array([rr[f"x{i}"] * rr["W"], rr[f"y{i}"] * rr["H"]])
        ch.append(selev(P(sh) - P(hp), P(hp) - P(an)))
    return d.assign(chord=ch)

if __name__ == "__main__":
    res = []
    for (w, k), d in df.groupby(["w", "set"]):
        d = chords(d.sort_values("t_ms"))
        s0, s1 = d.t_ms[d.in_set].min(), d.t_ms[d.in_set].max()
        row = dict(w=w, set=k, reps=int(d.reps.iloc[0]))
        for name, kw in {"B": {}, "B_cyc4": dict(max_cycle=4000), "B_lift8": dict(lift=8), "B_dep3": dict(depart=3, lift=5)}.items():
            fc = FloorCycle3(**kw)
            for r in d.itertuples(index=False): fc.on(r.t_ms, r.chord)
            ins = sum(1 for c in fc.cycles if s0 - 1500 <= c[1] <= s1 + 1500); outs = len(fc.cycles) - ins
            row[name] = f"{ins}/{outs}/{','.join(r for _, r in fc.rejected) or '-'}"; row[name + "_err"] = ins - row["reps"]; row[name + "_out"] = outs
        res.append(row)
    o = pd.DataFrame(res)
    print(o[["w", "set", "reps", "B", "B_cyc4", "B_lift8", "B_dep3"]].to_string(index=False))
    for n in ("B", "B_cyc4", "B_lift8", "B_dep3"):
        e = o[n + "_err"].to_numpy()
        print(n, "정확", int((e == 0).sum()), "±1", int((abs(e) <= 1).sum()), "/15 | 세트 밖 헛사이클", int(o[n + "_out"].sum()), "| 오차", e.tolist())
