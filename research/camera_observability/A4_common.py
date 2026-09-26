# -*- coding: utf-8 -*-
"""A4 공용 — 실제 폰 세트 로그(trex.posture.setlog/1)를 읽고, 이미지 2D·월드 3D 좌표로 변수를 계산한다.

좌표 규약 (앱과 같게, `PostureAnalyzer.kt`·`PostureOrientation.kt`·`PostureCore.kt`)
    xy   정규화 이미지 좌표(회전 보정 후, 세로 480×640). 전면 카메라 분석 프레임은 **미러가 아니다** → 정면을 보는 사람의 왼쪽이 화면 오른쪽.
    w    MediaPipe 월드 원값(m, x 오른쪽·y 아래·z 카메라에서 멀어짐, 골반 중점 원점).
         앱처럼 P = (x, −y, −z)·100 cm 로 바꾼다 → X 화면 오른쪽, Y 위, Z 카메라 쪽.
    up   그 프레임 피처에 쓴 up 벡터(같은 X·Y·Z 좌표계, IMU 중력 반대 방향). 높이 = P·up.
2D 는 픽셀 등방 좌표(x·480, y·640, y 아래)에서 up 을 화면에 투영한 방향 u2 = (up_x, −up_y) 로 높이를 잰다.

월드 좌표는 **골반 중점 원점**이라 '바닥에 대한' 절대 위치(골반 높이·발 이동)는 3D 로 직접 못 잰다 — 그런 변수는
몸 안의 다른 점(발목·발끝)을 기준으로 삼거나 2D(카메라 고정) 로만 잰다. 변수 정의는 `A4_4_variables.py` 머리말.
"""
from __future__ import annotations

import glob
import json
import math
import os
import re
import sys
from dataclasses import dataclass, field

import numpy as np

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
PHONE = os.path.join(ROOT, 'data', 'phone')
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'outputs', 'A4')

MAIN_SET = '20260925T031938-6e1e8e89'   # 12:19 KST, 좌표 있는 유일한 스쿼트 세트(10회)
PRE_SET = '20260925T031804-af05ec69'    # 12:18 KST, 직전 18프레임 시작 세트(좌표 있음, 0회)
BACK_SET = '20260923T053858-a4ef77b6'   # 09-23 후면 카메라 스쿼트(591프레임, 0회)
SET_1137 = '20260925T023707-5155c5dd'   # 11:37 KST, 사용자 정답 있는 피처 전용 세트(10회)

# 12:19 세트 사용자 정답(반복 순서)
TRUTH_1219 = ['정상', '정상', '발끝안', '발끝안', '발끝밖', '발끝밖', '넓게', '넓게', '정상끝', '정상끝']
# 11:37 세트 사용자 정답(설계 §21.8)
TRUTH_1137 = ['정상', '정상', '넓게+발끝밖', '넓게+발끝밖', '발끝밖', '발끝밖', '발끝안', '발끝안', '넓게(잔류)', '넓게(잔류)']

IMG_W, IMG_H = 480, 640
MIN_VIS = 0.5  # 앱 MIN_VISIBILITY

LM = dict(nose=0, l_sh=11, r_sh=12, l_el=13, r_el=14, l_wr=15, r_wr=16, l_hip=23, r_hip=24,
          l_knee=25, r_knee=26, l_ank=27, r_ank=28, l_heel=29, r_heel=30, l_toe=31, r_toe=32)
LM_NAME = {v: k for k, v in LM.items()}
LOWER = [11, 12, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32]

_ID_RE = re.compile(r'"set_id":"([^"]+)"')


def out_path(name: str) -> str:
    os.makedirs(OUT, exist_ok=True)
    return os.path.join(OUT, name)


def utf8_stdout():
    try:
        sys.stdout.reconfigure(encoding='utf-8')
    except Exception:
        pass


def load_sets(ids: set[str] | None = None, pattern: str = 'sets-2026092*.jsonl') -> dict[str, dict]:
    """data/phone/*/ 의 같은 set_id 는 한 번만(처음 본 파일). ids 를 주면 그 세트만 파싱한다."""
    seen: dict[str, dict] = {}
    for path in sorted(glob.glob(os.path.join(PHONE, '*', pattern))):
        with open(path, encoding='utf-8') as f:
            for line in f:
                if not line.strip():
                    continue
                m = _ID_RE.search(line[:400])
                sid = m.group(1) if m else None
                if sid is None or sid in seen or (ids is not None and sid not in ids):
                    continue
                d = json.loads(line)
                d['_path'] = os.path.relpath(path, ROOT)
                seen[d['set_id']] = d
    return seen


def kst(set_id: str) -> str:
    """set_id 앞 UTC 시각 → KST HH:MM."""
    hh = int(set_id[9:11]); mm = set_id[11:13]
    return f'{(hh + 9) % 24:02d}:{mm}'


def _arr(v, shape):
    if v is None:
        return None
    a = np.array([np.nan if x is None else x for x in v], float)
    return a.reshape(shape)


@dataclass
class SetFrames:
    set_id: str
    header: dict
    t: np.ndarray            # s
    infer: np.ndarray        # ms
    visible: np.ndarray
    vis: np.ndarray          # (n,33) NaN = 없음
    xy: np.ndarray           # (n,33,2) 픽셀(등방), NaN = 없음
    P: np.ndarray            # (n,33,3) cm, X 오른쪽·Y 위·Z 카메라 쪽
    up: np.ndarray           # (n,3)
    feats: list = field(default_factory=list)

    @property
    def n(self):
        return len(self.t)

    def feat(self, key: str) -> np.ndarray:
        return np.array([f.get(key, np.nan) if f.get(key) is not None else np.nan for f in self.feats], float)


def frames_of(d: dict) -> SetFrames:
    fr = d['frames']
    n = len(fr)
    vis = np.full((n, 33), np.nan); xy = np.full((n, 33, 2), np.nan)
    P = np.full((n, 33, 3), np.nan); up = np.full((n, 3), np.nan)
    img = d.get('image') or {'w': IMG_W, 'h': IMG_H}
    W, H = img.get('w', IMG_W), img.get('h', IMG_H)
    for i, f in enumerate(fr):
        v = _arr(f.get('vis'), (33,))
        if v is not None:
            vis[i] = v
        a = _arr(f.get('xy'), (33, 2))
        if a is not None:
            xy[i, :, 0] = a[:, 0] * W; xy[i, :, 1] = a[:, 1] * H
        w = _arr(f.get('w'), (33, 3))
        if w is not None:
            P[i, :, 0] = w[:, 0] * 100; P[i, :, 1] = -w[:, 1] * 100; P[i, :, 2] = -w[:, 2] * 100
        u = f.get('up')
        if u is not None:
            up[i] = u
    return SetFrames(
        set_id=d['set_id'], header={k: v for k, v in d.items() if k not in ('frames',)},
        t=np.array([f['t_ms'] for f in fr], float) / 1000.0,
        infer=np.array([f['infer_ms'] for f in fr], float),
        visible=np.array([f.get('visible', 0) for f in fr], float),
        vis=vis, xy=xy, P=P, up=up, feats=[f.get('features') or {} for f in fr])


# ---------------------------------------------------------------- 기하 도구

def unit(v, axis=-1):
    n = np.linalg.norm(v, axis=axis, keepdims=True)
    with np.errstate(invalid='ignore', divide='ignore'):
        return v / n


def deg(x):
    return np.degrees(x)


def angle3(a, b, c):
    """b 꼭짓점 각(도). 마지막 축이 좌표. 2D·3D 공용."""
    u = unit(a - b); w = unit(c - b)
    return deg(np.arccos(np.clip(np.sum(u * w, -1), -1, 1)))


class Geo:
    """한 세트의 프레임별 2D·3D 좌표에 높이·수평 연산을 붙인다."""

    def __init__(self, sf: SetFrames):
        self.sf = sf
        up = sf.up.copy()
        # up 이 없는 프레임은 앞뒤 값으로 채운다(좌표 있는 세트는 전부 있다)
        good = np.all(np.isfinite(up), 1)
        if not good.all() and good.any():
            idx = np.where(good)[0]
            for i in np.where(~good)[0]:
                up[i] = up[idx[np.argmin(np.abs(idx - i))]]
        up = unit(up)
        self.up3 = up                                           # (n,3)
        u2 = np.stack([up[:, 0], -up[:, 1]], 1)                  # 화면(픽셀, y 아래)에서 위 방향
        self.u2 = unit(u2)
        self.r2 = np.stack([-self.u2[:, 1], self.u2[:, 0]], 1)   # 화면 오른쪽(중력 기준)

    # 3D
    def p3(self, j):
        return self.sf.P[:, j, :]

    def h3(self, v):
        return np.sum(v * self.up3, -1)

    def flat3(self, v):
        return v - self.up3 * self.h3(v)[:, None]

    # 2D
    def p2(self, j):
        return self.sf.xy[:, j, :]

    def h2(self, v):
        return np.sum(v * self.u2, -1)

    def l2(self, v):
        return np.sum(v * self.r2, -1)

    def body_axes(self):
        """3D 신체 좌표계: xb = 사람 왼쪽(골반 수평), zb = xb × up(정면을 향한 사람이면 카메라 쪽)."""
        xb = unit(self.flat3(self.p3(LM['l_hip']) - self.p3(LM['r_hip'])))
        zb = unit(np.cross(xb, self.up3))
        return xb, zb

    def left2(self):
        """2D 사람 왼쪽 방향의 부호(+1: 화면 오른쪽이 사람 왼쪽). 골반 x 순서로 프레임마다."""
        d = self.l2(self.p2(LM['l_hip']) - self.p2(LM['r_hip']))
        return np.sign(d)


def vis_ok(sf: SetFrames, joints, thr=MIN_VIS):
    return np.all(sf.vis[:, joints] >= thr, 1)


# ---------------------------------------------------------------- 반복 분할

@dataclass
class Rep:
    k: int
    i_start: int      # 하강 시작(서 있음 띠를 마지막으로 벗어나기 직전 프레임)
    i_bottom: int     # 최소 프레임
    i_end: int        # 복귀(서 있음 띠에 처음 다시 들어온 프레임)
    top: list         # 하강 직전 서 있는 프레임(≤ 5)
    bottom: list      # 최소 + 진폭/3 이하
    descent: list
    ascent: list      # 바닥 다음 ~ 복귀
    early_ascent: list  # 바닥 다음 ~ 진폭 1/2 회복까지
    vmin: float
    vmax: float


def segment_reps(t, sig, stand_level, min_amp=35.0, top_n=5):
    """무릎각 신호(서 있음 = 큼)로 반복을 나눈다. 앱 RepForm 의 위상 정의와 같은 뜻으로:
    상단 = 하강 직전 서 있는 프레임 ≤ 5개, 바닥 = 최소 + 진폭/3 이하, 상승 초반 = 바닥 다음부터 진폭 1/2 회복까지."""
    v = np.asarray(sig, float)
    ok = np.isfinite(v)
    below = ok & (v < stand_level)
    reps = []
    i = 0; n = len(v); k = 0
    while i < n:
        if not below[i]:
            i += 1; continue
        j = i
        while j < n and (below[j] or not ok[j]):
            j += 1
        seg = np.arange(i, j)
        segv = np.where(ok[seg], v[seg], np.inf)
        ib = seg[int(np.argmin(segv))]
        # 진폭 = 이 구간 앞 서 있음 최대 − 최소
        pre = [q for q in range(max(0, i - 8), i) if ok[q]]
        vmax = max([v[q] for q in pre] + [stand_level])
        vmin = v[ib]
        if vmax - vmin >= min_amp:
            top = [q for q in range(i - 1, -1, -1) if ok[q] and v[q] >= stand_level][:top_n][::-1]
            # 직전 반복 복귀 이후만
            if reps:
                top = [q for q in top if q >= reps[-1].i_end]
            lvl = vmin + (vmax - vmin) / 3.0
            half = vmin + (vmax - vmin) / 2.0
            bottom = [q for q in seg if ok[q] and v[q] <= lvl]
            descent = [q for q in range(i, ib) if ok[q]]
            iend = j if j < n else n - 1
            ascent = [q for q in range(ib + 1, iend + 1) if ok[q]]
            early = []
            for q in ascent:
                early.append(q)
                if v[q] >= half:
                    break
            k += 1
            reps.append(Rep(k, i - 1 if i > 0 else i, ib, iend, top, bottom, descent, ascent, early, vmin, vmax))
        i = j
    return reps


def standing_level_from_start(t, sig, first_bottom_t, band=10.5):
    """시작 자세(첫 반복 전 서 있는 프레임) 무릎각 중앙값 − band(앱 §21.11 STANDING_BAND_REF 0.3h = 10.5°)."""
    v = np.asarray(sig, float)
    pre = (t < first_bottom_t) & np.isfinite(v)
    vals = v[pre]
    ref = np.nanmedian(np.sort(vals)[-5:]) if len(vals) else np.nanmax(v)
    return ref - band, ref


# ---------------------------------------------------------------- 핀홀 카메라 맞춤

# Galaxy Note10+(SM-N976N) 전면 10 MP, 26 mm 환산 → 대각 화각 ≈ 80°. 4:3 전체 화면을 480×640 으로 받으면
# 반대각 400 px ↔ tan(39.8°) → f ≈ 480 px. 기기 사양에서 온 **가정값**이다(보정 촬영으로 잰 값이 아님).
F_PX = 480.0
CX, CY = IMG_W / 2.0, IMG_H / 2.0


def mp_world_m(sf: SetFrames) -> np.ndarray:
    """앱 좌표(cm, Y 위·Z 카메라 쪽) → MediaPipe 카메라 규약(m, x 오른쪽·y 아래·z 멀어짐)."""
    W = np.empty_like(sf.P)
    W[..., 0] = sf.P[..., 0] / 100.0
    W[..., 1] = -sf.P[..., 1] / 100.0
    W[..., 2] = -sf.P[..., 2] / 100.0
    return W


def project(Wc: np.ndarray, f: float, c=(CX, CY)) -> np.ndarray:
    """카메라 좌표(m, MediaPipe 규약) → 픽셀."""
    z = Wc[..., 2]
    return np.stack([f * Wc[..., 0] / z + c[0], f * Wc[..., 1] / z + c[1]], -1)


def fit_translation(W: np.ndarray, u: np.ndarray, wts: np.ndarray, f: float, c=(CX, CY)):
    """한 프레임: 월드(골반 원점, 카메라 축 정렬 가정) + T 가 픽셀 u 로 투영되도록 T 를 맞춘다.
    MediaPipe 월드 랜드마크 축은 카메라 축과 평행하다고 가정(회전 없음). 반환 (T, 잔차 RMS px) 또는 None."""
    from scipy.optimize import least_squares
    m = np.isfinite(W).all(1) & np.isfinite(u).all(1) & (wts > 0)
    if m.sum() < 6:
        return None
    Wm, um, wm = W[m], u[m], np.sqrt(wts[m])
    # 약원근 초기값
    Wc = Wm[:, :2] - Wm[:, :2].mean(0); uc = um - um.mean(0)
    s = np.sqrt((uc ** 2).sum() / max((Wc ** 2).sum(), 1e-9))
    tz = f / s
    txy = (um.mean(0) - np.array(c)) / s - Wm[:, :2].mean(0)
    x0 = np.array([txy[0], txy[1], tz])

    def res(T):
        p = project(Wm + T, f, c)
        return ((p - um) * wm[:, None]).ravel()
    r = least_squares(res, x0, method='lm')
    T = r.x
    p = project(Wm + T, f, c)
    rms = float(np.sqrt(np.mean(np.sum((p - um) ** 2, 1))))
    return T, rms


def rstd(x):
    x = np.asarray(x, float); x = x[np.isfinite(x)]
    return float(np.std(x, ddof=1)) if len(x) >= 2 else float('nan')


def med(x):
    x = np.asarray(x, float); x = x[np.isfinite(x)]
    return float(np.median(x)) if len(x) else float('nan')


def fmt(x, d=2):
    if x is None or (isinstance(x, float) and not math.isfinite(x)):
        return '—'
    return f'{x:.{d}f}'


def md_table(header, rows):
    out = ['| ' + ' | '.join(header) + ' |', '|' + '|'.join(['---'] * len(header)) + '|']
    for r in rows:
        out.append('| ' + ' | '.join(str(c) for c in r) + ' |')
    return '\n'.join(out)
