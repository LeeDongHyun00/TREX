# -*- coding: utf-8 -*-
"""B2 분석 — 덤벨 컬 가동 범위 분포 · ROM 게이트 시뮬레이션 · 반동/벌어짐 후보의 정상 분포 · 폰 2세트 기술.

입력  outputs/B2/cap30/*.npz(16세트, 30 fps + 진짜 300/333 ms), data/mmfit_mp_captures/*.cap(59세트, 300 ms),
      data/mm-fit/mm-fit/wNN/*_pose_3d.npy(반복 시각 기준, 영상 없는 세트), 폰 로그 2세트.
출력  outputs/B2/reps_*.csv(반복별), sets_*.csv(세트별), rom_grid_*.csv(게이트 격자), cand_*.csv(후보 분포), tables.md, summary.json
사용  python B2_analyze.py
"""
from __future__ import annotations

import json
from collections import Counter, defaultdict

import numpy as np

import B2_lib as L

OUT = L.OUT
FPS = L.FPS
MARGIN_MS = 500
PRE_MS = 6000       # npz 캡처의 세트 앞 여유(B2_extract30 MMFIT_PRE_S)
X_GRID = [50, 60, 70, 80, 90, 100, 110]
Y_GRID = [120, 130, 140, 150, 160, 170]
RATIOS = [0.5, 0.6, 0.7, 0.8]
BANDS_N = {"upperarm_vert": [20, 30, 40, 50], "shoulder_h": [0.03, 0.05, 0.08], "sh_h_img": [0.03, 0.05, 0.08]}  # 이완 − 창 최소(n_*)
BANDS = {  # 후보별 띠 후보(창 최대 − 이완, x_*) — 값은 물리 단위
    "upperarm_vert": [10, 15, 20, 25, 30], "elbow_torso": [0.05, 0.08, 0.10, 0.15, 0.20], "elbow_h": [0.05, 0.08, 0.10, 0.15],
    "shoulder_h": [0.03, 0.05, 0.08, 0.10], "sh_h_img": [0.03, 0.05, 0.08, 0.10], "torso_incl": [5, 8, 10, 15, 20],
    "elbow_gap_w": [0.15, 0.25, 0.35, 0.50], "elbow_gap_img": [0.10, 0.15, 0.25, 0.35], "sh_y_img_n": [0.03, 0.05, 0.08, 0.10],
}
CANDS = [c for c in L.CAND_SIDE + L.CAND_COMMON if c not in ("forearm_vert", "hip_y_img_n")]
REST_KEYS = [f"{k}_{s}" for k in L.CAND_SIDE for s in L.SIDES] + L.CAND_COMMON


def fmt(v, nd=1):
    if v is None or (isinstance(v, float) and not np.isfinite(v)):
        return "—"
    return f"{v:.{nd}f}"


# ----------------------------------------------------------------------------------------------- 세트 처리
def segment(V, t, lo, hi, key, k=5):
    rL = L.find_arm_reps(V["elbow_L"], t, lo, hi, k=k)
    rR = L.find_arm_reps(V["elbow_R"], t, lo, hi, k=k)
    return L.pair_reps(rL, rR, t, V, key)


def phase_lag(V, t, lo, hi):
    """좌우 팔꿈치 각의 위상차(주기 비율, 0 = 동시, 0.5 = 교대). 상관 최대 lag ÷ 주기."""
    m = (t >= lo) & (t <= hi)
    a, b = L.smooth(V["elbow_L"], 5)[m], L.smooth(V["elbow_R"], 5)[m]
    if len(a) < 20:
        return np.nan, np.nan
    a, b = a - a.mean(), b - b.mean()
    # 주기: 자기상관 첫 봉우리
    ac = np.correlate(a, a, "full")[len(a) - 1:]
    ac = ac / max(ac[0], 1e-9)
    per = None
    for i in range(3, len(ac) - 1):
        if ac[i] > ac[i - 1] and ac[i] >= ac[i + 1] and ac[i] > 0.2:
            per = i
            break
    if per is None:
        return np.nan, np.nan
    best, bl = -2, 0
    for lag in range(-per, per + 1):
        if lag >= 0:
            x, y = a[lag:], b[:len(b) - lag]
        else:
            x, y = a[:lag], b[-lag:]
        if len(x) < 10:
            continue
        r = float(np.corrcoef(x, y)[0, 1])
        if r > best:
            best, bl = r, lag
    dt = float(np.median(np.diff(t[m])))
    return abs(bl) / per, per * dt / 1000.0


def process_video_sets(index_by_key):
    """16세트: 30 fps 기준 분할 → f30·c300·c333·cap300 반복별 값."""
    rep_rows, set_rows, rest_rows = [], [], []
    jobs = json.loads((L.CAP30 / "jobs.json").read_text(encoding="utf-8"))
    for job in sorted(jobs, key=lambda j: j["name"]):
        meta, st = L.load_npz(job["name"])
        w = meta["workout"]
        idx = index_by_key[meta["capKey"]]
        t30 = st["f30"].t_ms
        V30 = L.arm_vars(st["f30"])
        lo, hi = meta["labelStart"] * 1000.0 / FPS - MARGIN_MS, meta["labelEnd"] * 1000.0 / FPS + MARGIN_MS
        reps, mode = segment(V30, t30, lo, hi, job["name"])
        pl, per = phase_lag(V30, t30, lo, hi)
        # pose_3d 기준과 대조(같은 세트)
        f0, e3 = L.pose3d_elbows(w)
        t3 = np.round((f0 + np.arange(len(e3["elbow_L"]))) * 1000.0 / FPS).astype(np.int64)
        lo3, hi3 = meta["labelStartRaw"] * 1000.0 / FPS - MARGIN_MS, meta["labelEndRaw"] * 1000.0 / FPS + MARGIN_MS
        reps3, mode3 = segment(e3, t3, lo3, hi3, job["name"] + "@3d")
        shift = meta["lagFrames"] * 1000.0 / FPS
        dts = []
        for r in reps:
            c = [abs(r3.t_min + shift - r.t_min) for r3 in reps3]
            if c:
                dts.append(min(c))
        streams = {"f30": (V30, t30), "c300": (L.arm_vars(st["c300"]), st["c300"].t_ms), "c333": (L.arm_vars(st["c333"]), st["c333"].t_ms)}
        cap = L.load_cap(L.CAP300_DIR / meta["capKey"])
        streams["cap300"] = (L.arm_vars(cap), cap.t_ms)
        vis = {s: float(np.mean(V30[f"vis_{s}"][(t30 >= lo) & (t30 <= hi)])) for s in L.SIDES}
        set_rows.append({"set": job["name"], "workout": w, "subject": L.subject_of(w), "truth": meta["truthReps"], "found": len(reps),
                         "mode": mode, "n_pair": sum(1 for r in reps if r.arm == "LR"), "phase_lag": pl, "period_s": per,
                         "found_3d": len(reps3), "mode_3d": mode3, "dt_3d_med_ms": float(np.median(dts)) if dts else np.nan,
                         "dt_3d_max_ms": float(np.max(dts)) if dts else np.nan, "vis_L": vis["L"], "vis_R": vis["R"],
                         "lag_frames": meta["lagFrames"], "lag_how": meta["lagHow"], "set_s": (hi - lo - 2 * MARGIN_MS) / 1000.0})
        for i, r in enumerate(reps):
            # 같은 반복 창의 데이터셋 3D 각(라벨 프레임 = 영상 프레임 − lag)
            fa = int(round((r.t_top_b) * FPS / 1000.0)) - meta["lagFrames"] - f0
            fb = int(round((r.t_top_a) * FPS / 1000.0)) - meta["lagFrames"] - f0
            arm3 = r.arm if r.arm != "LR" else "L"
            seg3 = e3[f"elbow_{arm3}"][max(0, fa):max(0, fb) + 1]
            fm = int(round(r.t_min * FPS / 1000.0)) - meta["lagFrames"] - f0
            seg3b = e3[f"elbow_{arm3}"][max(0, fa):max(0, fm) + 1]
            ref3_min = float(np.nanmin(seg3)) if np.isfinite(seg3).any() else np.nan
            ref3_top = float(np.nanmax(seg3b)) if np.isfinite(seg3b).any() else np.nan
            base = {"set": job["name"], "workout": w, "subject": L.subject_of(w), "rep": i + 1, "arm": r.arm, "mode": mode,
                    "t_min": r.t_min, "ref_min": r.ref_min, "ref_top_b": r.ref_top_b, "ref_top_a": r.ref_top_a,
                    "ref3d_min": ref3_min, "ref3d_top_b": ref3_top, "ref3d_amp_b": ref3_top - ref3_min,
                    "dur_s": (r.t_d1 - r.t_d0) / 1000.0, "contract_s": (r.t_c1 - r.t_c0) / 1000.0, "cycle_s": (r.t_top_a - r.t_top_b) / 1000.0}
            for sname, (V, t) in streams.items():
                row = dict(base)
                row["stream"] = sname
                row.update(L.rep_values(V, t, r))
                rep_rows.append(row)
        r0 = min((r.t_d0 for r in reps), default=int(lo))
        for sname, (V, t) in streams.items():
            rj = L.rest_jitter(V, t, int(r0 - 3300), int(r0 - 300), REST_KEYS)
            for k, v in rj.items():
                rest_rows.append({"set": job["name"], "stream": sname, "feature": k, **v})
    return rep_rows, set_rows, rest_rows


def process_cap_sets(index):
    """59세트: pose_3d(라벨 시간축) 기준 분할 → cap300(영상 시각) 반복별 값. 좌우 대응은 세트마다 상관으로 확인."""
    rep_rows, set_rows, rest_rows = [], [], []
    for s in index:
        w = s["workout"]
        cap = L.load_cap(L.CAP300_DIR / s["capture"])
        Vc, tc = L.arm_vars(cap), cap.t_ms
        f0, e3 = L.pose3d_elbows(w)
        t3 = np.round((f0 + np.arange(len(e3["elbow_L"]))) * 1000.0 / FPS).astype(np.int64)
        shift = s["shiftMs"]                                                      # 영상 시각 = 라벨 시각 + shift
        lo3, hi3 = s["startMs"] - MARGIN_MS, s["endMs"] + MARGIN_MS                 # index 의 startMs/endMs 는 라벨 시각
        # 좌우 확인: cap 표본(영상 시각)의 라벨 프레임에서 3D 각을 뽑아 상관
        m = (tc >= s["startMs"] + shift - MARGIN_MS) & (tc <= s["endMs"] + shift + MARGIN_MS)
        fl = np.round(tc[m] * FPS / 1000.0).astype(int) - s["lagFrames"] - f0
        okf = (fl >= 0) & (fl < len(e3["elbow_L"]))
        rr = {}
        for a in L.SIDES:
            for b in L.SIDES:
                x, y = Vc[f"elbow_{a}"][m][okf], e3[f"elbow_{b}"][fl[okf]]
                g = np.isfinite(x) & np.isfinite(y)
                rr[a + b] = float(np.corrcoef(x[g], y[g])[0, 1]) if g.sum() >= 10 else np.nan
        same = np.nanmean([rr["LL"], rr["RR"]])
        cross = np.nanmean([rr["LR"], rr["RL"]])
        swapped = bool(cross > same)
        e3u = {"elbow_L": e3["elbow_R"], "elbow_R": e3["elbow_L"]} if swapped else e3
        reps, mode = segment(e3u, t3, lo3, hi3, s["capture"])
        pl, per = phase_lag(e3u, t3, lo3, hi3)
        vis = {a: float(np.mean(Vc[f"vis_{a}"][m])) for a in L.SIDES}
        set_rows.append({"set": s["capture"], "workout": w, "subject": s["subject"], "truth": s["truthReps"], "found": len(reps), "mode": mode,
                         "n_pair": sum(1 for r in reps if r.arm == "LR"), "phase_lag": pl, "period_s": per, "r_same": same, "r_cross": cross,
                         "swapped": swapped, "vis_L": vis["L"], "vis_R": vis["R"], "lag_frames": s["lagFrames"], "lag_how": s["lagHow"],
                         "set_s": (s["endMs"] - s["startMs"]) / 1000.0, "n_cap_in_set": int(m.sum())})
        for i, r in enumerate(reps):
            row = {"set": s["capture"], "workout": w, "subject": s["subject"], "rep": i + 1, "arm": r.arm, "mode": mode, "stream": "cap300",
                   "t_min": r.t_min + shift, "ref3d_min": r.ref_min, "ref3d_top_b": r.ref_top_b, "ref3d_amp_b": r.ref_top_b - r.ref_min,
                   "dur_s": (r.t_d1 - r.t_d0) / 1000.0,
                   "contract_s": (r.t_c1 - r.t_c0) / 1000.0, "cycle_s": (r.t_top_a - r.t_top_b) / 1000.0}
            row.update(L.rep_values(Vc, tc, r, shift_ms=shift))
            rep_rows.append(row)
        r0 = min((r.t_d0 + shift for r in reps), default=int(s["startMs"] + shift))
        rj = L.rest_jitter(Vc, tc, int(r0 - 3300), int(r0 - 300), REST_KEYS)
        for k, v in rj.items():
            rest_rows.append({"set": s["capture"], "stream": "cap300", "feature": k, **v})
    return rep_rows, set_rows, rest_rows


# ----------------------------------------------------------------------------------------------- ROM 게이트
def rom_grid(rep_rows, set_rows, stream, ext_key="ext_b"):
    """반복별 (min, ext) 로 절대 격자·개인 비율 격자의 재현율과 세트 정확 일치."""
    truth = {r["set"]: r["truth"] for r in set_rows}
    by_set = defaultdict(list)
    for r in rep_rows:
        if r["stream"] == stream and np.isfinite(r.get("min", np.nan)):
            by_set[r["set"]].append(r)
    for k in by_set:
        by_set[k].sort(key=lambda r: r["t_min"])
    n_reps = sum(len(v) for v in by_set.values())
    n_sets = len(truth)
    rows = []

    seg_ok = {sk for sk, reps in by_set.items() if len(reps) == truth[sk]}

    def tally(name, passfn, extra=None):
        kept, exact, exact_ok = 0, 0, 0
        per_subject = defaultdict(lambda: [0, 0])
        for sk, reps in by_set.items():
            k = sum(1 for r in reps if passfn(r, reps))
            kept += k
            if k == truth[sk]:
                exact += 1
                if sk in seg_ok:
                    exact_ok += 1
            subj = reps[0]["subject"]
            per_subject[subj][0] += k
            per_subject[subj][1] += len(reps)
        row = {"gate": name, "recall": kept / max(n_reps, 1), "set_exact": exact / max(n_sets, 1), "set_exact_segok": exact_ok / max(len(seg_ok), 1),
               "kept": kept, "n_reps": n_reps, "n_sets": n_sets, "n_segok": len(seg_ok)}
        for subj, (a, b) in sorted(per_subject.items()):
            row[f"recall_{subj}"] = a / max(b, 1)
        if extra:
            row.update(extra)
        rows.append(row)

    tally("none(분할만)", lambda r, reps: True)
    for X in X_GRID:
        for Y in Y_GRID:
            tally(f"abs X≤{X} Y≥{Y}", lambda r, reps, X=X, Y=Y: r["min"] <= X and r[ext_key] >= Y, {"X": X, "Y": Y, "kind": "abs"})
    for X in X_GRID:
        tally(f"abs X≤{X} only", lambda r, reps, X=X: r["min"] <= X, {"X": X, "kind": "absX"})
    for Y in Y_GRID:
        tally(f"abs Y≥{Y} only", lambda r, reps, Y=Y: r[ext_key] >= Y, {"Y": Y, "kind": "absY"})
    for nref in (2, 3):
        for ratio in RATIOS:
            def pf(r, reps, ratio=ratio, nref=nref):
                ref = float(np.nanmedian([q[f"amp_{ext_key[-1]}"] for q in reps[:nref]]))
                return r[f"amp_{ext_key[-1]}"] >= ratio * ref
            tally(f"rel amp≥{ratio}×첫{nref}회", pf, {"ratio": ratio, "nref": nref, "kind": "rel_amp"})
    # 같은 팔의 첫 2회 진폭 대비 — 교대 컬에서 팔마다 진폭이 다른 사람(w19: 한 팔 ~85°, 다른 팔 ~50°)을 두 팔 섞은 기준이 깎지 않게
    for ratio in RATIOS:
        def pfa(r, reps, ratio=ratio):
            same = [q for q in reps if q["arm"] == r["arm"]][:2]
            ref = float(np.nanmedian([q[f"amp_{ext_key[-1]}"] for q in same])) if same else np.nan
            return np.isfinite(ref) and r[f"amp_{ext_key[-1]}"] >= ratio * ref
        tally(f"rel amp≥{ratio}×같은 팔 첫2회", pfa, {"ratio": ratio, "kind": "rel_arm"})
    for ratio in RATIOS:
        def pf2(r, reps, ratio=ratio):
            # 시작 자세 = 첫 반복 하강 직전 이완각(스트림 자체) — 깊이 = 시작 − 최소, 기준 깊이 = 첫 3회 중앙값, 복귀 = 시작 − 20° 이상
            start = reps[0][ext_key]
            ref = float(np.nanmedian([start - q["min"] for q in reps[:3]]))
            return (start - r["min"]) >= ratio * ref and r["ext_a"] >= start - 20
        tally(f"rel 시작자세 깊이≥{ratio}×첫3회 & 복귀", pf2, {"ratio": ratio, "kind": "rel_start"})
    return rows


# ----------------------------------------------------------------------------------------------- 후보 분포
def cand_stats(rep_rows, rest_rows, stream):
    reps = [r for r in rep_rows if r["stream"] == stream and np.isfinite(r.get("min", np.nan))]
    rows = []
    for c in CANDS:
        for pre in ("d", "r", "x", "n"):
            k = f"{pre}_{c}"
            vals = np.array([r.get(k, np.nan) for r in reps], float)
            p = L.pct(vals, (5, 50, 95, 99))
            row = {"cand": c, "stat": pre, "stream": stream, "n": int(np.isfinite(vals).sum()), "p5": p[0], "p50": p[1], "p95": p[2], "p99": p[3]}
            if pre == "x" and c in BANDS:
                for T in BANDS[c]:
                    row[f"fp>{T}"] = float(np.mean(vals[np.isfinite(vals)] > T)) if np.isfinite(vals).any() else np.nan
            if pre == "n" and c in BANDS_N:
                for T in BANDS_N[c]:
                    row[f"fp>{T}"] = float(np.mean(vals[np.isfinite(vals)] > T)) if np.isfinite(vals).any() else np.nan
            rows.append(row)
    # 서 있음 잡음
    rest = defaultdict(list)
    for rr in rest_rows:
        if rr["stream"] == stream:
            base = rr["feature"].rsplit("_", 1)[0] if rr["feature"].rsplit("_", 1)[-1] in ("L", "R") else rr["feature"]
            rest[base].append(rr)
    rest_out = []
    for c in CANDS:
        rs = rest[c]
        sig = np.array([x["sigma"] for x in rs], float)
        rg = np.array([x["range2s_p95"] for x in rs], float)
        rest_out.append({"cand": c, "stream": stream, "n_sets": len(rs), "sigma_med": float(np.nanmedian(sig)) if np.isfinite(sig).any() else np.nan,
                         "range2s_p95_med": float(np.nanmedian(rg)) if np.isfinite(rg).any() else np.nan,
                         "range2s_p95_max": float(np.nanmax(rg)) if np.isfinite(rg).any() else np.nan})
    return rows, rest_out


# ----------------------------------------------------------------------------------------------- 폰 세트
def process_phone():
    out = []
    for d in L.load_phone_sets():
        t, V = L.phone_vars(d["frames"])
        anchor = d.get("anchor_t_ms", 0) or 0
        lo, hi = int(t[0]), int(t[-1])
        reps, mode = segment(V, t, lo, hi, d["set_id"][-8:], k=1)
        pl, per = phase_lag(V, t, lo, hi)
        rows = []
        for i, r in enumerate(reps):
            row = {"set": d["set_id"][-8:], "rep": i + 1, "arm": r.arm, "t_min": r.t_min, "dur_s": (r.t_d1 - r.t_d0) / 1000.0,
                   "contract_s": (r.t_c1 - r.t_c0) / 1000.0, "stream": "phone300"}
            row.update(L.rep_values(V, t, r))
            rows.append(row)
        app = d["reps"]
        r0 = min((r.t_d0 for r in reps), default=lo)
        rj = L.rest_jitter(V, t, int(r0 - 3300), int(r0 - 300), REST_KEYS)
        rest_note = "첫 반복 전 3 s"
        if not rj or all(v["n_rest"] < 3 for v in rj.values()):
            rj = L.rest_jitter(V, t, lo, hi, REST_KEYS, rest_angle=130.0)
            rest_note = "세트 안 이완 표본(양 팔꿈치 ≥ 130°)"
        # 게이트·띠 적용(MM-Fit 정상 p95 부근 띠)
        gate_abs = [r for r in rows if np.isfinite(r.get("min", np.nan)) and r["min"] <= 90 and r.get("ext_b", 0) >= 140]
        ref = float(np.nanmedian([r["amp_b"] for r in rows[:3]])) if len(rows) >= 1 else np.nan
        gate_rel = [r for r in rows if np.isfinite(r.get("amp_b", np.nan)) and r["amp_b"] >= 0.7 * ref]
        flags = {}
        for k, T_ in (("n_upperarm_vert", 30), ("x_torso_incl", 10), ("r_torso_incl", 15), ("x_elbow_torso", 0.24), ("x_elbow_h", 0.22), ("x_shoulder_h", 0.05), ("x_sh_h_img", 0.035)):
            flags[f"{k}>{T_}"] = [r["rep"] for r in rows if np.isfinite(r.get(k, np.nan)) and r[k] > T_]
        out.append({"set": d["set_id"], "exercise": d["exercise"], "mode_app": d["mode"], "tilt": d["tilt_deg"], "view": d.get("view"),
                    "anchor_ms": anchor, "n_frames": len(d["frames"]), "n_feat": int(len(t)), "t_end": hi, "found": len(reps), "mode": mode,
                    "phase_lag": pl, "period_s": per, "app_count": app["count"], "app_t": app["t_ms"], "app_min": app["min"], "app_max": app["max"],
                    "app_valid": app["valid"], "reps": rows, "rest": rj, "rest_note": rest_note, "results": d["results"],
                    "gate_abs_90_140": [r["rep"] for r in gate_abs], "gate_rel_0.7": [r["rep"] for r in gate_rel], "flags": flags,
                    "vis_L": float(np.mean(V["vis_L"])), "vis_R": float(np.mean(V["vis_R"]))})
    return out


# ----------------------------------------------------------------------------------------------- 표
def md_table(headers, rows):
    out = ["| " + " | ".join(headers) + " |", "|" + "|".join("---" for _ in headers) + "|"]
    for r in rows:
        out.append("| " + " | ".join(str(x) for x in r) + " |")
    return "\n".join(out)


def dist_table(rep_rows, streams, key, by="subject"):
    rows = []
    for st in streams:
        reps = [r for r in rep_rows if r["stream"] == st and np.isfinite(r.get(key, np.nan))]
        groups = defaultdict(list)
        for r in reps:
            groups["전체"].append(r[key])
            groups[r[by]].append(r[key])
        for g, vals in groups.items():
            if g != "전체" and len(vals) < 8:
                continue
            p = L.pct(vals)
            rows.append([st, g, len(vals), fmt(p[0]), fmt(p[1]), fmt(p[2])])
    return rows


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    index = L.load_index()
    index_by_key = {s["capture"]: s for s in index}
    print("video sets ...", flush=True)
    rep_v, set_v, rest_v = process_video_sets(index_by_key)
    print("cap sets ...", flush=True)
    rep_c, set_c, rest_c = process_cap_sets(index)
    print("phone ...", flush=True)
    phone = process_phone()
    L.write_csv(OUT / "reps_video16.csv", rep_v)
    L.write_csv(OUT / "sets_video16.csv", set_v)
    L.write_csv(OUT / "rest_video16.csv", rest_v)
    L.write_csv(OUT / "reps_cap59.csv", rep_c)
    L.write_csv(OUT / "sets_cap59.csv", set_c)
    L.write_csv(OUT / "rest_cap59.csv", rest_c)
    L.write_csv(OUT / "reps_phone.csv", [r for p in phone for r in p["reps"]])

    T = []
    # 1. 분할 요약
    T.append("## 1. 반복 분할 (팔별 굴곡 최소) — 정답 수 대조\n")
    hdr = ["세트", "사람", "정답", "찾음", "방식", "쌍", "위상차", "주기 s", "3D 찾음", "|Δt| 3D 중앙 ms", "가시 L/R", "지연 프레임"]
    rows = [[s["set"], s["subject"], s["truth"], s["found"], s["mode"], s["n_pair"], fmt(s["phase_lag"], 2), fmt(s["period_s"], 2), s["found_3d"],
             fmt(s["dt_3d_med_ms"], 0), f"{s['vis_L']:.2f}/{s['vis_R']:.2f}", f"{s['lag_frames']} ({s['lag_how']})"] for s in set_v]
    T.append(md_table(hdr, rows))
    T.append("\n### 59세트(pose_3d 기준 분할 → cap300)\n")
    exact = sum(1 for s in set_c if s["found"] == s["truth"])
    T.append(f"- 분할 = 정답 세트 {exact}/{len(set_c)}, 방식 {dict(Counter(s['mode'] for s in set_c))}, 좌우 뒤집힘 {sum(s['swapped'] for s in set_c)}세트, "
             f"위상차 중앙값 {fmt(np.nanmedian([s['phase_lag'] for s in set_c]), 2)} (p5 {fmt(np.nanpercentile([s['phase_lag'] for s in set_c], 5), 2)}), "
             f"주기 중앙값 {fmt(np.nanmedian([s['period_s'] for s in set_c]), 2)} s\n")
    mism = [(s["set"], s["truth"], s["found"], s["mode"]) for s in set_c if s["found"] != s["truth"]]
    T.append(f"- 불일치 세트: {mism}\n")
    T.append(f"- 사람별 세트 수: {dict(Counter(s['subject'] for s in set_c))}\n")

    # 2. 가동 범위 분포
    T.append("\n## 2. 반복별 가동 범위 — 수축 최소 · 이완(하강 직전) · 진폭\n")
    for key, name in (("min", "수축 최소(°)"), ("ext_b", "이완 최대(°)"), ("amp_b", "진폭(°)")):
        T.append(f"\n### {name} — 16세트 (f30 기준 창 안 자기 표본)\n")
        T.append(md_table(["스트림", "사람", "n", "p5", "p50", "p95"], dist_table(rep_v, ["f30", "c300", "c333", "cap300"], key)))
        T.append(f"\n### {name} — 59세트 cap300 (pose_3d 기준 창)\n")
        T.append(md_table(["스트림", "사람", "n", "p5", "p50", "p95"], dist_table(rep_c, ["cap300"], key)))
    # 앱 신호(elbow_mean) 같은 창
    T.append("\n### 같은 창의 앱 신호 elbow_mean 최소 · minside 최소 (교대 컬에서 평균이 얼마나 얕게 보이나)\n")
    T.append(md_table(["스트림", "사람", "n", "p5", "p50", "p95"], dist_table(rep_v, ["f30", "c300"], "elbow_mean_min") + dist_table(rep_c, ["cap300"], "elbow_mean_min")))
    T.append("\n(minside 최소)\n")
    T.append(md_table(["스트림", "사람", "n", "p5", "p50", "p95"], dist_table(rep_v, ["f30", "c300"], "elbow_minside_min") + dist_table(rep_c, ["cap300"], "elbow_minside_min")))
    # 300 ms 가 30 fps 를 얼마나 놓치나
    T.append("\n### 300 ms 표본이 30 fps 극값을 놓치는 양 (같은 반복, 스트림 − f30)\n")
    by = defaultdict(dict)
    for r in rep_v:
        by[(r["set"], r["rep"])][r["stream"]] = r
    rows = []
    for st in ("c300", "c333", "cap300"):
        for key in ("min", "ext_b", "amp_b"):
            d = [by[k][st][key] - by[k]["f30"][key] for k in by if st in by[k] and np.isfinite(by[k][st].get(key, np.nan)) and np.isfinite(by[k]["f30"].get(key, np.nan))]
            p = L.pct(d)
            rows.append([st, key, len(d), fmt(p[0]), fmt(p[1]), fmt(p[2]), fmt(float(np.mean(np.abs(d))))])
    T.append(md_table(["스트림", "값", "n", "p5", "p50", "p95", "MAE"], rows))

    # 3. 2D 대리
    T.append("\n## 3. 2D 대리와 월드 각의 일치 (f30, 16세트, 반복별)\n")
    from scipy.stats import pearsonr, spearmanr  # noqa: PLC0415
    reps = [r for r in rep_v if r["stream"] == "f30" and np.isfinite(r.get("min", np.nan))]
    rows = []
    for a, b, label in (("min", "elbow2d_min", "수축 최소: 월드 vs 이미지 팔꿈치각"), ("amp_b", "elbow2d_max", "진폭 vs 이미지각 최대"),
                        ("min", "wrist_sh_dy_min", "수축 최소 vs (손목y−어깨y)min px"), ("amp_b", "wrist_sh_dy_max", "진폭 vs (손목y−어깨y)max px")):
        x = np.array([r[a] for r in reps], float)
        y = np.array([r.get(b, np.nan) for r in reps], float)
        if "wrist" in b:
            y = y / np.array([r.get("forearm_px_top", np.nan) for r in reps], float)
        g = np.isfinite(x) & np.isfinite(y)
        rows.append([label, int(g.sum()), fmt(pearsonr(x[g], y[g])[0], 2), fmt(spearmanr(x[g], y[g])[0], 2), fmt(float(np.mean(y[g])), 2), fmt(float(np.std(y[g])), 2)])
    # 2D 진폭 정의: 이미지각 진폭 = elbow2d_max − elbow2d_min, 손목-어깨 정규화 진폭 = (max − min)/forearm_px
    x = np.array([r["amp_b"] for r in reps], float)
    y2 = np.array([r.get("elbow2d_max", np.nan) - r.get("elbow2d_min", np.nan) for r in reps], float)
    y3 = np.array([(r.get("wrist_sh_dy_max", np.nan) - r.get("wrist_sh_dy_min", np.nan)) / r.get("forearm_px_top", np.nan) for r in reps], float)
    for y, label in ((y2, "진폭 vs 이미지각 진폭"), (y3, "진폭 vs 손목-어깨 세로 진폭/전완px")):
        g = np.isfinite(x) & np.isfinite(y)
        rows.append([label, int(g.sum()), fmt(pearsonr(x[g], y[g])[0], 2), fmt(spearmanr(x[g], y[g])[0], 2), fmt(float(np.mean(y[g])), 2), fmt(float(np.std(y[g])), 2)])
    T.append(md_table(["짝", "n", "Pearson", "Spearman", "2D 평균", "2D σ"], rows))
    T.append("\n### MediaPipe 월드 각 vs 데이터셋 3D(H36M) 각 — 같은 반복 창\n")
    rows = []
    for label, rr, st in (("f30·16", rep_v, "f30"), ("c300·16", rep_v, "c300"), ("cap300·59", rep_c, "cap300")):
        reps2 = [r for r in rr if r["stream"] == st and np.isfinite(r.get("min", np.nan)) and np.isfinite(r.get("ref3d_min", np.nan))]
        for a, b, name in (("min", "ref3d_min", "수축 최소"), ("amp_b", "ref3d_amp_b", "진폭")):
            x = np.array([r[a] for r in reps2], float)
            y = np.array([r[b] for r in reps2], float)
            g2 = np.isfinite(x) & np.isfinite(y)
            d = x[g2] - y[g2]
            rows.append([label, name, int(g2.sum()), fmt(pearsonr(x[g2], y[g2])[0], 2), fmt(spearmanr(x[g2], y[g2])[0], 2),
                         fmt(float(np.median(d))), fmt(float(np.std(d))), fmt(float(np.median(y[g2])))])
    T.append(md_table(["스트림", "값", "n", "Pearson", "Spearman", "편향 중앙(MP−3D)", "σ", "3D 중앙"], rows))
    # 정면 카메라에서 이미지 팔꿈치각의 반복별 분포
    p2 = L.pct([r.get("elbow2d_min", np.nan) for r in reps])
    p3 = L.pct([r.get("wrist_sh_dy_min", np.nan) / r.get("forearm_px_top", np.nan) for r in reps])
    T.append(f"\n- 이미지 팔꿈치각 수축 최소 p5/p50/p95 = {fmt(p2[0])}/{fmt(p2[1])}/{fmt(p2[2])}° (월드 {fmt(L.pct([r['min'] for r in reps])[1])}°) — "
             f"정면 카메라에서 전완이 카메라를 향해 접혀 이미지각이 월드각보다 훨씬 작다. (손목y−어깨y)/전완px 수축 최소 p5/p50/p95 = {fmt(p3[0], 2)}/{fmt(p3[1], 2)}/{fmt(p3[2], 2)}\n")

    # 4. ROM 게이트
    T.append("\n## 4. ROM 게이트 시뮬레이션 — 반복 유지율(재현율) · 세트 정확 일치\n")
    grids = {}
    for label, rr, ss, st in (("cap300·59세트", rep_c, set_c, "cap300"), ("c300·16세트", rep_v, set_v, "c300"), ("c333·16세트", rep_v, set_v, "c333"),
                              ("f30·16세트(상한)", rep_v, set_v, "f30")):
        g = rom_grid(rr, ss, st)
        grids[label] = g
        L.write_csv(OUT / f"rom_grid_{st}_{len(ss)}.csv", g)
        T.append(f"\n### {label}: 절대 격자 — 재현율 (세트 정확)\n")
        hdr = ["X≤ \\ Y≥"] + [str(y) for y in Y_GRID]
        rows = []
        for X in X_GRID:
            row = [str(X)]
            for Y in Y_GRID:
                q = next(z for z in g if z.get("kind") == "abs" and z["X"] == X and z["Y"] == Y)
                row.append(f"{q['recall']:.2f} ({q['set_exact_segok']:.2f})")
            rows.append(row)
        T.append(md_table(hdr, rows))
        base = next(z for z in g if z["gate"].startswith("none"))
        T.append(f"\n- 분할만(게이트 없음): 재현율 {base['recall']:.2f}, 세트 정확 {base['set_exact']:.2f} (반복 {base['n_reps']}, 세트 {base['n_sets']}); "
                 f"괄호의 세트 정확은 분할 = 정답인 {base['n_segok']}세트 기준")
        T.append("- X 만 / Y 만: " + ", ".join(f"X≤{z['X']} {z['recall']:.2f}" for z in g if z.get("kind") == "absX") + " · " +
                 ", ".join(f"Y≥{z['Y']} {z['recall']:.2f}" for z in g if z.get("kind") == "absY"))
        T.append("- 개인 비율(첫 2·3회 진폭 대비): " + ", ".join(f"{z['gate']} {z['recall']:.2f} ({z['set_exact_segok']:.2f})" for z in g if z.get("kind") == "rel_amp"))
        T.append("- 같은 팔 첫 2회 진폭 대비: " + ", ".join(f"{z['ratio']} {z['recall']:.2f} ({z['set_exact_segok']:.2f})" for z in g if z.get("kind") == "rel_arm"))
        T.append("- 시작 자세 대비 깊이 + 복귀: " + ", ".join(f"{z['ratio']} {z['recall']:.2f} ({z['set_exact_segok']:.2f})" for z in g if z.get("kind") == "rel_start"))
        subj_keys = sorted({k for z in g for k in z if k.startswith("recall_")})
        T.append("- 사람별 재현율(절대 X≤90 Y≥140 / 비율 0.7×첫3회 / 0.7×같은 팔 첫2회): " + ", ".join(
            f"{k[7:]} {next(z for z in g if z.get('kind') == 'abs' and z['X'] == 90 and z['Y'] == 140).get(k, float('nan')):.2f}/"
            f"{next(z for z in g if z.get('kind') == 'rel_amp' and z['ratio'] == 0.7 and z['nref'] == 3).get(k, float('nan')):.2f}/"
            f"{next(z for z in g if z.get('kind') == 'rel_arm' and z['ratio'] == 0.7).get(k, float('nan')):.2f}" for k in subj_keys) + "\n")

    # 5. 후보 분포
    T.append("\n## 5. 반동·벌어짐 후보 — 정상 반복(MM-Fit)의 분포와 서 있을 때 잡음\n")
    T.append("d = 수축 표본 − 이완(하강 직전) 표본, r = 반복 창 안 범위(max − min), x = 창 최대 − 이완 표본. 동시 반복은 두 팔 중 큰 값.\n")
    for label, rr, rs, st in (("cap300·59세트", rep_c, rest_c, "cap300"), ("c300·16세트", rep_v, rest_v, "c300"), ("f30·16세트", rep_v, rest_v, "f30")):
        cs, rest = cand_stats(rr, rs, st)
        L.write_csv(OUT / f"cand_{st}_{len(rr)}.csv", cs)
        L.write_csv(OUT / f"cand_rest_{st}.csv", rest)
        T.append(f"\n### {label}\n")
        hdr = ["후보", "통계", "n", "p5", "p50", "p95", "p99", "서 있음 σ(중앙)", "서 있음 2s 범위 p95(중앙/최대)", "띠별 오탐률(x·n)"]
        rows = []
        rest_by = {r["cand"]: r for r in rest}
        for c in CANDS:
            for pre in (("d", "x", "n") if c in BANDS_N else ("d", "x")):
                q = next(z for z in cs if z["cand"] == c and z["stat"] == pre)
                rb = rest_by.get(c, {})
                fp = ", ".join(f">{k[3:]}: {v:.2f}" for k, v in q.items() if k.startswith("fp>") and np.isfinite(v)) if pre in ("x", "n") else ""
                nd = 3 if q["p95"] is not None and abs(q["p95"]) < 2 else 1
                rows.append([c, pre, q["n"], fmt(q["p5"], nd), fmt(q["p50"], nd), fmt(q["p95"], nd), fmt(q["p99"], nd),
                             fmt(rb.get("sigma_med"), nd) if pre == "d" else "", f"{fmt(rb.get('range2s_p95_med'), nd)}/{fmt(rb.get('range2s_p95_max'), nd)}" if pre == "d" else "", fp])
        T.append(md_table(hdr, rows))

    # 6. 수축 정점의 300 ms 표본 수 · 템포
    T.append("\n## 6. 수축 정점(평활각 ≤ 최소+10°)에 들어오는 300 ms 표본 수 · 템포\n")
    rows = []
    for label, rr, st in (("c300·16", rep_v, "c300"), ("c333·16", rep_v, "c333"), ("cap300·16", rep_v, "cap300"), ("cap300·59", rep_c, "cap300")):
        reps = [r for r in rr if r["stream"] == st and np.isfinite(r.get("min", np.nan))]
        nc = Counter(min(r["n_contract"], 3) for r in reps)
        n = len(reps)
        cs = L.pct([r["contract_s"] for r in reps])
        ds = L.pct([r["dur_s"] for r in reps])
        cy = L.pct([r["cycle_s"] for r in reps])
        rows.append([label, n, f"{nc[0] / n:.2f}", f"{nc[1] / n:.2f}", f"{nc[2] / n:.2f}", f"{nc[3] / n:.2f}", f"{cs[0]:.2f}/{cs[1]:.2f}/{cs[2]:.2f}",
                     f"{ds[0]:.2f}/{ds[1]:.2f}/{ds[2]:.2f}", f"{cy[0]:.2f}/{cy[1]:.2f}/{cy[2]:.2f}",
                     f"{L.pct([r['t_min_err'] for r in reps])[1]:.0f}", f"{np.mean(np.abs([r['t_min_err'] for r in reps])):.0f}"])
    T.append(md_table(["스트림", "반복", "0개", "1개", "2개", "3개+", "수축 국면 s p5/50/95", "반복(하강→복귀) s", "창 s", "최소 시각 오차 중앙 ms", "MAE ms"], rows))
    # 세트 템포별 300 ms 최소 오차
    T.append("\n(세트 주기별, cap300·59: 주기 < 1.6 s / 1.6~2.0 / ≥ 2.0 s 의 반복 수, 수축 표본 0개 비율, min 편차(cap300 − 3D 기준의 앱 값이 아님 — 같은 창 3D 최소와의 차이는 편향 포함이라 싣지 않음))\n")
    per_set = {s["set"]: s["period_s"] for s in set_c}
    rows = []
    for lo_, hi_, name in ((0, 1.6, "< 1.6 s"), (1.6, 2.0, "1.6~2.0 s"), (2.0, 99, "≥ 2.0 s")):
        reps = [r for r in rep_c if np.isfinite(r.get("min", np.nan)) and np.isfinite(per_set.get(r["set"], np.nan)) and lo_ <= per_set[r["set"]] / 2 < hi_]
        if not reps:
            continue
        rows.append([name, len(reps), f"{np.mean([r['n_contract'] == 0 for r in reps]):.2f}", fmt(L.pct([r["min"] for r in reps])[1]), fmt(L.pct([r["amp_b"] for r in reps])[1])])
    T.append(md_table(["반복 간격(교대 — 한 팔 주기/2)", "반복", "수축 표본 0개", "min p50", "진폭 p50"], rows))

    # 7. 폰
    T.append("\n## 7. 폰 덤벨 컬 2세트 (2026-09-12, 피처만, 정답 없음)\n")
    for p in phone:
        T.append(f"\n### {p['set']} — 앱 {p['app_count']}회(유효 {sum(p['app_valid'])}), 프레임 {p['n_frames']}(피처 {p['n_feat']}), 기울기 {p['tilt']:.0f}°, "
                 f"뷰 {p['view']}, 앵커 {p['anchor_ms']} ms, 팔 가시 L/R {p['vis_L']:.2f}/{p['vis_R']:.2f}\n")
        T.append(f"- 팔별 분할: {p['found']}회, 방식 {p['mode']}, 위상차 {fmt(p['phase_lag'], 2)}, 주기 {fmt(p['period_s'], 2)} s; 앱 반복 시각 {p['app_t']}, 앱 min {[round(v) for v in p['app_min']]}\n")
        hdr = ["회", "팔", "t s", "min", "이완 b", "진폭", "수축표본", "반복 s", "n 상완(앞으로)", "x 팔꿈치이탈", "x 팔꿈치높이", "x 어깨h", "r 몸통", "d 몸통", "elbow_mean min"]
        rows = [[r["rep"], r["arm"], f"{r['t_min'] / 1000:.1f}", fmt(r.get("min")), fmt(r.get("ext_b")), fmt(r.get("amp_b")), r["n_contract"], fmt(r["dur_s"], 2),
                 fmt(r.get("n_upperarm_vert")), fmt(r.get("x_elbow_torso"), 3), fmt(r.get("x_elbow_h"), 3), fmt(r.get("x_shoulder_h"), 3), fmt(r.get("r_torso_incl")),
                 fmt(r.get("d_torso_incl")), fmt(r.get("elbow_mean_min"))] for r in p["reps"]]
        T.append(md_table(hdr, rows))
        rest = p["rest"]
        T.append(f"\n- 서 있음 잡음({p['rest_note']}; σ / 2 s 범위 p95): " + ", ".join(f"{k} {fmt(v['sigma'], 3 if 'torso' not in k and 'vert' not in k else 1)}/{fmt(v['range2s_p95'], 3 if 'torso' not in k and 'vert' not in k else 1)} (n={v['n_rest']})"
                                                     for k, v in rest.items() if k in ("upperarm_vert_L", "upperarm_vert_R", "elbow_torso_L", "elbow_torso_R", "torso_incl", "shoulder_h_L", "shoulder_h_R")))
        T.append(f"- 세트 규칙 결과: {[(r['rule_id'], r['verdict'], round(r['value'], 3)) for r in p['results']]}")
        T.append(f"- 게이트 통과 회: 절대 X≤90·Y≥140 {p['gate_abs_90_140']} ({len(p['gate_abs_90_140'])}/{p['found']}), 비율 0.7×첫3회 {p['gate_rel_0.7']} ({len(p['gate_rel_0.7'])}/{p['found']})")
        T.append(f"- 띠 초과 회(MM-Fit 정상 p95 부근): {p['flags']}  [서 있음 창: {p['rest_note']}]\n")

    (OUT / "tables.md").write_text("\n".join(T), encoding="utf-8")
    summary = {"video_sets": set_v, "cap_sets": set_c, "phone": [{k: v for k, v in p.items() if k not in ("reps", "rest", "results")} for p in phone],
               "rom_grids": grids}
    (OUT / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=1, default=lambda o: float(o) if isinstance(o, (np.floating,)) else str(o)), encoding="utf-8")
    print("\n".join(T))


if __name__ == "__main__":
    main()
