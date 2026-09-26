# -*- coding: utf-8 -*-
"""A3 — 변수별 필요 프레임률 · 뷰별 MediaPipe 잡음 · REHAB 정상/오류 AUC.

입력  outputs/A3/cap30/*.npz (A3_extract30.py)
출력  outputs/A3/
        reps.csv                 반복 목록(뷰·정답·요·템포)
        phase_durations.csv      국면 지속시간 분포(ms) + 간격별 국면 표본 수·실패율(0~1개)
        ref_stats.csv            반복 × 통계(30 fps 기준값)
        subsample_errors.csv     통계 × 간격: 실패율·편향·|오차| 분위·상대 오차·판정
        subsample_by_view.csv    같은 것을 뷰별로(300 ms 중심)
        tracker_effect.csv       진짜 앱 주기(c<I>, 위상 0) − 같은 프레임 솎아내기 차이
        landmark_diff.csv        같은 프레임의 c300 − f30 랜드마크 차(px·cm)
        view_distributions.csv   뷰별 정상 반복 p5/p50/p95
        view_jitter.csv          뷰별 서 있을 때 프레임 간 떨림(30 fps · 진짜 300 ms)
        view_visibility.csv      뷰별 발목·뒤꿈치·발끝 가시성, 좌우 뒤바뀜
        auc.csv                  REHAB 정상/오류 AUC(합동·사람 안)
        summary.json
"""
from __future__ import annotations

import csv
import json
import sys
import time
from collections import defaultdict
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from A3_lib import (CAP, FPS, OUT, PHASES, STATS, STAT_IDS, Rep, block_refs, frame_vars, grid, load_capture,  # noqa: E402
                    mmfit_reps, phase_bounds, rehab_reps, rep_stats, smooth, true_phases)

INTERVALS = [100, 150, 200, 300, 333, 450]
STRIDE = {100: 3.0, 150: 4.5, 200: 6.0, 300: 9.0, 333: 10.0, 450: 13.5}
# 뷰 = 실제 측정 요(앱 ViewEstimator 정의)의 순서. MM-Fit 은 '고정 정면' 이 아니다 — 반복별 |요| ≤ 16.4°(앱 C) 면 mmfit_C, 아니면 mmfit_BD
VIEWS = ["rehab17_front", "mmfit_C", "mmfit_BD", "rehab18_obl", "rehab17_half", "rehab18_side"]
FRONT_MAX_DEG = 16.4
TOL_OK, TOL_EDGE, FAIL_OK, FAIL_EDGE = 0.10, 0.20, 0.05, 0.10
# 통계 → 그 통계를 이루는 프레임 변수(잡음 바닥 비교용). 파생량(상승 초반·하강 속도·회복·발 이동)은 대응 없음.
STAT_JIT = {
    "R_hip_drop.img.max": "pel_y/leg", "R_hip_drop.img.bot": "pel_y/leg", "R_hip_drop.w.max": "hip_h_w/self",
    "R_depth.w.min": "thigh_incl_w_mean", "R_depth.w.bot": "thigh_incl_w_mean", "R_depth.img.min": "thigh_incl_img_mean",
    "R_depth.ratio_w.min": "depth_ratio_w_mean", "R_depth.ratio_img.min": "hipknee_dy_mean/thigh",
    "R_knee_min.max": "flex_less", "R_knee_min.bot": "flex_less",
    "F_knee_foot.fppa.bot": "fppa_mean", "F_knee_foot.kout_img.bot": "kout_img_mean", "F_knee_foot.kout_w.bot": "kout_w_mean",
    "F_knee_foot.kasr_img.bot": "kasr_img", "F_knee_foot.kasr_w.bot": "kasr_w", "F_knee_foot.kf_ang.bot": "kf_ang_mean",
    "F_heel.ankle_rise.max": "anky_mean/leg", "F_heel.ankle_rise.bot": "anky_mean/leg",
    "F_heel.heeltoe_img.bot": "heeltoe_img_mean/leg", "F_heel.heel_lift_w.bot": "heel_lift_w_mean",
    "F_lateral.tilt_w.bot": "pelvis_tilt_w", "F_lateral.tilt_img.bot": "pelvis_tilt_img",
    "F_lateral.shift_img.bot": "pel_shift_img", "F_lateral.shift_w.bot": "pel_shift_w", "F_lateral.knee_asym.bot": "knee_asym",
    "T_torso.dmax": "torso_incl", "S_toe.stand": "toe_max", "S_stance.w.stand": "stance_w", "S_stance.img.stand": "stance_2d",
}
STAT_PHASE = {  # 통계 → 그 통계가 기대는 참 국면(표본 수 실패율 계산용)
    "bot": "바닥(앱 정의 d≥2/3)", "hip_first": "바닥→상승1/3 창", "descent": "하강", "stand": "서 있음",
}


def stat_phase(sid: str) -> str | None:
    if sid.endswith(".bot"):
        return STAT_PHASE["bot"]
    if sid.startswith("F_hip_first"):
        return STAT_PHASE["hip_first"]
    if sid.startswith("F_descent"):
        return STAT_PHASE["descent"]
    if sid.endswith(".stand"):
        return STAT_PHASE["stand"]
    return None


def expand(Vc: dict, idx: np.ndarray, n: int) -> dict:
    out = {}
    for k, v in Vc.items():
        if isinstance(v, np.ndarray) and v.ndim == 1 and v.dtype != bool:
            a = np.full(n, np.nan)
            a[idx] = v
            out[k] = a
    return out


def jit_series(Vs: dict, V30: dict, var: str, stand_mask: np.ndarray) -> np.ndarray:
    """떨림 계산용 프레임 계열. '/leg' = 서 있는 다리 px, '/thigh' = 서 있는 허벅지 px, '/self' = 서 있는 자기 중앙값으로 나눔(30 fps 기준)."""
    base, _, norm = var.partition("/")
    x = Vs[base]
    if norm == "leg":
        x = x / np.nanmedian(V30["leg_px"][stand_mask])
    elif norm == "thigh":
        x = x / np.nanmedian(V30["thigh_px_mean"][stand_mask])
    elif norm == "self":
        x = x / np.nanmedian(V30[base][stand_mask])
    return x


TEMPO_BINS = [(0, 1200, "동작<1.2s"), (1200, 1600, "동작1.2~1.6s"), (1600, 2000, "동작1.6~2.0s"), (2000, 2500, "동작2.0~2.5s"),
              (2500, 1e9, "동작≥2.5s")]


def tempo_bin(ms: float) -> str:
    for lo, hi, name in TEMPO_BINS:
        if lo <= ms < hi:
            return name
    return TEMPO_BINS[-1][2]


def q(v, p):
    v = np.asarray(v, float)
    v = v[np.isfinite(v)]
    return float(np.percentile(v, p)) if len(v) else np.nan


def auc(pos, neg):
    """P(pos > neg) + 0.5 P(=). pos = 오류 반복."""
    pos, neg = np.asarray(pos, float), np.asarray(neg, float)
    pos, neg = pos[np.isfinite(pos)], neg[np.isfinite(neg)]
    if len(pos) == 0 or len(neg) == 0:
        return np.nan, 0, 0
    gt = (pos[:, None] > neg[None, :]).sum()
    eq = (pos[:, None] == neg[None, :]).sum()
    return float((gt + 0.5 * eq) / (len(pos) * len(neg))), len(pos), len(neg)


def write_csv(path: Path, rows: list[dict]):
    if not rows:
        return
    keys = list(rows[0].keys())
    for r in rows[1:]:
        for k in r:
            if k not in keys:
                keys.append(k)
    with path.open("w", newline="", encoding="utf-8-sig") as h:
        w = csv.DictWriter(h, fieldnames=keys)
        w.writeheader()
        for r in rows:
            w.writerow({k: (f"{v:.5g}" if isinstance(v, float) else v) for k, v in r.items()})


def main() -> int:
    t0 = time.time()
    names = sorted(p.stem for p in CAP.glob("*.npz") if not p.stem.endswith(".tmp"))
    all_reps: list[Rep] = []
    ref_rows, sub_err = [], defaultdict(list)          # sub_err[(sid, I)] = [(rep_key, view, phase, err, fail)]
    trk_err = defaultdict(list)                         # (sid, I) -> [(view, err_track, err_sample, fail_c, fail_s)]
    phase_dur = defaultdict(list)                        # (dataset/view, phase) -> [ms]
    phase_cnt = defaultdict(list)                        # (phase, I) -> [n samples]
    jitter = defaultdict(lambda: defaultdict(list))     # view -> var -> [(kind, value)]
    vis_rows = defaultdict(lambda: defaultdict(list))   # view -> metric -> [values]
    lm_diff = defaultdict(list)                          # (dataset, joint group, space) -> [diffs]
    rep_info = []
    rep_tempo = {}
    cad_rows = []
    JIT_VARS = {"pel_y/leg": None, "hip_h_w/self": None, "knee_mean": 1, "flex_less": 1, "torso_incl": 1,
                "thigh_incl_w_mean": 1, "thigh_incl_img_mean": 1, "depth_ratio_w_mean": 1, "hipknee_dy_mean/thigh": None,
                "kout_w_mean": 1, "kout_img_mean": 1, "fppa_mean": 1, "kasr_img": 1, "kasr_w": 1, "kf_ang_mean": 1,
                "toe_max": 1, "stance_w": 1, "stance_2d": 1, "pelvis_tilt_w": 1, "pelvis_tilt_img": 1,
                "pel_shift_img": 1, "pel_shift_w": 1, "knee_asym": 1, "heel_lift_w_mean": 1, "anky_mean/leg": None,
                "heeltoe_img_mean/leg": None}

    for name in names:
        cap = load_capture(name)
        N = len(cap.f30.frames)
        assert np.all(np.diff(cap.f30.frames) == 1), name
        V = frame_vars(cap.f30, cap.wa, cap.ha)
        reps = rehab_reps(cap, V) if cap.meta["dataset"] == "rehab" else mmfit_reps(cap, V)
        if cap.meta["dataset"] == "mmfit":
            for r in reps:
                ay = np.nanmedian(np.abs(V["yaw"][r.a:r.b + 1]))
                r.view = "mmfit_C" if ay <= FRONT_MAX_DEG else "mmfit_BD"
        # 30 fps 참 국면
        phs = {}
        stand_mask = np.zeros(N, bool)
        for r in reps:
            ph = true_phases(V, r)
            if ph is None:
                continue
            phs[r.key] = ph
            d = ph["d"]
            m = np.zeros(N, bool)
            m[ph["lo"]:ph["hi"] + 1] = d[ph["lo"]:ph["hi"] + 1] <= 0.10
            stand_mask |= m
        reps = [r for r in reps if r.key in phs]
        all_reps += reps
        blocks = defaultdict(list)
        for r in reps:
            blocks[r.block].append(r)

        def block_masks():
            out = {}
            for bk, rs in blocks.items():
                lo, hi = max(0, min(r.a for r in rs) - 15), min(N - 1, max(r.b for r in rs) + 15)
                m = np.zeros(N, bool)
                m[lo:hi + 1] = stand_mask[lo:hi + 1]
                out[bk] = m
            return out
        bmask = block_masks()
        all_idx = np.arange(N)
        refs30 = {bk: block_refs(V, all_idx, m) for bk, m in bmask.items()}
        ref_vals = {}
        for r in reps:
            st = rep_stats(V, all_idx, r, refs30[r.block], smooth_vel=True)
            ref_vals[r.key] = st
            ph = phs[r.key]
            yaw = V["yaw"][r.a:r.b + 1]
            # 인물 바뀜 점검: 골반 중심의 프레임 간 최대 이동(÷ 다리 px), 검출률, 몸 크기(다리 px)가 블록 기준에서 벗어난 프레임 비율
            px, py = V["pel_x"][r.a:r.b + 1], V["pel_y"][r.a:r.b + 1]
            jump = np.hypot(np.diff(px), np.diff(py)) / refs30[r.block]["leg_px"]
            rep_info.append({"key": r.key, "dataset": r.dataset, "view": r.view, "person": r.person, "correct": int(r.correct),
                             "block": r.block, "extra_person": r.extra_person, "mocap_err": int(r.mocap_err),
                             "dur_ms": (r.b - r.a + 1) / FPS * 1000,
                             "yaw_med": float(np.nanmedian(yaw)) if np.isfinite(yaw).any() else np.nan,
                             "abs_yaw_med": float(np.nanmedian(np.abs(yaw))) if np.isfinite(yaw).any() else np.nan,
                             "amp_px": float(ph["A0"]), "leg_px": refs30[r.block]["leg_px"],
                             "drop_leg": float(ph["A0"] / refs30[r.block]["leg_px"]),
                             "max_jump_leg": float(np.nanmax(jump)) if np.isfinite(jump).any() else np.nan,
                             "det_rate": float(cap.f30.det[r.a:r.b + 1].mean())})
            ref_rows.append({"key": r.key, "view": r.view, "person": r.person, "correct": int(r.correct), **st})
            # 국면 지속시간과 간격별 표본 수
            pb = phase_bounds(ph)
            # 앱 정의 바닥: 무릎각(knee_mean, 5프레임 평활) ≤ 창 최소 + (최대 − 최소)/3 — 연속이 아닐 수 있어 마스크로 센다
            km = smooth(V["knee_mean"], 5)[r.a:r.b + 1]
            kmask = np.zeros(N, bool)
            if np.isfinite(km).sum() > 5:
                kmin, kmax = np.nanmin(km), np.nanmax(km)
                kmask[r.a:r.b + 1] = km <= kmin + (kmax - kmin) / 3.0
            move_ms = (ph["a1"] - ph["d0"]) / FPS * 1000
            rep_info[-1]["move_ms"] = move_ms
            rep_info[-1]["bottom_knee_ms"] = kmask.sum() / FPS * 1000
            tbin = tempo_bin(move_ms)
            rep_tempo[r.key] = tbin
            KB = "바닥(앱: 무릎각 진폭 1/3)"
            for gk in (r.view, r.dataset, "all"):
                phase_dur[(gk, KB)].append(kmask.sum() / FPS * 1000)
            phase_dur[(tbin, "반복 동작(하강 시작→복귀)")].append(move_ms)
            phase_dur[("all", "반복 동작(하강 시작→복귀)")].append(move_ms)
            phase_dur[(r.dataset, "반복 동작(하강 시작→복귀)")].append(move_ms)
            for I in INTERVALS:
                st_ = STRIDE[I]
                for phi in range(int(np.ceil(st_))):
                    g = grid(N, st_, phi)
                    c_ = int(kmask[g].sum())
                    phase_cnt[(KB, I)].append(c_)
                    phase_cnt[(KB, I, r.dataset)].append(c_)
                    phase_cnt[(KB, I, tbin)].append(c_)
                    for pname2 in ("바닥(앱 정의 d≥2/3)", "바닥→상승1/3 창", "하강"):
                        s2, e2 = pb[pname2]
                        phase_cnt[(pname2, I, tbin)].append(int(((g >= s2) & (g < e2)).sum()))
            for pname, (s_i, e_i) in pb.items():
                phase_dur[(r.view, pname)].append((e_i - s_i) / FPS * 1000)
                phase_dur[(r.dataset, pname)].append((e_i - s_i) / FPS * 1000)
                phase_dur[("all", pname)].append((e_i - s_i) / FPS * 1000)
                for I in INTERVALS:
                    st_ = STRIDE[I]
                    for phi in range(int(np.ceil(st_))):
                        g = grid(N, st_, phi)
                        c_ = int(((g >= s_i) & (g < e_i)).sum())
                        phase_cnt[(pname, I)].append(c_)
                        phase_cnt[(pname, I, r.dataset)].append(c_)
            for tag, (s_i, e_i) in (("반복 전체(창)", (r.a, r.b + 1)),):
                phase_dur[(r.view, tag)].append((e_i - s_i) / FPS * 1000)
                phase_dur[("all", tag)].append((e_i - s_i) / FPS * 1000)

        # 서브샘플 (30 fps 에서 솎아내기, 모든 위상)
        sub0 = {}
        for I in INTERVALS:
            st_ = STRIDE[I]
            for phi in range(int(np.ceil(st_))):
                sel = grid(N, st_, phi)
                refs = {bk: block_refs(V, sel, m) for bk, m in bmask.items()}
                for r in reps:
                    sv = rep_stats(V, sel, r, refs[r.block])
                    if phi == 0:
                        sub0[(r.key, I)] = sv
                    rv = ref_vals[r.key]
                    for sid in STAT_IDS:
                        if not np.isfinite(rv[sid]):
                            continue
                        fail = not np.isfinite(sv[sid])
                        sub_err[(sid, I)].append((r.key, r.view, phi, np.nan if fail else sv[sid] - rv[sid], fail))
        # 진짜 앱 주기 스트림(위상 0) — 같은 프레임 솎아내기와 비교
        for I, cs in cap.cad.items():
            if I not in STRIDE:
                continue
            idx = np.searchsorted(cap.f30.frames, cs.frames)
            assert np.all(cap.f30.frames[idx] == cs.frames)
            Vc = expand(frame_vars(cs, cap.wa, cap.ha), idx, N)
            refs = {bk: block_refs(Vc, idx, m) for bk, m in bmask.items()}
            for r in reps:
                cv = rep_stats(Vc, idx, r, refs[r.block])
                sv = sub0[(r.key, I)]
                rv = ref_vals[r.key]
                cad_rows.append({"key": r.key, "interval_ms": I, "view": r.view, **cv})
                for sid in STAT_IDS:
                    if not np.isfinite(rv[sid]):
                        continue
                    trk_err[(sid, I)].append((r.view, cv[sid] - sv[sid], cv[sid] - rv[sid], sv[sid] - rv[sid],
                                              not np.isfinite(cv[sid]), not np.isfinite(sv[sid]), r.key))
            if I == 300:
                # 랜드마크 차(같은 프레임): 이미지 px, 월드 cm — min(vis) ≥ 0.5 둘 다
                a_img = cap.f30.img[idx]
                c_img = cs.img
                okb = (np.fmin(a_img[..., 2], a_img[..., 3]) >= 0.5) & (np.fmin(c_img[..., 2], c_img[..., 3]) >= 0.5)
                okb &= cap.f30.det[idx][:, None] & cs.det[:, None]
                scale = np.array([cap.wa, cap.ha])
                for gname, js in (("엉덩이", [23, 24]), ("무릎", [25, 26]), ("발목", [27, 28]), ("뒤꿈치", [29, 30]),
                                  ("발끝", [31, 32]), ("어깨", [11, 12])):
                    for j in js:
                        m = okb[:, j]
                        dimg = np.linalg.norm((a_img[m, j, :2] - c_img[m, j, :2]) * scale, axis=-1)
                        dw = np.linalg.norm((cap.f30.wld[idx][m, j] - cs.wld[m, j]) * 100, axis=-1)
                        lm_diff[(cap.meta["dataset"], gname, "img_px")] += dimg.tolist()
                        lm_diff[(cap.meta["dataset"], gname, "wld_cm")] += dw.tolist()
                # 서 있을 때 떨림(진짜 300 ms): 연속한 c300 두 표본이 모두 서 있음일 때 차이
                Vc300 = Vc
                cst = stand_mask[idx]
                pair = cst[1:] & cst[:-1] & (np.diff(idx) <= 10)
                view_of = np.full(N, "", dtype=object)
                for r in reps:
                    view_of[max(0, r.a - 15):min(N, r.b + 16)] = r.view
                for var in JIT_VARS:
                    x = jit_series(Vc300, V, var, stand_mask)[idx]
                    dx = np.diff(x)[pair]
                    vv = view_of[idx][1:][pair]
                    for vname in set(vv):
                        if not vname:
                            continue
                        z = dx[(vv == vname) & np.isfinite(dx)]
                        jitter[vname][var].append(("c300", z))

        # 서 있을 때 떨림(30 fps): 연속 프레임 둘 다 서 있음
        pair = stand_mask[1:] & stand_mask[:-1]
        view_of = np.full(N, "", dtype=object)
        for r in reps:
            view_of[max(0, r.a - 15):min(N, r.b + 16)] = r.view
        for var in JIT_VARS:
            x = jit_series(V, V, var, stand_mask)
            dx = np.diff(x)[pair]
            vv = view_of[1:][pair]
            for vname in set(vv):
                if not vname:
                    continue
                z = dx[(vv == vname) & np.isfinite(dx)]
                jitter[vname][var].append(("f30", z))
            # 솎아낸 300 ms(위상 0)의 연속 표본 차 — 진짜 300 ms 와 비교
            g = grid(N, 9.0, 0)
            gp = stand_mask[g][1:] & stand_mask[g][:-1]
            dxs = np.diff(x[g])[gp]
            vs = view_of[g][1:][gp]
            for vname in set(vs):
                if not vname:
                    continue
                z = dxs[(vs == vname) & np.isfinite(dxs)]
                jitter[vname][var].append(("s300", z))

        # 가시성 · 좌우 뒤바뀜 (반복 창 프레임, 30 fps)
        vis = V["vis"]
        P = V["P"]
        for r in reps:
            sl = slice(r.a, r.b + 1)
            det = cap.f30.det[sl]
            for j, jn in ((27, "발목"), (28, "발목"), (29, "뒤꿈치"), (30, "뒤꿈치"), (31, "발끝"), (32, "발끝")):
                v = vis[sl, j]
                vis_rows[r.view][f"vis_ok_{jn}"].append(float(np.mean(np.nan_to_num(v, nan=0) >= 0.5)))
                vis_rows[r.view][f"vis_med_{jn}"].append(float(np.nanmedian(v)) if np.isfinite(v).any() else np.nan)
            vis_rows[r.view]["det"].append(float(det.mean()))
            ph = phs[r.key]
            bsl = slice(ph["app0"], ph["e1"])
            for j, jn in ((29, "뒤꿈치"), (30, "뒤꿈치"), (31, "발끝"), (32, "발끝")):
                v = vis[bsl, j]
                vis_rows[r.view][f"vis_ok_bottom_{jn}"].append(float(np.mean(np.nan_to_num(v, nan=0) >= 0.5)))
            # 좌우 뒤바뀜: 연속 프레임 배정 검사(무릎·발목 쌍, 이미지)
            ev, fr = 0, 0
            for lj, rj in ((25, 26), (27, 28)):
                L, R = P[sl, lj], P[sl, rj]
                same = np.linalg.norm(L[1:] - L[:-1], axis=-1) + np.linalg.norm(R[1:] - R[:-1], axis=-1)
                swap = np.linalg.norm(L[1:] - R[:-1], axis=-1) + np.linalg.norm(R[1:] - L[:-1], axis=-1)
                sep = np.linalg.norm(L[:-1] - R[:-1], axis=-1)
                m = np.isfinite(same) & np.isfinite(swap) & (sep > 8)
                ev += int(((swap < 0.5 * same) & m).sum())
                fr += int(m.sum())
            vis_rows[r.view]["swap_events_per_1000"].append(1000.0 * ev / max(fr, 1))
            y = V["yaw"][sl]
            dy = np.abs((np.diff(y) + 180) % 360 - 180)
            vis_rows[r.view]["yaw_flip_per_1000"].append(1000.0 * float(np.nansum(dy > 90)) / max(np.isfinite(dy).sum(), 1))
            # 정면 계열: 사람 왼쪽 엉덩이가 이미지 오른쪽이 아닌 프레임(라벨 거울상)
            lx, rx = P[sl, 23, 0], P[sl, 24, 0]
            m = np.isfinite(lx) & np.isfinite(rx)
            if m.any():
                maj = np.sign(np.median(lx[m] - rx[m]))
                vis_rows[r.view]["hip_order_minority_pct"].append(100.0 * float(np.mean(np.sign(lx[m] - rx[m]) != maj)))
                vis_rows[r.view]["hip_order_mirrored_rep"].append(float(maj < 0))
        print(f"[{time.time() - t0:5.0f}s] {name}: {len(reps)} reps", flush=True)

    OUT.mkdir(parents=True, exist_ok=True)
    # ---------------------------------------------------------------- 반복 목록
    write_csv(OUT / "reps.csv", rep_info)
    write_csv(OUT / "ref_stats.csv", ref_rows)
    refs_by = {r["key"]: r for r in ref_rows}
    info_by = {r["key"]: r for r in rep_info}

    # ---------------------------------------------------------------- 국면 지속시간 · 표본 수
    rows = []
    groups = ["all", "rehab", "mmfit"] + VIEWS
    pnames = [p[0] for p in PHASES] + ["바닥(앱 정의 d≥2/3)", "바닥(앱: 무릎각 진폭 1/3)", "바닥→상승1/3 창", "반복 전체(창)"]
    groups = groups + [b[2] for b in TEMPO_BINS]
    pnames_t = pnames + ["반복 동작(하강 시작→복귀)"]
    for gname in groups:
        for pn in pnames_t:
            v = phase_dur.get((gname, pn), [])
            if not v:
                continue
            rows.append({"group": gname, "phase": pn, "n": len(v), "p5_ms": q(v, 5), "p25_ms": q(v, 25), "p50_ms": q(v, 50),
                         "p75_ms": q(v, 75), "p95_ms": q(v, 95)})
    write_csv(OUT / "phase_durations.csv", rows)
    rows = []
    for pn in [x for x in pnames if x != "반복 전체(창)"]:
        for I in INTERVALS:
            for ds in ("all", "rehab", "mmfit") + tuple(b[2] for b in TEMPO_BINS):
                c = np.array(phase_cnt.get((pn, I) if ds == "all" else (pn, I, ds), []))
                if len(c) == 0:
                    continue
                rows.append({"phase": pn, "interval_ms": I, "dataset": ds, "n_rep_phase": len(c),
                             "median_samples": float(np.median(c)), "p10_samples": q(c, 10),
                             "fail0_pct": 100 * float(np.mean(c == 0)), "fail01_pct": 100 * float(np.mean(c <= 1))})
    write_csv(OUT / "phase_samples.csv", rows)
    phase_fail = {(r["phase"], r["interval_ms"]): r for r in rows if r["dataset"] == "all"}

    # ---------------------------------------------------------------- 서브샘플 오차
    # 규모 S: 정상 반복, 뷰마다 p95−p5, 뷰 중앙값(n≥10 인 뷰만)
    S = {}
    for sid in STAT_IDS:
        spans = []
        for vw in VIEWS:
            v = [refs_by[k][sid] for k in refs_by if info_by[k]["view"] == vw and info_by[k]["correct"] == 1]
            v = np.asarray(v, float)
            v = v[np.isfinite(v)]
            if len(v) >= 10:
                spans.append(np.percentile(v, 95) - np.percentile(v, 5))
        S[sid] = float(np.median(spans)) if spans else np.nan
    # 앱이 실제로 받는 한 샘플 잡음: 진짜 300 ms 주기(c300)에서 서 있을 때 연속 표본 차의 강건 σ ÷ √2 (뷰 합동)
    sig300 = {}
    for var in JIT_VARS:
        zs = [a for vw in VIEWS for kd, a in jitter[vw].get(var, []) if kd == "c300"]
        z = np.concatenate(zs) if zs else np.array([])
        sig300[var] = float(1.4826 * np.median(np.abs(z - np.median(z))) / np.sqrt(2)) if len(z) > 20 else np.nan
    rows, verdict = [], {}
    for sid, grp, desc, unit in STATS:
        per_I = {}
        for I in INTERVALS:
            e = sub_err.get((sid, I), [])
            if not e:
                continue
            errs = np.array([x[3] for x in e], float)
            fail = np.array([x[4] for x in e], bool)
            ae = np.abs(errs[np.isfinite(errs)])
            rel90 = (np.percentile(ae, 90) / S[sid]) if len(ae) and S[sid] > 0 else np.nan
            pf = stat_phase(sid)
            pfail = phase_fail.get((pf, I), {}).get("fail01_pct", np.nan) if pf else np.nan
            sig = sig300.get(STAT_JIT.get(sid, ""), np.nan)
            noise_ratio = float(np.percentile(ae, 90) / sig) if len(ae) and np.isfinite(sig) and sig > 0 else np.nan
            ok = (fail.mean() <= FAIL_OK) and (rel90 <= TOL_OK)
            ok_noise = (not ok) and (fail.mean() <= FAIL_OK) and np.isfinite(noise_ratio) and noise_ratio <= 1.0
            edge = (fail.mean() <= FAIL_EDGE) and (rel90 <= TOL_EDGE)
            per_I[I] = "OK" if ok else ("OK(잡음)" if ok_noise else ("경계" if edge else "부족"))
            rows.append({"stat": sid, "group": grp, "desc": desc, "unit": unit, "interval_ms": I, "n": len(e),
                         "n_reps": len({x[0] for x in e}), "fail_pct": 100 * float(fail.mean()),
                         "phase_fail01_pct": pfail, "bias_med": float(np.nanmedian(errs)) if len(ae) else np.nan,
                         "abs_p50": q(ae, 50), "abs_p90": q(ae, 90), "abs_p99": q(ae, 99), "S_p5p95": S[sid],
                         "rel90": float(rel90), "sigma_c300": sig, "err90_over_sigma": noise_ratio, "verdict": per_I[I]})
        # 최소 필요 간격: 작은 간격부터 연속으로 OK 인 가장 큰 간격
        best = None
        for I in INTERVALS:
            if per_I.get(I) in ("OK", "OK(잡음)"):
                best = I
            else:
                break
        verdict[sid] = {"desc": desc, "unit": unit, "at300": per_I.get(300), "at333": per_I.get(333),
                        "max_ok_ms": best, "per_interval": per_I, "S": S[sid]}
    write_csv(OUT / "subsample_errors.csv", rows)
    # 뷰별(300·333·200 ms)
    rows = []
    for sid in STAT_IDS:
        for I in (200, 300, 333):
            e = sub_err.get((sid, I), [])
            for vw in VIEWS:
                ev = [x for x in e if x[1] == vw]
                if not ev:
                    continue
                errs = np.array([x[3] for x in ev], float)
                fail = np.array([x[4] for x in ev], bool)
                ae = np.abs(errs[np.isfinite(errs)])
                rows.append({"stat": sid, "interval_ms": I, "view": vw, "n": len(ev), "fail_pct": 100 * float(fail.mean()),
                             "abs_p90": q(ae, 90), "rel90": q(ae, 90) / S[sid] if S[sid] else np.nan})
    write_csv(OUT / "subsample_by_view.csv", rows)

    # ---------------------------------------------------------------- 진짜 주기 효과
    rows = []
    for sid in STAT_IDS:
        for I in INTERVALS:
            e = trk_err.get((sid, I), [])
            if not e:
                continue
            d_trk = np.array([x[1] for x in e], float)
            e_c = np.array([x[2] for x in e], float)
            e_s = np.array([x[3] for x in e], float)
            fc = np.array([x[4] for x in e], bool)
            fs = np.array([x[5] for x in e], bool)
            rows.append({"stat": sid, "interval_ms": I, "n": len(e), "track_minus_sub_bias": float(np.nanmedian(d_trk)),
                         "track_minus_sub_abs_p50": q(np.abs(d_trk), 50), "track_minus_sub_abs_p90": q(np.abs(d_trk), 90),
                         "cad_err_abs_p90": q(np.abs(e_c), 90), "sub_err_abs_p90": q(np.abs(e_s), 90),
                         "cad_rel90": q(np.abs(e_c), 90) / S[sid] if S[sid] else np.nan,
                         "sub_rel90": q(np.abs(e_s), 90) / S[sid] if S[sid] else np.nan,
                         "cad_fail_pct": 100 * float(fc.mean()), "sub_fail_pct": 100 * float(fs.mean())})
    write_csv(OUT / "tracker_effect.csv", rows)
    write_csv(OUT / "cad_stats.csv", cad_rows)
    # 템포(동작 시간)별: 진짜 주기 오차·실패, 솎아내기 오차·실패
    rows = []
    for sid in STAT_IDS:
        for I in INTERVALS:
            e = trk_err.get((sid, I), [])
            se = sub_err.get((sid, I), [])
            for lo, hi, tb in TEMPO_BINS:
                ec = [x for x in e if rep_tempo.get(x[6]) == tb]
                es = [x for x in se if rep_tempo.get(x[0]) == tb]
                if not ec:
                    continue
                e_c = np.array([x[2] for x in ec], float)
                e_s = np.array([x[3] for x in es], float)
                rows.append({"stat": sid, "interval_ms": I, "tempo": tb, "n_reps": len(ec),
                             "cad_fail_pct": 100 * float(np.mean([x[4] for x in ec])),
                             "cad_rel90": q(np.abs(e_c), 90) / S[sid] if S[sid] else np.nan,
                             "sub_fail_pct": 100 * float(np.mean([x[4] for x in es])) if es else np.nan,
                             "sub_rel90": q(np.abs(e_s), 90) / S[sid] if S[sid] else np.nan})
    write_csv(OUT / "by_tempo.csv", rows)
    # 두 카메라(cam17·cam18)가 같은 반복을 동시에 찍었다 — 시점 불변 통계의 카메라 간 상관이 주기에 따라 얼마나 떨어지는가(정답 없는 재현성)
    from scipy.stats import spearmanr  # noqa: PLC0415
    cad_by = defaultdict(dict)
    for cr in cad_rows:
        cad_by[cr["interval_ms"]][cr["key"]] = cr
    rows = []
    for sid in STAT_IDS:
        for kind in ["f30"] + INTERVALS:
            src = refs_by if kind == "f30" else cad_by.get(kind, {})
            pairs = []
            for k, rr in src.items():
                if "_17#" not in k:
                    continue
                k2 = k.replace("_17#", "_18#")
                if k2 in src and np.isfinite(rr[sid]) and np.isfinite(src[k2][sid]):
                    pairs.append((rr[sid], src[k2][sid]))
            if len(pairs) < 20:
                continue
            a_, b_ = np.array(pairs).T
            rows.append({"stat": sid, "stream": str(kind), "n": len(pairs), "pearson": float(np.corrcoef(a_, b_)[0, 1]),
                         "spearman": float(spearmanr(a_, b_).statistic)})
    write_csv(OUT / "cross_camera.csv", rows)
    rows = []
    for (ds, gname, space), v in sorted(lm_diff.items()):
        rows.append({"dataset": ds, "joint": gname, "space": space, "n": len(v), "p50": q(v, 50), "p90": q(v, 90), "p99": q(v, 99)})
    write_csv(OUT / "landmark_diff.csv", rows)

    # ---------------------------------------------------------------- 뷰별 분포(정상 반복)
    rows = []
    for vw in VIEWS:
        keys = [k for k in refs_by if info_by[k]["view"] == vw and info_by[k]["correct"] == 1]
        for sid in STAT_IDS:
            v = np.asarray([refs_by[k][sid] for k in keys], float)
            nv = int(np.isfinite(v).sum())
            rows.append({"view": vw, "stat": sid, "n": nv, "n_reps": len(keys), "p5": q(v, 5), "p50": q(v, 50), "p95": q(v, 95)})
        for k2 in ("abs_yaw_med", "dur_ms"):
            v = [info_by[k][k2] for k in keys]
            rows.append({"view": vw, "stat": k2, "n": len(v), "n_reps": len(keys), "p5": q(v, 5), "p50": q(v, 50), "p95": q(v, 95)})
    write_csv(OUT / "view_distributions.csv", rows)
    rows = []
    for vw in VIEWS:
        for var, kinds in jitter[vw].items():
            rec = {"view": vw, "var": var}
            for kind in ("f30", "s300", "c300"):
                z = np.concatenate([a for kd, a in kinds if kd == kind]) if any(kd == kind for kd, _ in kinds) else np.array([])
                rec[f"{kind}_n"] = int(len(z))
                # 프레임 간 차의 강건 표준편차(1.4826·MAD) ÷ √2 = 프레임당 잡음
                rec[f"{kind}_sigma"] = float(1.4826 * np.median(np.abs(z - np.median(z))) / np.sqrt(2)) if len(z) > 10 else np.nan
                rec[f"{kind}_sd"] = float(np.std(z) / np.sqrt(2)) if len(z) > 10 else np.nan
            rows.append(rec)
    write_csv(OUT / "view_jitter.csv", rows)
    rows = []
    for vw in VIEWS:
        rec = {"view": vw, "n_reps": len(vis_rows[vw].get("det", []))}
        for k, v in vis_rows[vw].items():
            rec[k] = float(np.nanmean(v)) if len(v) else np.nan
        rows.append(rec)
    write_csv(OUT / "view_visibility.csv", rows)

    # ---------------------------------------------------------------- AUC (REHAB)
    rows = []
    for vw in [v for v in VIEWS if v.startswith("rehab")]:
        keys = [k for k in refs_by if info_by[k]["view"] == vw]
        persons = sorted({info_by[k]["person"] for k in keys})
        for sid in STAT_IDS:
            pos = [refs_by[k][sid] for k in keys if info_by[k]["correct"] == 0]
            neg = [refs_by[k][sid] for k in keys if info_by[k]["correct"] == 1]
            a, npos, nneg = auc(pos, neg)
            wa, ws, dirs, dvals = 0.0, 0.0, [], []
            for pp in persons:
                pk = [k for k in keys if info_by[k]["person"] == pp]
                ap, bp, cp = auc([refs_by[k][sid] for k in pk if info_by[k]["correct"] == 0],
                                 [refs_by[k][sid] for k in pk if info_by[k]["correct"] == 1])
                if np.isfinite(ap) and bp >= 2 and cp >= 2:
                    wa += ap * bp * cp
                    ws += bp * cp
                    dirs.append(ap)
                    e1 = np.asarray([refs_by[k][sid] for k in pk if info_by[k]["correct"] == 0], float)
                    e0 = np.asarray([refs_by[k][sid] for k in pk if info_by[k]["correct"] == 1], float)
                    e1, e0 = e1[np.isfinite(e1)], e0[np.isfinite(e0)]
                    sd = np.sqrt((np.var(e1, ddof=1) + np.var(e0, ddof=1)) / 2) if len(e1) > 1 and len(e0) > 1 else np.nan
                    if sd and np.isfinite(sd) and sd > 0:
                        dvals.append((np.mean(e1) - np.mean(e0)) / sd)
            rows.append({"view": vw, "stat": sid, "auc_pooled": a, "n_err": npos, "n_norm": nneg,
                         "auc_within": wa / ws if ws else np.nan, "persons": len(dirs),
                         "persons_auc_gt_0.5": int(sum(x > 0.5 for x in dirs)),
                         "persons_auc_lt_0.5": int(sum(x < 0.5 for x in dirs)),
                         "cohen_d_median": float(np.median(dvals)) if dvals else np.nan})
    write_csv(OUT / "auc.csv", rows)

    summary = {"captures": names, "n_reps": len(all_reps),
               "reps_by_view": {vw: sum(1 for r in all_reps if r.view == vw) for vw in VIEWS},
               "errors_by_view": {vw: sum(1 for r in all_reps if r.view == vw and not r.correct) for vw in VIEWS},
               "criteria": {"ok": f"실패 ≤ {FAIL_OK:.0%} 이고 p90|오차| ≤ {TOL_OK:.2f}·S", "edge": f"실패 ≤ {FAIL_EDGE:.0%} 이고 ≤ {TOL_EDGE:.2f}·S",
                            "S": "정상 반복 30 fps 통계의 뷰별 p95−p5 의 중앙값"},
               "verdict": verdict, "elapsed_s": round(time.time() - t0, 1)}
    (OUT / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"done in {time.time() - t0:.0f}s", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
