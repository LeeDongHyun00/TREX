# -*- coding: utf-8 -*-
"""B4 공용 — 덤벨 컬 정면(C) 뷰의 2D 단서(옆 벌림·반동·손목 높이·전완 단축)와 반복 통계.

정의(폰 분석과 같게, 좌표는 픽셀 — 폰 = 정규화 × (480, 640), AIHub = 원본 px, MM-Fit = 640 × 360)
    lat        사람 기준 바깥 방향 팔꿈치 가로 오프셋 = side · lat_sign · (팔꿈치x − 어깨x) ÷ |어깨 x 간격|
               lat_sign = sign(왼골반x − 오른골반x) (정면 비미러 = 사람 왼쪽이 화면 +x), side: 왼 +1 · 오른 −1
    rise       팔꿈치 높이 = (어깨y − 팔꿈치y) ÷ 몸통 길이(어깨 중점–골반 중점, 2D)  — 위 +
    wrist_h    손목 높이 = (어깨y − 손목y) ÷ 몸통
    ua_len     상완 2D 길이 ÷ 몸통(앞으로 내밀면 짧아짐), fa_ua = 전완 2D 길이 ÷ 상완 2D 길이(수축 단축)
    ua_out     상완의 화면 수직(아래)에서 바깥으로 벗어난 각(°)
    elbow_gap  팔꿈치 간격 ÷ 어깨 간격(2D)
반복 통계
    _c    사이클 창 안 팔꿈치각(월드) 최소 프레임 ± 1 (창 안 ≤ 3프레임) 의 중앙값     ← "수축"
    _c1   최소 프레임 한 장
    _max  창 안 최대, _min 창 안 최소
    start 첫 하강 전 서 있음(그 팔 팔꿈치 ≥ REST_ANGLE) 프레임 중앙값                 ← "시작"
    _d    _c − start (수축 − 시작 변화), _dmax = _max − start
"""
from __future__ import annotations

import csv
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
OUT = HERE / "outputs" / "B4"
LS, RS, LE, RE, LW, RW, LH, RH = 11, 12, 13, 14, 15, 16, 23, 24
SIDES = ("L", "R")
KEYS = ["lat", "rise", "wrist_h", "ua_len", "fa_ua", "fa_len", "ua_out", "wrist_lat"]      # 팔별
KEYS_COMMON = ["elbow_gap", "elbow_gap_x", "sh_wx_torso"]
REST_ANGLE = 140.0

# 띠 후보 — 반복 단위(두 팔 중 하나라도 넘으면 초과)
FLARE_BANDS = [(d, a) for d in (0.10, 0.12, 0.15, 0.20) for a in (0.0, 0.25, 0.28, 0.30, 0.35)]   # (시작 대비 Δ, 절대 하한; 0 = 절대 조건 없음)
FLARE_ABS = [0.25, 0.28, 0.30, 0.35, 0.40]
SWING_D = [0.08, 0.10, 0.12, 0.15, 0.20]
SWING_ABS = [-0.40, -0.35, -0.30, -0.25]
WRIST_ABOVE = [-0.10, -0.05, 0.0, 0.10, 0.15, 0.20]


def _norm(v):
    return np.linalg.norm(v, axis=-1)


def feat2d(P: np.ndarray) -> dict[str, np.ndarray]:
    """P (..., 33, 2) px, 안 보이는 관절은 NaN. 반환: (...,) 값 사전."""
    g = lambda i: P[..., i, :]  # noqa: E731
    ls, rs, le, re, lw, rw, lh, rh = (g(i) for i in (LS, RS, LE, RE, LW, RW, LH, RH))
    with np.errstate(all="ignore"):
        lat = np.sign(lh[..., 0] - rh[..., 0])
        lat = np.where(lat == 0, np.nan, lat)
        sh_wx = np.abs(ls[..., 0] - rs[..., 0])
        sh_wx = np.where(sh_wx < 5, np.nan, sh_wx)
        shm, hm = (ls + rs) / 2, (lh + rh) / 2
        torso = _norm(shm - hm)
        torso = np.where(torso < 10, np.nan, torso)
        F: dict[str, np.ndarray] = {}
        for s, S, E, W, sg in (("L", ls, le, lw, 1.0), ("R", rs, re, rw, -1.0)):
            dx = sg * lat * (E[..., 0] - S[..., 0])
            F[f"lat_{s}"] = dx / sh_wx
            F[f"rise_{s}"] = (S[..., 1] - E[..., 1]) / torso
            F[f"wrist_h_{s}"] = (S[..., 1] - W[..., 1]) / torso
            ua, fa = _norm(E - S), _norm(W - E)
            F[f"ua_len_{s}"] = ua / torso
            F[f"fa_len_{s}"] = fa / torso
            F[f"fa_ua_{s}"] = fa / np.where(ua < 3, np.nan, ua)
            F[f"ua_out_{s}"] = np.degrees(np.arctan2(dx, E[..., 1] - S[..., 1]))
            F[f"wrist_lat_{s}"] = sg * lat * (W[..., 0] - S[..., 0]) / sh_wx
        F["elbow_gap"] = _norm(le - re) / np.where(_norm(ls - rs) < 5, np.nan, _norm(ls - rs))
        F["elbow_gap_x"] = np.abs(le[..., 0] - re[..., 0]) / sh_wx
        F["sh_wx_torso"] = sh_wx / torso
        F["lat_sign"] = lat
        F["torso_px"] = torso
    return F


def contraction_idx(elbow: np.ndarray, win: np.ndarray, need: int = 3) -> int:
    """창 안 팔꽘치각 최소 인덱스(−1 = 표본 부족)."""
    x = np.where(win, elbow, np.nan)
    if np.isfinite(x).sum() < need:
        return -1
    return int(np.nanargmin(x))


def rep_stats(F: dict, bases: list[str], side: str, win: np.ndarray, i_min: int, start: dict | None = None) -> dict[str, float]:
    """한 팔(side) 한 사이클: F 의 '{base}_{side}' 에서 '{base}_{stat}_{side}' 를 낸다.
    stat: c(최소 ± 1 중앙값) c1(최소 프레임) max min, start 가 있으면 s(시작값) d(c − s) dmax(max − s). side='' 면 공통 변수(접미사 없음)."""
    out: dict[str, float] = {}
    if i_min < 0:
        return out
    n = len(win)
    idx = [i for i in (i_min - 1, i_min, i_min + 1) if 0 <= i < n and win[i]]
    suf = f"_{side}" if side else ""
    for b in bases:
        x = F[f"{b}{suf}"]
        v3 = x[idx]
        xw = np.where(win, x, np.nan)
        out[f"{b}_c{suf}"] = float(np.nanmedian(v3)) if np.isfinite(v3).any() else np.nan
        out[f"{b}_c1{suf}"] = float(x[i_min])
        out[f"{b}_max{suf}"] = float(np.nanmax(xw)) if np.isfinite(xw).any() else np.nan
        out[f"{b}_min{suf}"] = float(np.nanmin(xw)) if np.isfinite(xw).any() else np.nan
        if start is not None:
            s0 = start.get(b, np.nan)
            out[f"{b}_s{suf}"] = s0
            out[f"{b}_d{suf}"] = out[f"{b}_c{suf}"] - s0
            out[f"{b}_dmax{suf}"] = out[f"{b}_max{suf}"] - s0
    out[f"n_c3{suf}"] = len(idx)
    return out


def start_values(F: dict, bases: list[str], side: str, mask: np.ndarray) -> tuple[dict[str, float], int]:
    """서 있음 프레임(mask) 중앙값 — {base: 값}."""
    out = {}
    suf = f"_{side}" if side else ""
    for b in bases:
        x = F[f"{b}{suf}"][mask]
        x = x[np.isfinite(x)]
        out[b] = float(np.median(x)) if len(x) else np.nan
    return out, int(mask.sum())


def pct(x, q=(5, 50, 95, 99)) -> list[float]:
    x = np.asarray(x, float)
    x = x[np.isfinite(x)]
    if len(x) == 0:
        return [np.nan] * len(q)
    return [float(v) for v in np.percentile(x, q)]


def fmt(v, nd=2):
    if v is None or (isinstance(v, float) and not np.isfinite(v)):
        return "—"
    return f"{v:.{nd}f}"


def write_csv(path: Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    keys: list[str] = []
    for r in rows:
        for k in r:
            if k not in keys:
                keys.append(k)
    with path.open("w", encoding="utf-8-sig", newline="") as h:
        wr = csv.DictWriter(h, fieldnames=keys)
        wr.writeheader()
        for r in rows:
            wr.writerow(r)


def md_table(headers, rows) -> str:
    out = ["| " + " | ".join(str(h) for h in headers) + " |", "|" + "|".join("---" for _ in headers) + "|"]
    for r in rows:
        out.append("| " + " | ".join(str(x) for x in r) + " |")
    return "\n".join(out)


# ------------------------------------------------------------------------------------------ 띠 평가
def rep_level(rows: list[dict], key: str, how: str = "max") -> np.ndarray:
    """반복(사이클) 행 목록에서 팔별 값 key_L/key_R 을 반복 값으로(두 팔 중 max/min/nanmean). 한 팔만 있으면 그 팔."""
    a = np.array([r.get(f"{key}_L", np.nan) for r in rows], float)
    b = np.array([r.get(f"{key}_R", np.nan) for r in rows], float)
    with np.errstate(all="ignore"):
        if how == "max":
            return np.fmax(a, b)
        if how == "min":
            return np.fmin(a, b)
        return np.nanmean(np.stack([a, b]), 0)


def band_rates(rows: list[dict], group_key: str | None = None) -> list[dict]:
    """정상으로 가정한 반복 행에서 띠별 초과율(반복 단위, 두 팔 중 큰 값). group_key 가 있으면 그룹별 초과율도."""
    out = []
    lat_c, lat_d, lat_max, lat_dmax = (rep_level(rows, f"lat_{st}") for st in ("c", "d", "max", "dmax"))
    rise_c, rise_d, rise_max, rise_dmax = (rep_level(rows, f"rise_{st}") for st in ("c", "d", "max", "dmax"))
    wrist_max = rep_level(rows, "wrist_h_max")
    groups = np.array([r.get(group_key, "") for r in rows]) if group_key else None

    def add(name, flag, kind):
        ok = np.isfinite(flag.astype(float))
        row = {"band": name, "kind": kind, "n": int(ok.sum()), "rate": float(np.mean(flag[ok])) if ok.any() else np.nan}
        if groups is not None:
            per = {}
            for gname in sorted(set(groups)):
                m = ok & (groups == gname)
                if m.sum() >= 3:
                    per[gname] = float(np.mean(flag[m]))
            row["group_max"] = max(per.values()) if per else np.nan
            row["group_argmax"] = max(per, key=per.get) if per else ""
            row["n_groups"] = len(per)
            row["groups_over_5pct"] = sum(1 for v in per.values() if v > 0.05)
        out.append(row)

    with np.errstate(all="ignore"):
        for d, a in FLARE_BANDS:
            add(f"flare: lat_c−start ≥ {d:.2f} & lat_c ≥ {a:.2f}", (lat_d >= d) & (lat_c >= a), "flare_c")
            add(f"flare: lat_max−start ≥ {d:.2f} & lat_max ≥ {a:.2f}", (lat_dmax >= d) & (lat_max >= a), "flare_max")
        for a in FLARE_ABS:
            add(f"flare: lat_c ≥ {a:.2f} (절대만)", lat_c >= a, "flare_abs")
            add(f"flare: lat_max ≥ {a:.2f} (절대만)", lat_max >= a, "flare_abs_max")
        for d in SWING_D:
            add(f"swing: rise_c−start ≥ {d:.2f}", rise_d >= d, "swing_c")
            add(f"swing: rise_max−start ≥ {d:.2f}", rise_dmax >= d, "swing_max")
        for a in SWING_ABS:
            add(f"swing: rise_c ≥ {a:.2f} (절대만)", rise_c >= a, "swing_abs")
            add(f"swing: rise_max ≥ {a:.2f} (절대만)", rise_max >= a, "swing_abs_max")
        for a in WRIST_ABOVE:
            add(f"swing: wrist_h_max ≥ {a:.2f} (손목이 어깨 근처)", wrist_max >= a, "wrist_above")
        for d in (0.10, 0.15, 0.20):
            for a in (-0.35, -0.30, -0.25):
                add(f"swing: rise_max−start ≥ {d:.2f} & rise_max ≥ {a:.2f}", (rise_dmax >= d) & (rise_max >= a), "swing_combo")
        for d in (0.15, 0.20):
            for a in (0.28, 0.30, 0.35):
                add(f"flare: lat_max−start ≥ {d:.2f} & lat_max ≥ {a:.2f} & lat_c ≥ {a - 0.05:.2f}", (lat_dmax >= d) & (lat_max >= a) & (lat_c >= a - 0.05), "flare_combo")
    return out


def start_mask_window(t: np.ndarray, elbow: np.ndarray, det: np.ndarray, frontal: np.ndarray, t_lo: int, t_hi: int, angle: float = REST_ANGLE) -> np.ndarray:
    """시작(개인 기준) 프레임: [t_lo, t_hi] 안 · 검출 · 정면(어깨 x 간격 ÷ 몸통 ≥ 0.35) · 그 팔 팔꿈치 ≥ angle."""
    return (t >= t_lo) & (t <= t_hi) & det & frontal & (elbow >= angle)


def dist_rows(rows: list[dict], keys: list[str], group_key: str, label: str) -> list[dict]:
    """반복 단위(두 팔 중 큰 값)와 팔 단위의 p5/50/95/99, 그룹별 p95 의 분포."""
    out = []
    groups = np.array([r.get(group_key, "") for r in rows])
    for k in keys:
        for unit in ("rep_max", "arm"):
            if unit == "rep_max":
                v = rep_level(rows, k)
                g = groups
            else:
                v = np.concatenate([np.array([r.get(f"{k}_L", np.nan) for r in rows], float), np.array([r.get(f"{k}_R", np.nan) for r in rows], float)])
                g = np.concatenate([groups, groups])
            ok = np.isfinite(v)
            p = pct(v[ok])
            gp95 = []
            for gname in sorted(set(g)):
                m = ok & (g == gname)
                if m.sum() >= 3:
                    gp95.append(float(np.percentile(v[m], 95)))
            out.append({"source": label, "cand": k, "unit": unit, "n": int(ok.sum()), "p5": p[0], "p50": p[1], "p95": p[2], "p99": p[3],
                        "mean": float(np.mean(v[ok])) if ok.any() else np.nan, "sd": float(np.std(v[ok])) if ok.any() else np.nan,
                        "n_groups": len(gp95), "group_p95_min": min(gp95) if gp95 else np.nan, "group_p95_med": float(np.median(gp95)) if gp95 else np.nan,
                        "group_p95_max": max(gp95) if gp95 else np.nan})
    return out
