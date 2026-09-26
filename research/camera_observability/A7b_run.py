# -*- coding: utf-8 -*-
"""A7b — 발 너비·발끝 후보 측정법의 반복별 값을 모집단(REHAB24-6 · MM-Fit)과 폰 정답 세트에서 뽑는다.

세션(카운터·평가기를 처음부터 돌리는 단위):
    rehab  : (영상, 카메라, 블록) — 블록 = 같은 방향·같은 정상/오류의 연속 반복(A3 정의). 블록 앞 1.5 s ~ 뒤 1.5 s. 30 fps(f30) · 진짜 300 ms(c300) 둘 다.
    mmfit30: cap30 세트 하나(w06·w14·w18·w19·w20 × 3, 앞 6 s) — f30 · c300.
    mmfit  : data/mmfit_mp_captures 의 스쿼트 64세트(진짜 300 ms 캡처, 앞 15 s).
    phone  : 사용자 정답 세트(300 ms 실기기 로그).
반복 라벨: rehab = Segmentation.csv 반복(사이클 최소 프레임이 든 반복)의 correctness · 뷰, mmfit = 라벨 창 안이면 정상, 뷰 = 사이클 |요| 중앙값(앱 분류),
           phone = 사용자 정답(반복 순서 = 앱 t_ms 순서, 파리티 확인됨).
후보 값(반복마다, 값의 정의는 A7b_lib 머리말·아래 rep_row):
    발 너비  st_app(앱: 서 있는 프레임 극값 ÷ 시작 stance_2d)  st_stand_ext_ank(서 있는 프레임 극값, 발목 px ÷ 시작 발목 px)
            st_bot_rel(바닥 중앙값 발목 px ÷ 시작 발목 px)     st_bot_abs(바닥 중앙값 발목 px ÷ 시작 어깨 px)
            st_fr_max / st_fr_med(창 프레임마다 stance_2d 의 최대/중앙값)  st_top{N}_ank / st_top{N}_2d(하강 직전 N 프레임 중앙값, 상대)
    발끝    toe_{m}_{side}_n{N}(하강 직전 N 프레임 중앙값 − 시작 1 s 중앙값), toe_{m}_{side}_stand(앱 창: 상단+꼬리 서 있음 중앙값 − 시작), toe_app(앱 파리티)
            m ∈ ank2d·heel2d·lat·w, side ∈ L·R·max(앱 maxside)·mean
출력: outputs/A7b/reps.csv, sessions.csv
"""
from __future__ import annotations

import csv
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from A7b_lib import (OUT, REPO, TOE_MEASURES, TOP_NS, Frames, frame_vars, load_cap30, load_cap_file,  # noqa: E402
                     load_phone_set, run_counter, run_evaluator, view_class)

sys.stdout.reconfigure(encoding="utf-8")
OUT.mkdir(parents=True, exist_ok=True)

REHAB_VIDEOS = ["PM_008", "PM_022", "PM_029", "PM_038", "PM_043", "PM_105", "PM_113", "PM_118", "PM_126"]
MMFIT30 = [f"mmfit_{w}_set{k:02d}" for w in ("w06", "w14", "w18", "w19", "w20") for k in range(3)]
PHONE = {  # set_id: (이름, 반복별 정답, 월드(up) 신뢰)
    "20260925T031938-6e1e8e89": ("12:19", ["normal", "normal", "toe_in", "toe_in", "toe_out", "toe_out", "wide", "wide", "normal", "normal"], True),
    "20260925T071437-4f0aa81d": ("16:14", ["normal"] * 4 + ["wide"] * 3, True),
    "20260925T071609-79a23977": ("16:16", ["normal", "normal", "toe_out", "wide", "wide", "toe_out", "unknown"], True),
    "20260925T071348-63fe9f27": ("16:13", ["unknown"] * 4 + ["wide?"] * 3 + ["unknown"] * 14, False),
    "20260925T023707-5155c5dd": ("11:37", ["normal", "normal", "wide+toe_out", "wide+toe_out", "toe_out", "toe_out", "toe_in", "toe_in", "wide", "wide"], True),
}
SIDES4 = ("L", "R", "max", "mean")
BASE_KEYS = ["knee_mean", "stance_2d", "ank_gap", "sh_gap"] + [f"toe_{m}_{s}" for m in TOE_MEASURES for s in SIDES4]


def med(a):
    a = a[np.isfinite(a)]
    return float(np.median(a)) if len(a) else np.nan


def sd(a, need=2):
    a = a[np.isfinite(a)]
    return float(np.std(a, ddof=1)) if len(a) >= need else np.nan


def rep_rows(V: dict, t: np.ndarray, wins, base_row: dict) -> list[dict]:
    """세션 하나의 사이클마다 후보 값 한 행. 시작 1 s 기준(start1s)은 첫 사이클의 하강 직전 1 s 서 있는 프레임 중앙값."""
    rows = []
    start = None
    for k, w in enumerate(wins):
        ph = w.phases
        if start is None and ph.top_end >= 0:
            te = ph.top_end
            sel = [i for i in ph.window_all if t[te] - 1000 < t[i] <= t[te] and np.isfinite(V["knee_mean"][i]) and V["knee_mean"][i] >= ph.standing_level]
            start = {kk: med(V[kk][sel]) for kk in BASE_KEYS if kk != "knee_mean"}
            start["_n"] = len(sel); start["_t"] = int(t[te])
        b = w.baseline or {}
        st = start or {}
        stand = ph.top + ph.trailing
        own = ph.own
        r = dict(base_row)
        r.update(cycle=k + 1, end_t=int(w.event.t), cmin=round(w.event.cmin, 2), cmax=round(w.event.cmax, 2),
                 n_top=len(ph.top), n_bottom=len(ph.bottom), n_stand=len(stand), n_win=len(own), n_start=st.get("_n", 0),
                 has_base=int(bool(b)), has_start=int(bool(st) and np.isfinite(st.get("ank_gap", np.nan))),
                 yaw_abs_med=med(np.abs(V["yaw"][own])) if own else np.nan)
        r["view_cls"] = view_class(r["yaw_abs_med"])
        # 최소 프레임(라벨 대조용)
        if own:
            km = V["knee_mean"][own]
            r["min_idx"] = int(own[int(np.nanargmin(km))])
        else:
            r["min_idx"] = -1
        # ---- 발 너비
        s2 = V["stance_2d"]; ag = V["ank_gap"]; sg = V["sh_gap"]
        base2 = b.get("stance_2d", np.nan); sa = st.get("ank_gap", np.nan); ss = st.get("sh_gap", np.nan)
        vals = s2[stand]; vals = vals[np.isfinite(vals)]
        if np.isfinite(base2) and len(vals):
            rr = vals / base2; r["st_app"] = float(rr[np.argmax(np.abs(rr - 1))]); r["st_app_raw"] = float(vals[np.argmax(np.abs(rr - 1))])
        else:
            r["st_app"] = np.nan; r["st_app_raw"] = np.nan
        vals = ag[stand]; vals = vals[np.isfinite(vals)]
        if np.isfinite(sa) and sa > 0 and len(vals):
            rr = vals / sa; r["st_stand_ext_ank"] = float(rr[np.argmax(np.abs(rr - 1))])
        else:
            r["st_stand_ext_ank"] = np.nan
        bot_ag = ag[ph.bottom]; bot_ag = bot_ag[np.isfinite(bot_ag)]
        r["n_bot_ank"] = int(len(bot_ag))
        r["st_bot_rel"] = float(np.median(bot_ag) / sa) if (len(bot_ag) >= 2 and np.isfinite(sa) and sa > 0) else np.nan
        r["st_bot_abs"] = float(np.median(bot_ag) / ss) if (len(bot_ag) >= 2 and np.isfinite(ss) and ss > 0) else np.nan
        r["st_bot_rel1"] = float(np.median(bot_ag) / sa) if (len(bot_ag) >= 1 and np.isfinite(sa) and sa > 0) else np.nan
        fr2 = s2[own]; fr2 = fr2[np.isfinite(fr2)]
        r["st_fr_max"] = float(np.max(fr2)) if len(fr2) else np.nan
        r["st_fr_med"] = float(np.median(fr2)) if len(fr2) else np.nan
        stv = s2[stand]; stv = stv[np.isfinite(stv)]
        r["st_stand_max_abs"] = float(np.max(stv)) if len(stv) else np.nan
        for n in TOP_NS:
            top_n = ph.top[-n:]
            v_ank = ag[top_n]; v_2d = s2[top_n]
            r[f"st_top{n}_ank"] = (med(v_ank) / sa) if (np.isfinite(sa) and sa > 0 and np.isfinite(v_ank).any()) else np.nan
            r[f"st_top{n}_2d"] = (med(v_2d) / base2) if (np.isfinite(base2) and np.isfinite(v_2d).any()) else np.nan
            r[f"n_top{n}"] = int(np.isfinite(v_ank).sum())
        # 안정성(반복 안 프레임 간 SD)
        r["sd_stand_2d"] = sd(s2[stand]); r["sd_bot_2d"] = sd(s2[ph.bottom]); r["sd_win_2d"] = sd(s2[own])
        if np.isfinite(sa) and sa > 0:
            r["sd_stand_ank"] = sd(ag[stand] / sa); r["sd_bot_ank"] = sd(ag[ph.bottom] / sa); r["sd_win_ank"] = sd(ag[own] / sa)
        else:
            r["sd_stand_ank"] = r["sd_bot_ank"] = r["sd_win_ank"] = np.nan
        sgs = sg[stand]; sgs = sgs[np.isfinite(sgs)]
        r["cv_sh_stand"] = float(np.std(sgs, ddof=1) / np.mean(sgs)) if len(sgs) >= 2 else np.nan
        sgo = sg[own]; sgo = sgo[np.isfinite(sgo)]
        r["cv_sh_win"] = float(np.std(sgo, ddof=1) / np.mean(sgo)) if len(sgo) >= 2 else np.nan
        r["sh_min_over_start"] = float(np.min(sgo) / ss) if (len(sgo) and np.isfinite(ss) and ss > 0) else np.nan
        # ---- 발끝
        for m in TOE_MEASURES:
            for side in SIDES4:
                key = f"toe_{m}_{side}"
                s0 = st.get(key, np.nan)
                for n in TOP_NS:
                    vv = V[key][ph.top[-n:]]
                    r[f"{key}_n{n}"] = (med(vv) - s0) if (np.isfinite(s0) and np.isfinite(vv).any()) else np.nan
                vv = V[key][stand]
                r[f"{key}_stand"] = (med(vv) - s0) if (np.isfinite(s0) and np.isfinite(vv).sum() >= 2) else np.nan
                r[f"{key}_sd"] = sd(V[key][stand])
        vv = V["toe_w_max"][stand]
        r["toe_app"] = (med(vv) - b["toe_w_max"]) if ("toe_w_max" in b and np.isfinite(vv).sum() >= 2) else np.nan
        rows.append(r)
    return rows


def frame_vars_from_features(d: dict, fr: Frames) -> dict:
    """좌표가 없는 세트(11:37, 피처만): 앱이 남긴 피처로 V 를 만든다 — knee_mean/knee_maxside, 월드 toe_out(L/R/maxside/mean), 요(view_cos/sin).
    2D 후보(stance_2d·발목 px·2D 발끝)는 전부 NaN(= 유보)."""
    n = len(fr.t)
    feats = [f.get("features") or {} for f in d["frames"]]

    def col(k):
        return np.array([f.get(k, np.nan) if f.get(k) is not None else np.nan for f in feats], float)
    V = {k: np.full(n, np.nan) for k in ["stance_2d", "ank_gap", "sh_gap", "ank_gap_e", "lat"]}
    V["knee_mean"] = col("knee_mean"); V["knee_maxside"] = col("knee_maxside")
    V["yaw"] = np.degrees(np.arctan2(col("view_sin"), col("view_cos")))
    V["valid"] = np.isfinite(V["knee_mean"])
    for m in TOE_MEASURES:
        for s in SIDES4:
            V[f"toe_{m}_{s}"] = np.full(n, np.nan)
    V["toe_w_L"] = col("toe_out_L"); V["toe_w_R"] = col("toe_out_R"); V["toe_w_max"] = col("toe_out_maxside"); V["toe_w_mean"] = col("toe_out_mean")
    V["vis"] = np.fmin(fr.img[..., 2], fr.img[..., 3]); V["ok"] = np.isfinite(V["vis"]) & (V["vis"] >= 0.5)
    return V


def run_session(fr: Frames, base_row: dict, label_fn, sessions_out: list, rows_out: list, note: str = "", feats_doc: dict | None = None):
    V = frame_vars(fr) if feats_doc is None else frame_vars_from_features(feats_doc, fr)
    ev = run_counter(fr.t, V["knee_mean"], V["knee_maxside"])
    in_buf = fr.det & (np.isfinite(V["knee_mean"]) | np.isfinite(V["stance_2d"]))
    wins = run_evaluator(fr.t, V, ev, in_buf, BASE_KEYS)
    rows = rep_rows(V, fr.t, wins, base_row)
    for r in rows:
        label_fn(r, fr)
    rows_out.extend(rows)
    sessions_out.append({**base_row, "n_frames": len(fr.t), "n_cycles": len(wins), "n_rejected": sum(1 for e in ev if e.kind == "rejected"),
                         "n_labeled": sum(1 for r in rows if r["label"] != "unlabeled"), "note": note})
    return rows


def main() -> int:
    rows: list[dict] = []
    sessions: list[dict] = []
    seg = [r for r in csv.DictReader((REPO / "data" / "rehab24-6" / "Segmentation.csv").open(encoding="utf-8"), delimiter=";")
           if r["exercise_id"] == "6"]
    # ------------------------------------------------------------------ REHAB (블록 세션)
    for vid in REHAB_VIDEOS:
        for cam in ("17", "18"):
            meta, f30, c300 = load_cap30(f"rehab_{vid}_{cam}")
            reps = sorted([r for r in seg if r["video_id"] == vid], key=lambda r: int(r["first_frame"]))
            blocks, prev = [], None
            for r in reps:
                a, b = int(r["first_frame"]), int(r["last_frame"])
                key = (r["cam17_orientation"], r["correctness"])
                if prev is None or a - prev[1] > 60 or prev[2] != key:
                    blocks.append([])
                blocks[-1].append(r)
                prev = (a, b, key)
            for bi, blk in enumerate(blocks):
                a0, b1 = int(blk[0]["first_frame"]) - 45, int(blk[-1]["last_frame"]) + 45
                orient = blk[0]["cam17_orientation"]
                view = {"17": {"front": "rehab17_front", "half-profile": "rehab17_half"},
                        "18": {"front": "rehab18_side", "half-profile": "rehab18_obl"}}[cam][orient]
                for sname, stream in (("f30", f30), ("c300", c300)):
                    sel = np.flatnonzero((stream.frame_no >= a0) & (stream.frame_no <= b1))
                    if len(sel) < 5:
                        continue
                    sub = Frames(stream.t[sel], stream.img[sel], stream.wld[sel], stream.det[sel], None, stream.W, stream.H, stream.frame_no[sel])
                    base = {"dataset": "rehab", "session": f"{vid}_{cam}_b{bi}", "stream": sname, "person": blk[0]["person_id"], "view": view,
                            "block_correct": int(blk[0]["correctness"] == "1"), "label": "unlabeled", "gt_rep": ""}

                    def label(r, frm, blk=blk):
                        if r["min_idx"] < 0:
                            return
                        f = int(frm.frame_no[r["min_idx"]])
                        for g in blk:
                            if int(g["first_frame"]) <= f <= int(g["last_frame"]):
                                r["label"] = "normal" if g["correctness"] == "1" else "error"
                                r["gt_rep"] = g["repetition_number"]
                                return
                    run_session(sub, base, label, sessions, rows)
    # ------------------------------------------------------------------ MM-Fit cap30
    for name in MMFIT30:
        meta, f30, c300 = load_cap30(name)
        lo, hi = meta["labelStart"] - 30, meta["labelEnd"] + 30
        for sname, stream in (("f30", f30), ("c300", c300)):
            base = {"dataset": "mmfit30", "session": name, "stream": sname, "person": meta["workout"], "view": "mmfit", "block_correct": 1,
                    "label": "unlabeled", "gt_rep": ""}

            def label(r, frm, lo=lo, hi=hi):
                if r["min_idx"] >= 0 and lo <= frm.frame_no[r["min_idx"]] <= hi:
                    r["label"] = "normal"
                r["view"] = "mmfit_" + r["view_cls"]
            run_session(stream, base, label, sessions, rows)
    # ------------------------------------------------------------------ MM-Fit 300 ms 캡처 64세트
    index = json.loads((REPO / "data" / "mmfit_mp_captures" / "index.json").read_text(encoding="utf-8"))
    for s in index["sets"]:
        if s["activity"] != "squats":
            continue
        meta, fr = load_cap_file(REPO / "data" / "mmfit_mp_captures" / s["capture"])
        lo, hi = s["startMs"] - 1000, s["endMs"] + 1000
        base = {"dataset": "mmfit", "session": s["capture"].split("/")[-1].replace(".cap", ""), "stream": "c300", "person": s["workout"],
                "view": "mmfit", "block_correct": 1, "label": "unlabeled", "gt_rep": ""}

        def label(r, frm, lo=lo, hi=hi):
            if r["min_idx"] >= 0 and lo <= frm.t[r["min_idx"]] <= hi:
                r["label"] = "normal"
            r["view"] = "mmfit_" + r["view_cls"]
        run_session(fr, base, label, sessions, rows)
    # ------------------------------------------------------------------ 폰 정답 세트
    for sid, (nm, truth, world_ok) in PHONE.items():
        d, fr = load_phone_set(sid)
        base = {"dataset": "phone", "session": nm, "stream": "phone", "person": "user1", "view": "phone_C", "block_correct": 1,
                "label": "unlabeled", "gt_rep": ""}

        def label(r, frm, truth=truth, world_ok=world_ok, d=d):
            k = r["cycle"] - 1
            r["label"] = truth[k] if k < len(truth) else "unknown"
            r["gt_rep"] = str(k + 1)
            r["world_ok"] = int(world_ok)
            r["app_t_ms"] = d["reps"]["t_ms"][k] if k < len(d["reps"]["t_ms"]) else ""
        feats_only = not np.isfinite(fr.img[..., 0]).any()
        run_session(fr, base, label, sessions, rows, note="features_only" if feats_only else "", feats_doc=d if feats_only else None)
    # ------------------------------------------------------------------ 쓰기
    keys = []
    for r in rows:
        for k in r:
            if k not in keys:
                keys.append(k)
    with (OUT / "reps.csv").open("w", encoding="utf-8-sig", newline="") as f:
        w = csv.DictWriter(f, fieldnames=keys)
        w.writeheader()
        for r in rows:
            w.writerow({k: ("" if (isinstance(v, float) and not np.isfinite(v)) else v) for k, v in r.items()})
    with (OUT / "sessions.csv").open("w", encoding="utf-8-sig", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(sessions[0].keys()))
        w.writeheader(); w.writerows(sessions)
    print(f"rows {len(rows)} sessions {len(sessions)} → {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
