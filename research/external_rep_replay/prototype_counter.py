# -*- coding: utf-8 -*-
"""설계 검증용 카운터 프로토타입 — MM-Fit 자체 3D 포즈에서 '복귀 히스테리시스' 카운터를 잰다.

목적: REP_ENGINE_DESIGN.md 의 카운터 코어 제안이 현재 라이브 엔진(ReturnRepTracker)보다 나은지,
같은 입력(MM-Fit pose_3d, 앱 간격 300ms)에서 세트 카운트와 **음성 구간(휴식·타 종목)** 을 함께 잰다.
파이썬 프로토타입이고 앱 코드가 아니다. 코어 상수(각도 게이트 35°·f 0.25·불응기 0.8 s)는 설계값이며 MM-Fit 결과로 조정하지 않았다.
평활 여부와 시작 확정 정책은 MM-Fit(개발 데이터)에서 비교해 골랐다(설계 §4.2·§12·§13) — 성능 약속이 아니다.

카운터 규약 (한 사이클 = 휴식 → 하강 → 반전 → 복귀):
  REST : 휴식 기준 r = 마지막 발화 이후의 최댓값. v ≤ r − h 이면 하강 시작(사이클 기준 r0 = r).
  DESC : 바닥 m 추적. v ≥ m + h 이면 반전 확정(후보).
  ASC  : v ≥ m + (1−f)(r0 − m) 이면 발화(복귀 f=25% 남은 지점).
         복귀 전에 v ≤ (반전 후 최댓값) − h 로 다시 내려가면 '다음 사이클이 시작됐다' 로 보고 발화(불완전 복귀 폴백).
  불응기: 직전 발화 후 refractory 안의 발화는 잡음으로 보고 버린다.
  평활 없음(선택 실험으로 3점 중앙값 on/off). 사전 안정 자세 요구 없음 — 첫 사이클의 r0 는 하강 직전까지의 최댓값.
교대 종목: 좌·우 신호에 카운터를 하나씩 두고, 반대쪽이 W ms 안에 발화했으면 같은 반복으로 합친다(동시 수행 = 1회).
종목 문맥 게이트(선택): 스쿼트 — 사이클 안에서 고관절각이 r 대비 ≥ 20° 내려가야 한다(걷기 배제).
                        컬 — 팔꿈치 바닥 시점의 어깨각 < 90°(머리 위 신전·프레스 배제).

극성(polarity): 코어는 "휴식 = 신호 최댓값" 을 가정한다(관절각은 반복 중 작아진다 = falling). 반복 중 커지는 신호(rising)를
  그대로 넣으면 휴식이 신호 최솟값 쪽에서 잡혀 세트의 마지막 반복을 잃는다(stress_battery.py 의 mirror 조건).
  그래서 극성은 신호마다 명시하는 opt-in 이고, 명시하지 않은 신호는 앱에서 기존 ReturnRepTracker 를 쓴다(설계 §4.2).
시작 확정 정책(confirm): 첫 발화는 보류하고 첫 쌍 창 안에 진폭이 비슷한 두 번째 사이클이 오면 둘을 함께 발표한다.
  POLICIES 에 설계 §4.3 후보들이 있다. 기본 consistency() 는 옛 4s/4s(§9 잡음 표의 재현용)다.

사용법: python prototype_counter.py ../../data/mm-fit/mm-fit --out ../../data/mm-fit/exp_proto
"""
from __future__ import annotations

import argparse
import csv
import json
from collections import defaultdict
from pathlib import Path

import numpy as np

FPS = 30.0
MARGIN = 15
ACTS = {"squats": "squat", "lunges": "lunge", "bicep_curls": "curl"}
# H36M: 1 rhip 2 rknee 3 rankle 4 lhip 5 lknee 6 lankle 11 lsho 12 lelb 13 lwri 14 rsho 15 relb 16 rwri (좌우 이름은 EDA 와 다를 수 있으나 대칭 사용)
J = dict(rhip=1, rknee=2, rank=3, lhip=4, lknee=5, lank=6, lsho=11, lelb=12, lwri=13, rsho=14, relb=15, rwri=16)


def angle(a, b, c):
    u = a - b; w = c - b
    cos = (u * w).sum(-1) / (np.linalg.norm(u, axis=-1) * np.linalg.norm(w, axis=-1) + 1e-9)
    return np.degrees(np.arccos(np.clip(cos, -1, 1)))


def features(pose):
    """pose: (3, N, 17) → dict of per-frame angle arrays (deg)."""
    P = np.transpose(pose, (1, 2, 0))  # (N,17,3)
    g = lambda k: P[:, J[k], :]
    f = {
        "knee_L": angle(g("lhip"), g("lknee"), g("lank")), "knee_R": angle(g("rhip"), g("rknee"), g("rank")),
        "elbow_L": angle(g("lsho"), g("lelb"), g("lwri")), "elbow_R": angle(g("rsho"), g("relb"), g("rwri")),
        "hip_L": angle(g("lsho"), g("lhip"), g("lknee")), "hip_R": angle(g("rsho"), g("rhip"), g("rknee")),
        "shoulder_L": angle(g("lelb"), g("lsho"), g("lhip")), "shoulder_R": angle(g("relb"), g("rsho"), g("rhip")),
    }
    f["knee_mean"] = (f["knee_L"] + f["knee_R"]) / 2
    f["elbow_mean"] = (f["elbow_L"] + f["elbow_R"]) / 2
    f["hip_mean"] = (f["hip_L"] + f["hip_R"]) / 2
    # 앱(PostureCore)과 같이 minside = 좌우 중 작은 쪽. 한쪽이 없으면 앱은 있는 쪽을 쓴다(가림 조건은 stress_battery.py 가 흉내 낸다).
    f["knee_minside"] = np.minimum(f["knee_L"], f["knee_R"])
    f["elbow_minside"] = np.minimum(f["elbow_L"], f["elbow_R"])
    return f


class Counter:
    def __init__(self, h, f=0.25, refractory_ms=800, smooth=False, context=None, min_cycle_ms=0, polarity="falling"):
        # min_cycle_ms: 하강 시작부터 발화까지의 최소 시간. 잡음 사이클은 2~3샘플(≤900ms)이고 실제 반복은 그보다 길다(설계 §9).
        # polarity: "falling" = 반복 중 값이 작아지는 신호(휴식 = 최댓값, 앱 RepPolarity.DOWN). "rising"(UP) 이면 부호를 뒤집어 같은 상태기계에 넣는다.
        if polarity not in ("falling", "rising"):
            raise ValueError(polarity)
        self.h, self.f, self.ref, self.smooth, self.context, self.min_cycle = h, f, refractory_ms, smooth, context, min_cycle_ms
        self.sign = 1.0 if polarity == "falling" else -1.0
        self.reset()

    def reset(self):
        self.state = "REST"; self.r = None; self.r0 = None; self.m = None; self.amax = None
        self.last_fire = -10**9; self.raw = []; self.fires = []; self.amps = []; self.durs = []; self.cycle_ctx = []; self.last_t = None

    def _value(self, v):
        self.raw.append(v)
        if self.smooth and len(self.raw) >= 3:
            return float(np.median(self.raw[-3:]))
        return v

    def on_frame(self, t_ms, v, ctx=None):
        """ctx: 문맥 게이트용 보조값(스쿼트 hip_mean, 컬 shoulder). 발화하면 True."""
        if v is None or not np.isfinite(v):
            return False
        v = self.sign * v
        # maxGap 1.5 s: 가림·끊김 전후를 한 반복으로 잇지 않는다(설계 §4.2 — 현재 엔진과 같은 값). 확정된 수는 지킨다.
        if self.last_t is not None and t_ms - self.last_t > 1500:
            self.state, self.r, self.raw = "REST", None, []
        self.last_t = t_ms
        v = self._value(v)
        if self.r is None:
            self.r = v
        if self.state == "REST":
            self.r = max(self.r, v)
            if ctx is not None: self.ctx_rest = ctx if not hasattr(self, "ctx_rest") else max(self.ctx_rest, ctx)
            if v <= self.r - self.h:
                self.state, self.r0, self.m, self.cycle_ctx, self.desc_t = "DESC", self.r, v, [ctx], t_ms
            return False
        if self.state == "DESC":
            self.cycle_ctx.append(ctx)
            if v < self.m:
                self.m = v
            elif v >= self.m + self.h:
                self.state, self.amax = "ASC", v
            return False
        # ASC
        self.cycle_ctx.append(ctx)
        fired = False
        if v >= self.m + (1 - self.f) * (self.r0 - self.m):
            fired = True
        elif v <= self.amax - self.h:            # 복귀 전 재하강 — 사이클 종료로 간주(불완전 복귀)
            fired = True
        else:
            self.amax = max(self.amax, v)
        if not fired:
            return False
        ok = t_ms - self.last_fire >= self.ref and t_ms - self.desc_t >= self.min_cycle
        if ok and self.context is not None:
            ok = self.context(self)
        if ok:
            self.fires.append(t_ms); self.amps.append(self.r0 - self.m); self.durs.append(t_ms - self.desc_t); self.last_fire = t_ms
        # 다음 사이클 준비: 재하강 폴백이면 이미 내려가는 중이므로 DESC 로, 아니면 REST 로
        if v <= self.amax - self.h:
            self.state, self.r0, self.m, self.cycle_ctx, self.desc_t = "DESC", self.amax, v, [ctx], t_ms
            self.r = self.amax
        else:
            self.state, self.r = "REST", v
        return ok


def squat_context(c: Counter):
    vals = [x for x in c.cycle_ctx if x is not None]
    rest = getattr(c, "ctx_rest", None)
    return bool(vals) and rest is not None and (rest - min(vals)) >= 20.0


def curl_context(c: Counter):
    vals = [x for x in c.cycle_ctx if x is not None]
    return bool(vals) and min(vals) < 90.0


# 설계 §4.3 의 시작 확정 후보. first_ms = 첫 쌍 창, later_ms = 이후 반복의 시간 창(None = 진폭 비만),
# tempo_k = 첫 쌍 창을 max(first_ms, tempo_k × 보류 사이클 길이) 로 늘린다. "none" 은 확정 없이 모든 발화를 센다.
POLICIES = {
    "none": None,
    "4s/4s": dict(first_ms=4000, later_ms=4000),
    "first8+amp": dict(first_ms=8000, later_ms=None),
    "first10+amp": dict(first_ms=10000, later_ms=None),
    "tempo": dict(first_ms=4000, later_ms=None, tempo_k=2.5),
    # 사후 탐색(설계 §12 선택 후보 아님): 이후 반복에도 8 s 창을 두어 세트 뒤 헛카운트를 얼마나 줄이는지 본다
    "first8/later8": dict(first_ms=8000, later_ms=8000),
    # 사후 탐색: 첫 쌍 창 = max(8 s, 2.5 × 첫 사이클 길이) — 반복당 8 s 를 넘는 느린 세트에서 첫 쌍이 영영 안 맺히는 절벽을 보려고
    "first8|tempo": dict(first_ms=8000, later_ms=None, tempo_k=2.5),
}


def confirm(fires, amps, durs=None, first_ms=4000, later_ms=4000, ratio=(0.5, 2.0), tempo_k=None, drop_unpaired=True):
    """세트 시작 확정. 반환: (발표된 발화 시각 리스트, 끝까지 보류된 후보 [(t, amp)] — 세지 않고 로그에 남길 것).

    - 첫 발화는 보류한다. 첫 쌍 창 안에 진폭 비가 ratio 안인 두 번째 사이클이 오면 둘을 함께 발표한다.
    - 확정 뒤의 발화는 직전 발표 사이클과 진폭 비가 ratio 안이어야 한다(later_ms 가 있으면 시간 창도).
      어긋나면 새 보류 후보가 되고, 다음 사이클과 쌍을 이루면 둘 다 발표된다(재확정).
    - drop_unpaired=True(앱 `RepStartConfirmation` 과 같다): 보류 후보는 **바로 다음 사이클** 하나로만 해소된다 —
      짝이 맞으면 함께 발표, 아니면 버린다. 그래서 발표 순서가 항상 발화 순서다.
      False(옛 프로토타입, §5·§9 표): 짝이 안 맞아도 다음 사이클이 직전 발표와 맞으면 보류를 남겨 두어, 더 뒤의 사이클과
      짝지어 순서를 거슬러 발표할 수 있었다.
    - 세트가 끝날 때 보류 중인 후보는 세지 않는다 — 한 번의 고립된 움직임은 반복이 아니다.
    """
    out, pend, last = [], None, None      # pend = (t, amp, window_ms)
    for i, (t, a) in enumerate(zip(fires, amps)):
        if pend is not None and t - pend[0] <= pend[2] and ratio[0] <= a / max(pend[1], 1e-6) <= ratio[1]:
            out += [pend[0], t]; pend = None; last = (t, a); continue
        if drop_unpaired:
            pend = None
        if out and (later_ms is None or t - out[-1] <= later_ms) and ratio[0] <= a / max(last[1], 1e-6) <= ratio[1]:
            out.append(t); last = (t, a); continue
        win = first_ms if tempo_k is None or durs is None else max(first_ms, tempo_k * durs[i])
        pend = (t, a, win)
    return out, ([(pend[0], pend[1])] if pend is not None else [])


def apply_policy(name, fires, amps, durs=None):
    """정책 이름으로 확정을 적용한다. 반환 (발표 시각, 보류 후보)."""
    cfg = POLICIES[name]
    if cfg is None:
        return list(fires), []
    return confirm(fires, amps, durs, **cfg)


def consistency(fires, amps, window_ms=4000, ratio=(0.5, 2.0)):
    """옛 §4.3 (4s/4s) — §5·§9 표의 재현용. 새 정책은 apply_policy() 를 쓴다."""
    return confirm(fires, amps, None, first_ms=window_ms, later_ms=window_ms, ratio=ratio, drop_unpaired=False)[0]


def run_counter(kind, F, idx, cadence, smooth, gated, merge_ms=500, consist=False):
    """idx: 프레임 인덱스 배열(시간순). 반환: 발화 시각(ms) 리스트."""
    t = (idx * 1000.0 / FPS).astype(int)
    if kind in ("squat", "lunge"):
        c = Counter(35.0, smooth=smooth, context=squat_context if (gated and kind == "squat") else None)
        for k in range(len(idx)):
            c.on_frame(t[k], F["knee_mean"][idx[k]], F["hip_mean"][idx[k]])
        return consistency(c.fires, c.amps) if consist else c.fires
    # curl: per-arm + merge
    cs = {s: Counter(35.0, smooth=smooth, context=curl_context if gated else None) for s in "LR"}
    fires, amps, last = [], [], {"L": -10**9, "R": -10**9}
    for k in range(len(idx)):
        for s in "LR":
            if cs[s].on_frame(t[k], F[f"elbow_{s}"][idx[k]], F[f"shoulder_{s}"][idx[k]]):
                other = "R" if s == "L" else "L"
                if t[k] - last[other] > merge_ms:
                    fires.append(t[k]); amps.append(cs[s].amps[-1])
                last[s] = t[k]
    return consistency(fires, amps) if consist else fires


def load_labels(p: Path):
    rows = []
    for line in csv.reader(p.open(newline="")):
        if line:
            rows.append((int(line[0]), int(line[1]), int(line[2]), line[3].strip()))
    return sorted(rows)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("root", type=Path); ap.add_argument("--out", type=Path, required=True)
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    variants = {
        "hys300": dict(step=9, smooth=False, gated=False),
        "hys300+smooth": dict(step=9, smooth=True, gated=False),
        "hys300+gate": dict(step=9, smooth=False, gated=True),
        "hys200": dict(step=6, smooth=False, gated=False),
        "hys300+consist": dict(step=9, smooth=False, gated=False, consist=True),
        "hys300+gate+consist": dict(step=9, smooth=False, gated=True, consist=True),
    }
    LEAD_S = 10.0   # 세트 시작 직전 10초 — 앱에서 '시작' 을 누른 뒤 첫 반복 전의 준비 움직임에 해당하는 음성 구간
    sets = defaultdict(lambda: defaultdict(list))     # variant -> kind -> [(truth, count, tempo)]
    rest = defaultdict(lambda: defaultdict(lambda: [0, 0.0]))  # variant -> kind -> [fires, minutes]
    cross = defaultdict(lambda: defaultdict(lambda: [0, 0.0]))  # variant -> (kind|activity) -> [fires, minutes]
    lead = defaultdict(lambda: defaultdict(lambda: [0, 0, 0.0]))   # variant -> kind -> [fires, windows, minutes]
    burst = defaultdict(lambda: defaultdict(lambda: [0, 0]))       # variant -> kind -> [fires with a neighbour within 4s, fires]
    for wdir in sorted(p for p in args.root.iterdir() if p.is_dir()):
        w = wdir.name
        pose_p, lab_p = wdir / f"{w}_pose_3d.npy", wdir / f"{w}_labels.csv"
        if not pose_p.is_file() or not lab_p.is_file():
            continue
        pose = np.load(pose_p)
        frame_no = pose[0, :, 0].astype(int)
        F = features(pose[:, :, 1:])
        row_of = {f: i for i, f in enumerate(frame_no)}
        labels = load_labels(lab_p)
        claimed = set()
        for s, e, _, _ in labels:
            claimed.update(range(s - MARGIN, e + MARGIN + 1))
        for vname, cfg in variants.items():
            for s, e, reps, act in labels:
                fr = [f for f in range(s - MARGIN, e + MARGIN + 1) if f in row_of]
                idx = np.array([row_of[f] for f in fr])[::cfg["step"]]
                tempo = (e - s) / FPS / reps
                for kind in ("squat", "lunge", "curl"):
                    fires = run_counter(kind, F, idx, cfg["step"], cfg["smooth"], cfg["gated"], consist=cfg.get("consist", False))
                    if ACTS.get(act) == kind:
                        sets[vname][kind].append((reps, len(fires), tempo, w))
                    else:
                        cross[vname][f"{kind}|{act}"][0] += len(fires)
                        cross[vname][f"{kind}|{act}"][1] += len(fr) / FPS / 60
            # lead-in: the 10 s before each set of this exercise, ending at the set margin (fresh counter)
            for s, e, reps, act in labels:
                kind = ACTS.get(act)
                if kind is None:
                    continue
                fr = [f for f in range(s - MARGIN - int(LEAD_S * FPS), s - MARGIN) if f in row_of and f not in claimed]
                if len(fr) < FPS * 3:
                    continue
                idx = np.array([row_of[f] for f in fr])[::cfg["step"]]
                fires = run_counter(kind, F, idx, cfg["step"], cfg["smooth"], cfg["gated"], consist=cfg.get("consist", False))
                lead[vname][kind][0] += len(fires); lead[vname][kind][1] += 1; lead[vname][kind][2] += len(fr) / FPS / 60
            # rest gaps: contiguous unclaimed frames, fresh counter per gap
            gaps, cur, prev = [], [], None
            for i, f in enumerate(frame_no):
                if f in claimed:
                    continue
                if prev is not None and f != prev + 1 and cur:
                    gaps.append(cur); cur = []
                cur.append(i); prev = f
            if cur:
                gaps.append(cur)
            for gap in gaps:
                idx = np.array(gap)[::cfg["step"]]
                for kind in ("squat", "lunge", "curl"):
                    fires = run_counter(kind, F, idx, cfg["step"], cfg["smooth"], cfg["gated"], consist=cfg.get("consist", False))
                    rest[vname][kind][0] += len(fires)
                    ts = sorted(fires); burst[vname][kind][0] += sum(1 for i, x in enumerate(ts) if (i > 0 and x - ts[i-1] <= 4000) or (i + 1 < len(ts) and ts[i+1] - x <= 4000)); burst[vname][kind][1] += len(ts)
                    rest[vname][kind][1] += len(gap) / FPS / 60
    out = {}
    print("| 변형 | 종목 | 세트 | 정답 | 검출 | 재현율 | 정확 | ±1 | 과다 세트 | 0회 세트 | 빠른(<1.8s) 재현율 | 휴식 헛카운트/분 (군집 비율) | 세트 직전 10s 헛카운트/창 |")
    print("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    for vname in variants:
        out[vname] = {}
        for kind in ("squat", "lunge", "curl"):
            rows = sets[vname][kind]
            T = sum(r[0] for r in rows); C = sum(r[1] for r in rows)
            hit = sum(min(r[0], r[1]) for r in rows)
            exact = sum(r[0] == r[1] for r in rows) / len(rows)
            obo = sum(abs(r[0] - r[1]) <= 1 for r in rows) / len(rows)
            over = sum(r[1] > r[0] for r in rows); zero = sum(r[1] == 0 for r in rows)
            fast = [r for r in rows if r[2] < 1.8]
            fast_rec = sum(min(r[0], r[1]) for r in fast) / max(1, sum(r[0] for r in fast))
            rf, rm = rest[vname][kind]
            lf, lw, lm = lead[vname][kind]
            bf, bt = burst[vname][kind]
            m = dict(leadFires=lf, leadWindows=lw, leadPerWindow=lf / max(1, lw), restBurstFraction=bf / max(1, bt),sets=len(rows), truth=T, detected=C, recall=hit / T, precision=hit / max(1, C), exact=exact, obo=obo,
                     overSets=over, zeroSets=zero, fastSets=len(fast), fastRecall=fast_rec, restFiresPerMin=rf / rm, restMinutes=rm,
                     cross={k: dict(fires=v[0], minutes=v[1], perMin=v[0] / v[1]) for k, v in cross[vname].items() if k.startswith(kind + "|")},
                     perSet=[dict(w=r[3], truth=r[0], detected=r[1], tempo=r[2]) for r in rows])
            out[vname][kind] = m
            print(f"| {vname} | {kind} | {len(rows)} | {T} | {C} | {m['recall']:.3f} | {exact:.2f} | {obo:.2f} | {over} | {zero} | {fast_rec:.2f} ({len(fast)}) | {m['restFiresPerMin']:.2f} ({m['restBurstFraction']:.2f}) | {m['leadPerWindow']:.2f} ({lw}) |")
    (args.out / "proto_summary.json").write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")
    print("\n타 종목 세트에서의 헛카운트/분 (hys300 / hys300+gate):")
    for kind in ("squat", "lunge", "curl"):
        for k, v in sorted(out["hys300"][kind]["cross"].items(), key=lambda kv: -kv[1]["perMin"])[:4]:
            g = out["hys300+gate"][kind]["cross"][k]
            print(f"  {k}: {v['perMin']:.1f} → {g['perMin']:.1f} /분")


if __name__ == "__main__":
    main()
