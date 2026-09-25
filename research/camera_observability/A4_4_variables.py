# -*- coding: utf-8 -*-
"""A4 과제 4·5 — 12:19 세트(좌표 있음, 사용자 정답 10회)에서 변수를 반복별로 이미지 2D·월드 3D 로 계산한다.

정답: 1·2 정상, 3·4 발끝 안쪽, 5·6 발끝 바깥, 7·8 넓게(발끝 그대로), 9·10 정상.
부호 규약: 무릎–발 정렬 후보는 전부 + = 무릎이 바깥(외반 반대), − = 안쪽. 좌우는 **사람 기준**(L = 사람 왼쪽).

변수 (2D = 픽셀 등방 좌표, 중력 방향을 화면에 투영한 '위'(u2)·'옆'(r2) 축 / 3D = 월드 cm, up 축)
  R_hip_drop   (상단 골반 높이 − 현재)/상단 골반–발목 높이. 3D·2D(발목 기준) 는 골반−발목 중점 높이, 2D abs 는 화면 절대 골반 높이
               (카메라 고정이라 2D 만 가능 — 월드는 골반 원점). 반복값 = 사이클 최대.
  R_depth      3D: 허벅지 기울기 asin(h(골반−무릎)/|골반−무릎|)(평행 0°, 서 있음 ≈ 90°) 좌우 평균. 비율형: h(골반−무릎) ÷ 상단 허벅지 길이
               (2D 는 이 비율만 가능 — 정면에서 허벅지는 카메라 쪽으로 누워 길이가 줄 뿐 각이 안 보인다). 반복값 = 사이클 최소.
  R_knee_min   덜 굽힌 쪽 무릎 굽힘 = min(180 − 무릎각 L, R). 3D = 월드 각. 2D = 단축(foreshortening) 추정
               acos(허벅지 2D 길이/상단) + acos(정강이 2D 길이/상단)(시상면 굽힘을 가정), 참고로 2D 평면각(= FPPA 크기)도. 반복값 = 사이클 최대.
  R_foot_drift 발목 위치 이동 ÷ 상단 어깨 너비. 2D = 상단 대비 발목 화면 변위(카메라 고정), 좌우 중 큰 쪽, 사이클+복귀 뒤 서 있음 최대.
               3D 는 골반 원점이라 절대 이동을 못 잰다 — 두 발목 사이 수평 벡터의 변화만(상대).
  F_knee_foot  바닥 구간 평균. FPPA(2D 허벅지–정강이 각의 180° 보각, 부호 = 무릎이 엉덩이–발목 선 바깥이면 +),
               knee_out(엉덩이–발목 선에서 무릎 가로 이탈 ÷ 다리 길이, 앱 식 3D 와 같은 식의 2D), KASR(무릎 간격 ÷ 발목 간격),
               KFA3D(수평면에서 (무릎−발목)과 (발끝−발목) 사이 각, 무릎이 발끝 방향 바깥이면 +, 수평 정강이 ≥ 8 cm 일 때만),
               KTO2D(무릎과 발끝의 화면 가로 차 ÷ 다리 2D 길이, + = 무릎이 발끝보다 바깥).
  F_heel       상단 대비. 2D: 발목·뒤꿈치 화면 높이 상승 ÷ 상단 다리 2D 길이, 뒤꿈치−발끝 화면 높이차 변화. 3D: heel_lift(앱 식, 뒤꿈치−발끝 높이 ÷ 길이)
               변화, 발목−발끝 높이 변화 ÷ 다리 길이. 반복값 = 사이클 최대(2D abs 는 카메라 고정이라 바닥 기준).
  F_hip_first  상승 구간(바닥 프레임부터 복귀까지)의 몸통 기울기 최대 − 바닥 값(앱 RISE 와 같은 식) — 3D torso_incl, 2D 는 몸통 단축각
               acos(몸통 2D 길이/상단). 어깨 상승 ÷ 엉덩이 상승(바닥 → 바닥 다음 프레임): 2D 화면 절대 높이, 3D 는 발목 기준 높이.
  F_lateral    바닥 평균 − 상단: 골반 기울기(좌우 고관절 높이차 ÷ 골반 폭, 각도°, + = 왼쪽 높음), 골반 중심 가로 이동(두 발목 중점 대비 ÷ 발목 간격,
               + = 사람 왼쪽), 좌우 무릎 굽힘 차(L − R, 3D 각 / 2D 단축 추정).
  S_toe        서 있는 프레임(상단 + 복귀 뒤) 중앙값. 3D 발목→발끝(앱 toe_out 식)·뒤꿈치→발끝, 2D 화면 각(발목→발끝, 0 = 화면에서 바로 아래, + 바깥).
  S_stance     서 있는 프레임 중앙값. 발목 간격 ÷ 어깨 간격 — 2D 가로(중력 기준) / 3D 수평.
출력: outputs/A4/vars_1219_frames.csv, vars_1219_reps.csv, A4_4_summary.md
"""
from __future__ import annotations

import csv
import math

import numpy as np

from A4_common import (CX, CY, F_PX, LM, MAIN_SET, TRUTH_1219, Geo, angle3, fit_translation, frames_of, load_sets, md_table,
                       mp_world_m, out_path, project, segment_reps, standing_level_from_start, unit, utf8_stdout)

SIDES = ('L', 'R')
J = {s: dict(sh=LM[s.lower() + '_sh'], hip=LM[s.lower() + '_hip'], knee=LM[s.lower() + '_knee'], ank=LM[s.lower() + '_ank'],
             heel=LM[s.lower() + '_heel'], toe=LM[s.lower() + '_toe']) for s in SIDES}
SEG_NAMES = ['정상(1·2)', '발끝안(3·4)', '발끝밖(5·6)', '넓게(7·8)', '정상끝(9·10)']


def wrap180(a):
    return (a + 180.0) % 360.0 - 180.0


# ---------------------------------------------------------------- 프레임별 원자료

def raw2d(xy: np.ndarray, g: Geo, sL: float) -> dict:
    """xy: (n,33,2) 픽셀. 반환: 프레임별 2D 원값들."""
    p = lambda j: xy[:, j, :]
    h2, l2 = g.h2, g.l2
    lat = {'L': sL, 'R': -sL}   # 사람 바깥쪽의 화면 가로 부호
    hipM = 0.5 * (p(LM['l_hip']) + p(LM['r_hip'])); ankM = 0.5 * (p(LM['l_ank']) + p(LM['r_ank']))
    shM = 0.5 * (p(LM['l_sh']) + p(LM['r_sh']))
    o = {}
    o['H_hip_ank'] = h2(hipM - ankM)
    o['Y_hip'] = h2(hipM)
    o['Y_sh'] = h2(shM)
    o['sw'] = np.abs(l2(p(LM['l_sh']) - p(LM['r_sh'])))
    o['torso_len'] = np.linalg.norm(shM - hipM, axis=-1)
    o['ank_sep'] = np.abs(l2(p(LM['l_ank']) - p(LM['r_ank'])))
    o['kne_sep'] = np.abs(l2(p(LM['l_knee']) - p(LM['r_knee'])))
    o['stance'] = o['ank_sep'] / o['sw']
    o['kasr'] = o['kne_sep'] / o['ank_sep']
    dh = h2(p(LM['l_hip']) - p(LM['r_hip'])); dl = np.abs(l2(p(LM['l_hip']) - p(LM['r_hip'])))
    o['pelvis_tilt'] = np.degrees(np.arctan2(dh, dl))
    o['pelvis_shift'] = sL * (l2(hipM) - l2(ankM)) / o['ank_sep']
    for s in SIDES:
        j = J[s]
        hip, kn, an, he, to = p(j['hip']), p(j['knee']), p(j['ank']), p(j['heel']), p(j['toe'])
        o[f'thigh_len_{s}'] = np.linalg.norm(hip - kn, axis=-1)
        o[f'shin_len_{s}'] = np.linalg.norm(kn - an, axis=-1)
        o[f'leg_len_{s}'] = np.linalg.norm(hip - an, axis=-1)
        o[f'hk_h_{s}'] = h2(hip - kn)
        o[f'knee_ang_{s}'] = angle3(hip, kn, an)
        # knee_out 2D: 엉덩이–발목 선의 무릎 높이 지점 대비 가로 이탈
        tt = (h2(kn) - h2(an)) / (h2(hip) - h2(an))
        expl = l2(an) + tt * (l2(hip) - l2(an))
        o[f'ko_{s}'] = lat[s] * (l2(kn) - expl) / o[f'leg_len_{s}']
        o[f'fppa_{s}'] = np.sign(o[f'ko_{s}']) * (180.0 - o[f'knee_ang_{s}'])
        o[f'kto_{s}'] = lat[s] * (l2(kn) - l2(to)) / o[f'leg_len_{s}']
        o[f'ank_y_{s}'] = h2(an); o[f'heel_y_{s}'] = h2(he); o[f'toe_y_{s}'] = h2(to)
        o[f'heel_toe_h_{s}'] = h2(he - to)
        o[f'ank_x_{s}'] = an[:, 0]; o[f'ank_yy_{s}'] = an[:, 1]
        v = to - an
        o[f'toe_ang_{s}'] = np.degrees(np.arctan2(lat[s] * l2(v), -h2(v)))
        v = to - he
        o[f'heeltoe_ang_{s}'] = np.degrees(np.arctan2(lat[s] * l2(v), -h2(v)))
    return o


def raw3d(P: np.ndarray, g: Geo) -> dict:
    p = lambda j: P[:, j, :]
    h3, flat3 = g.h3, g.flat3
    up = g.up3
    xb, zb = g.body_axes()

    def bdir(v):
        return np.stack([np.sum(v * xb, -1), np.sum(v * up, -1), np.sum(v * zb, -1)], -1)

    hipM = 0.5 * (p(LM['l_hip']) + p(LM['r_hip'])); ankM = 0.5 * (p(LM['l_ank']) + p(LM['r_ank']))
    shM = 0.5 * (p(LM['l_sh']) + p(LM['r_sh']))
    o = {}
    o['H_hip_ank'] = h3(hipM - ankM)
    o['H_sh_ank'] = h3(shM - ankM)
    o['sw'] = np.linalg.norm(flat3(p(LM['l_sh']) - p(LM['r_sh'])), axis=-1)
    sv = flat3(p(LM['l_ank']) - p(LM['r_ank']))
    o['stance_vec'] = sv
    o['ank_sep'] = np.linalg.norm(sv, axis=-1)
    o['kne_sep'] = np.linalg.norm(flat3(p(LM['l_knee']) - p(LM['r_knee'])), axis=-1)
    o['stance'] = o['ank_sep'] / o['sw']
    o['kasr'] = o['kne_sep'] / o['ank_sep']
    ch = shM - hipM
    o['torso_incl'] = np.degrees(np.arccos(np.clip(np.sum(unit(ch) * up, -1), -1, 1)))
    dh = h3(p(LM['l_hip']) - p(LM['r_hip'])); dl = np.linalg.norm(flat3(p(LM['l_hip']) - p(LM['r_hip'])), axis=-1)
    o['pelvis_tilt'] = np.degrees(np.arctan2(dh, dl))
    ahat = unit(sv)
    o['pelvis_shift'] = np.sum((hipM - ankM) * ahat, -1) / o['ank_sep']
    for s in SIDES:
        j = J[s]; sign = 1.0 if s == 'L' else -1.0
        hip, kn, an, he, to = p(j['hip']), p(j['knee']), p(j['ank']), p(j['heel']), p(j['toe'])
        o[f'thigh_len_{s}'] = np.linalg.norm(hip - kn, axis=-1)
        o[f'leg_len_{s}'] = np.linalg.norm(hip - an, axis=-1)
        o[f'hk_h_{s}'] = h3(hip - kn)
        o[f'thigh_ang_{s}'] = np.degrees(np.arcsin(np.clip(o[f'hk_h_{s}'] / o[f'thigh_len_{s}'], -1, 1)))
        o[f'flex_{s}'] = 180.0 - angle3(hip, kn, an)
        # knee_out (앱 PostureCore 식, 신체 좌표계)
        hb, kb, ab = bdir(hip - hipM), bdir(kn - hipM), bdir(an - hipM)
        den = hb[:, 1] - ab[:, 1]
        tt = (kb[:, 1] - ab[:, 1]) / den
        expx = ab[:, 0] + tt * (hb[:, 0] - ab[:, 0])
        o[f'ko_{s}'] = sign * (kb[:, 0] - expx) / o[f'leg_len_{s}']
        # 발끝·뒤꿈치 방향(수평) · 무릎 방향(수평)
        fd = bdir(flat3(to - an)); hd = bdir(flat3(to - he)); kd = bdir(flat3(kn - an))
        o[f'toe_{s}'] = np.degrees(np.arctan2(sign * fd[:, 0], fd[:, 2]))
        o[f'heeltoe_{s}'] = np.degrees(np.arctan2(sign * hd[:, 0], hd[:, 2]))
        kang = np.degrees(np.arctan2(sign * kd[:, 0], kd[:, 2]))
        shin_h = np.linalg.norm(flat3(kn - an), axis=-1)
        o[f'kfa_{s}'] = np.where(shin_h >= 8.0, wrap180(kang - o[f'toe_{s}']), np.nan)
        o[f'shin_h_{s}'] = shin_h
        hl = he - to
        o[f'heel_lift_{s}'] = h3(hl) / np.linalg.norm(hl, axis=-1)
        o[f'ank_toe_h_{s}'] = h3(an - to)
    return o


class Geo_one:
    """Geo 의 한 프레임 판 — 합성 좌표를 raw2d 에 넣을 때 그 프레임의 u2·r2 를 쓴다."""
    def __init__(self, g, i):
        self.u2 = g.u2[i:i + 1]; self.r2 = g.r2[i:i + 1]

    def h2(self, v):
        return np.sum(v * self.u2, -1)

    def l2(self, v):
        return np.sum(v * self.r2, -1)


def mean_lr(o, key):
    return 0.5 * (o[f'{key}_L'] + o[f'{key}_R'])


# ---------------------------------------------------------------- 반복별

def nanmed(a, idx):
    v = np.asarray(a)[list(idx)] if len(idx) else np.array([np.nan])
    v = v[np.isfinite(v)]
    return float(np.median(v)) if len(v) else np.nan


def nanmean(a, idx):
    v = np.asarray(a)[list(idx)] if len(idx) else np.array([np.nan])
    v = v[np.isfinite(v)]
    return float(np.mean(v)) if len(v) else np.nan


def nanmax(a, idx):
    v = np.asarray(a)[list(idx)] if len(idx) else np.array([np.nan])
    v = v[np.isfinite(v)]
    return float(np.max(v)) if len(v) else np.nan


def nanmin(a, idx):
    v = np.asarray(a)[list(idx)] if len(idx) else np.array([np.nan])
    v = v[np.isfinite(v)]
    return float(np.min(v)) if len(v) else np.nan


def rep_values(o2: dict, o3: dict, r, trailing: list, n: int, ref: list | None = None) -> dict:
    """한 반복의 변수값(2D·3D). 반환 키는 '<변수>|<2D|3D>'.
    ref = 서 있을 때 기준 프레임(없으면 반복 상단). 발 위치가 반복 안에서 바뀐 경우(7회: 하강 직전에 넓게 벌림) 상단은 옛 자세라
    같은 구간의 조용히 서 있는 프레임을 기준으로 쓴다. 발 이동(R_foot_drift)만은 반복 자신의 상단 대비로 잰다."""
    own_top = r.top
    top = list(ref) if ref else list(r.top)
    cyc = list(range(r.i_start, r.i_end + 1)); bot = r.bottom
    asc = list(range(r.i_bottom, r.i_end + 1)); early = [r.i_bottom] + list(r.early_ascent)
    stand = list(ref) if ref else list(own_top) + list(trailing)
    v = {}
    # R_hip_drop
    for tag, o in (('2D', o2), ('3D', o3)):
        Ht = nanmed(o['H_hip_ank'], top)
        v[f'R_hip_drop|{tag}'] = nanmax((Ht - o['H_hip_ank']) / Ht, cyc)
    Ht = nanmed(o2['H_hip_ank'], top); Yt = nanmed(o2['Y_hip'], top)
    v['R_hip_drop|2Dabs'] = nanmax((Yt - o2['Y_hip']) / Ht, cyc)
    # R_depth
    v['R_depth_ang|3D'] = nanmin(mean_lr(o3, 'thigh_ang'), cyc)
    for tag, o in (('2D', o2), ('3D', o3)):
        rat = 0.5 * (o['hk_h_L'] / nanmed(o['thigh_len_L'], top) + o['hk_h_R'] / nanmed(o['thigh_len_R'], top))
        v[f'R_depth_ratio|{tag}'] = nanmin(rat, cyc)
    # R_knee_min
    flex3m = np.minimum(o3['flex_L'], o3['flex_R'])
    v['R_knee_min|3D'] = nanmax(flex3m, cyc)
    v['R_knee_min_rel|3D'] = v['R_knee_min|3D'] - nanmed(flex3m, top)
    f2 = {}
    for s in SIDES:
        at = np.degrees(np.arccos(np.clip(o2[f'thigh_len_{s}'] / nanmed(o2[f'thigh_len_{s}'], top), -1, 1)))
        a_s = np.degrees(np.arccos(np.clip(o2[f'shin_len_{s}'] / nanmed(o2[f'shin_len_{s}'], top), -1, 1)))
        f2[s] = at + a_s
    flex2m = np.minimum(f2['L'], f2['R'])
    v['R_knee_min_rel|2D'] = nanmax(flex2m, cyc)
    v['R_knee_min_planar|2D'] = nanmax(np.minimum(180 - o2['knee_ang_L'], 180 - o2['knee_ang_R']), cyc)
    # R_foot_drift
    sw2 = nanmed(o2['sw'], own_top)
    dd = []
    for s in SIDES:
        x0, y0 = nanmed(o2[f'ank_x_{s}'], own_top), nanmed(o2[f'ank_yy_{s}'], own_top)
        dd.append(np.hypot(o2[f'ank_x_{s}'] - x0, o2[f'ank_yy_{s}'] - y0) / sw2)
    drift2 = np.maximum(dd[0], dd[1])
    v['R_foot_drift|2D'] = nanmax(drift2, cyc + list(trailing))
    v['R_foot_drift_net|2D'] = nanmax(drift2, trailing) if trailing else np.nan
    sv0 = np.nanmedian(o3['stance_vec'][own_top], axis=0) if len(own_top) else np.full(3, np.nan)
    sw3 = nanmed(o3['sw'], own_top)
    drift3 = np.linalg.norm(o3['stance_vec'] - sv0, axis=-1) / sw3
    v['R_foot_drift_rel|3D'] = nanmax(drift3, cyc + list(trailing))
    # F_knee_foot (바닥 평균)
    v['FPPA|2D'] = nanmean(mean_lr(o2, 'fppa'), bot)
    cand = [q for q in cyc if np.isfinite(flex3m[q]) and 45.0 <= flex3m[q] <= 75.0]
    if cand:
        q = min(cand, key=lambda q: abs(flex3m[q] - 60.0))
        v['FPPA60|2D'] = float(mean_lr(o2, 'fppa')[q])
    else:
        v['FPPA60|2D'] = np.nan
    v['knee_out|2D'] = nanmean(mean_lr(o2, 'ko'), bot)
    v['knee_out|3D'] = nanmean(mean_lr(o3, 'ko'), bot)
    v['KASR|2D'] = nanmean(o2['kasr'], bot)
    v['KASR|3D'] = nanmean(o3['kasr'], bot)
    v['KFA|3D'] = nanmean(mean_lr(o3, 'kfa'), bot)
    v['KTO|2D'] = nanmean(mean_lr(o2, 'kto'), bot)
    v['knee_out_top|2D'] = nanmed(mean_lr(o2, 'ko'), top)
    v['knee_out_top|3D'] = nanmed(mean_lr(o3, 'ko'), top)
    # F_heel
    L2 = 0.5 * (nanmed(o2['leg_len_L'], top) + nanmed(o2['leg_len_R'], top))
    for key, nm in (('ank_y', 'F_heel_ank_rise'), ('heel_y', 'F_heel_heel_rise')):
        rise = 0.5 * ((o2[f'{key}_L'] - nanmed(o2[f'{key}_L'], top)) + (o2[f'{key}_R'] - nanmed(o2[f'{key}_R'], top))) / L2
        v[f'{nm}|2D'] = nanmax(rise, cyc)
    ht = 0.5 * ((o2['heel_toe_h_L'] - nanmed(o2['heel_toe_h_L'], top)) + (o2['heel_toe_h_R'] - nanmed(o2['heel_toe_h_R'], top))) / L2
    v['F_heel_heeltoe|2D'] = nanmax(ht, cyc)
    hl = mean_lr(o3, 'heel_lift')
    v['F_heel_lift|3D'] = nanmax(hl - nanmed(hl, top), cyc)
    v['F_heel_lift_bottom|3D'] = nanmean(hl, bot) - nanmed(hl, top)
    L3 = 0.5 * (nanmed(o3['leg_len_L'], top) + nanmed(o3['leg_len_R'], top))
    at = mean_lr(o3, 'ank_toe_h')
    v['F_heel_anktoe|3D'] = nanmax((at - nanmed(at, top)) / L3, cyc)
    # F_hip_first
    ti = o3['torso_incl']
    v['F_hip_first_rise|3D'] = nanmax(ti, asc) - ti[r.i_bottom] if np.isfinite(ti[r.i_bottom]) else np.nan
    t2 = np.degrees(np.arccos(np.clip(o2['torso_len'] / nanmed(o2['torso_len'], top), -1, 1)))
    v['F_hip_first_rise|2D'] = nanmax(t2, asc) - t2[r.i_bottom] if np.isfinite(t2[r.i_bottom]) else np.nan
    v['torso_max|3D'] = nanmax(ti, cyc) - nanmed(ti, top)
    v['torso_max|2D'] = nanmax(t2, cyc)
    ib = r.i_bottom
    if ib + 1 < n:
        dy_h = o2['Y_hip'][ib + 1] - o2['Y_hip'][ib]; dy_s = o2['Y_sh'][ib + 1] - o2['Y_sh'][ib]
        v['F_hip_first_ratio|2D'] = dy_s / dy_h if abs(dy_h) > 1e-6 else np.nan
        dh_h = o3['H_hip_ank'][ib + 1] - o3['H_hip_ank'][ib]; dh_s = o3['H_sh_ank'][ib + 1] - o3['H_sh_ank'][ib]
        v['F_hip_first_ratio|3D'] = dh_s / dh_h if abs(dh_h) > 1e-6 else np.nan
    # F_lateral (바닥 평균 − 상단)
    for tag, o in (('2D', o2), ('3D', o3)):
        v[f'F_pelvis_tilt|{tag}'] = nanmean(o['pelvis_tilt'], bot) - nanmed(o['pelvis_tilt'], top)
        v[f'F_pelvis_shift|{tag}'] = nanmean(o['pelvis_shift'], bot) - nanmed(o['pelvis_shift'], top)
    v['F_knee_diff|3D'] = nanmean(o3['flex_L'] - o3['flex_R'], bot)
    v['F_knee_diff|2D'] = nanmean(f2['L'] - f2['R'], bot)
    # S_toe / S_stance
    v['S_toe_ankle|3D'] = nanmed(mean_lr(o3, 'toe'), stand)
    v['S_toe_heel|3D'] = nanmed(mean_lr(o3, 'heeltoe'), stand)
    v['S_toe_ankle|2D'] = nanmed(mean_lr(o2, 'toe_ang'), stand)
    v['S_toe_heel|2D'] = nanmed(mean_lr(o2, 'heeltoe_ang'), stand)
    v['S_stance|2D'] = nanmed(o2['stance'], stand)
    v['S_stance|3D'] = nanmed(o3['stance'], stand)
    v['n_top'] = len(top); v['n_bottom'] = len(bot); v['n_trail'] = len(trailing); v['n_asc'] = len(asc)
    return v


# 표에 올릴 변수 순서: (행 이름, 키, 단위 배율, 소수)
TABLE = [
    ('R_hip_drop', 'R_hip_drop|3D', 1, 2), ('', 'R_hip_drop|2D', 1, 2), ('', 'R_hip_drop|2Dabs', 1, 2),
    ('R_depth 허벅지각°', 'R_depth_ang|3D', 1, 0), ('R_depth 비율', 'R_depth_ratio|3D', 1, 2), ('', 'R_depth_ratio|2D', 1, 2),
    ('R_knee_min° (상단 대비)', 'R_knee_min_rel|3D', 1, 0), ('', 'R_knee_min_rel|2D', 1, 0), ('R_knee_min° (절대 3D)', 'R_knee_min|3D', 1, 0),
    ('R_foot_drift ÷어깨', 'R_foot_drift|2D', 1, 2), ('', 'R_foot_drift_rel|3D', 1, 2),
    ('FPPA° 바닥 (+바깥)', 'FPPA|2D', 1, 1), ('FPPA° 굽힘 60° 프레임', 'FPPA60|2D', 1, 1), ('knee_out (+바깥)', 'knee_out|3D', 1, 3), ('', 'knee_out|2D', 1, 3),
    ('KASR', 'KASR|3D', 1, 2), ('', 'KASR|2D', 1, 2), ('KFA° 3D (+바깥)', 'KFA|3D', 1, 0), ('KTO 2D (+바깥)', 'KTO|2D', 1, 3),
    ('F_heel 발목 상승 ÷다리', 'F_heel_ank_rise|2D', 1, 3), ('F_heel 뒤꿈치 상승 ÷다리', 'F_heel_heel_rise|2D', 1, 3),
    ('F_heel heel_lift Δ', 'F_heel_lift|3D', 1, 2), ('F_heel 바닥 heel_lift Δ', 'F_heel_lift_bottom|3D', 1, 2),
    ('F_heel 발목−발끝 Δ÷다리', 'F_heel_anktoe|3D', 1, 3),
    ('F_hip_first 상승 몸통°', 'F_hip_first_rise|3D', 1, 1), ('', 'F_hip_first_rise|2D', 1, 1),
    ('F_hip_first 어깨/엉덩이', 'F_hip_first_ratio|3D', 1, 2), ('', 'F_hip_first_ratio|2D', 1, 2),
    ('F_lat 골반 기울기°', 'F_pelvis_tilt|3D', 1, 1), ('', 'F_pelvis_tilt|2D', 1, 1),
    ('F_lat 골반 이동 ÷발목간격', 'F_pelvis_shift|3D', 1, 3), ('', 'F_pelvis_shift|2D', 1, 3),
    ('F_lat 무릎 굽힘 L−R°', 'F_knee_diff|3D', 1, 1), ('', 'F_knee_diff|2D', 1, 1),
    ('S_toe° 발목→발끝', 'S_toe_ankle|3D', 1, 0), ('', 'S_toe_ankle|2D', 1, 0), ('S_toe° 뒤꿈치→발끝', 'S_toe_heel|3D', 1, 0), ('', 'S_toe_heel|2D', 1, 0),
    ('S_stance', 'S_stance|3D', 1, 2), ('', 'S_stance|2D', 1, 2),
]

# 프레임 단위 2D–3D 대응(같은 뜻의 원값): (이름, 2D 식, 3D 식)
PAIRS = [
    ('골반–발목 높이 (서 있을 때 정규화)', lambda o2, o3: o2['H_hip_ank'], lambda o2, o3: o3['H_hip_ank']),
    ('골반–무릎 높이 (서 있을 때 정규화)', lambda o2, o3: mean_lr(o2, 'hk_h'), lambda o2, o3: mean_lr(o3, 'hk_h')),
    ('knee_out 좌우 평균', lambda o2, o3: mean_lr(o2, 'ko'), lambda o2, o3: mean_lr(o3, 'ko')),
    ('KASR', lambda o2, o3: o2['kasr'], lambda o2, o3: o3['kasr']),
    ('발 너비 ÷ 어깨', lambda o2, o3: o2['stance'], lambda o2, o3: o3['stance']),
    ('발끝 각(발목→발끝)', lambda o2, o3: mean_lr(o2, 'toe_ang'), lambda o2, o3: mean_lr(o3, 'toe')),
    ('골반 기울기°', lambda o2, o3: o2['pelvis_tilt'], lambda o2, o3: o3['pelvis_tilt']),
    ('골반 가로 이동', lambda o2, o3: o2['pelvis_shift'], lambda o2, o3: o3['pelvis_shift']),
]


def main():
    utf8_stdout()
    S = load_sets({MAIN_SET})
    d = S[MAIN_SET]
    sf = frames_of(d)
    g = Geo(sf)
    n = sf.n
    act = np.array([len(f) > 0 for f in sf.feats]) & (sf.t <= d['assessment_end_t_ms'] / 1000)
    sL = float(np.sign(np.nanmedian(g.l2(g.p2(LM['l_hip']) - g.p2(LM['r_hip']))[act])))
    o2 = raw2d(sf.xy, g, sL)
    o3 = raw3d(sf.P, g)
    for o in (o2, o3):
        for k in list(o.keys()):
            if o[k].ndim == 1:
                o[k] = np.where(act, o[k], np.nan)

    # 앱 피처와 파리티(좌표 → 같은 식)
    parity = {}
    for key, mine in (('knee_out_L', o3['ko_L']), ('knee_out_R', o3['ko_R']), ('toe_out_L', o3['toe_L']), ('stance_sh', o3['stance']),
                      ('torso_incl', o3['torso_incl']), ('heel_lift_L', o3['heel_lift_L'])):
        ref = sf.feat(key); m = np.isfinite(ref) & np.isfinite(mine)
        parity[key] = float(np.max(np.abs(ref[m] - mine[m]))) if m.any() else float('nan')

    km = sf.feat('knee_mean')
    idx = np.where(np.isfinite(km) & (km < np.nanpercentile(km, 95) - 35))[0]
    level, ref_knee = standing_level_from_start(sf.t, km, sf.t[idx[0]])
    reps = segment_reps(sf.t, km, level)
    assert len(reps) == 10, f'반복 {len(reps)}개'
    standing = np.isfinite(km) & (km >= level) & act

    # 복귀 뒤 서 있음(다음 반복 하강 전, ≤ 5)
    trails = []
    for k, r in enumerate(reps):
        nxt = reps[k + 1].i_start if k + 1 < len(reps) else n
        trails.append([q for q in range(r.i_end, nxt) if standing[q]][:5])

    # 구간 경계와 조용히 서 있는 프레임(떨림 계산·반복 기준용)
    bounds = [0.0] + [(sf.t[reps[k - 1].i_end] + sf.t[reps[k].i_start]) / 2 for k in (2, 4, 6, 8)] + [1e9]
    seg_of = np.digitize(sf.t, bounds[1:-1])
    quiet = np.zeros(n, bool)
    for sgi in range(5):
        m = standing & (seg_of == sgi)
        if m.sum() < 3:
            continue
        ok = m.copy()
        for s in SIDES:
            for jn in ('ank', 'toe'):   # 발목만 보면 발끝을 돌리는 프레임(발목은 제자리)이 섞인다 — 발끝 위치도 본다
                jj = J[s][jn]
                cx0, cy0 = np.nanmedian(sf.xy[m, jj, 0]), np.nanmedian(sf.xy[m, jj, 1])
                ok &= np.hypot(sf.xy[:, jj, 0] - cx0, sf.xy[:, jj, 1] - cy0) <= 8.0
        quiet |= ok

    # 반복 기준 = 그 반복 바닥이 속한 구간의 조용히 서 있는 프레임(구간마다 발 자세가 하나)
    refs = [list(np.where(quiet & (seg_of == seg_of[r.i_bottom]))[0]) for r in reps]
    rv = [rep_values(o2, o3, r, trails[k], n, refs[k]) for k, r in enumerate(reps)]
    rv_top = [rep_values(o2, o3, r, trails[k], n, None) for k, r in enumerate(reps)]   # 앱처럼 반복 자신의 상단(+복귀 뒤)을 기준으로 한 판

    # 서 있을 때 떨림 — 같은 뜻의 프레임 값(상단 기준 변환 없이 원값), 구간별 SD 의 합동값
    def pooled_sd(arr):
        var = []; dof = 0
        for sgi in range(5):
            x = arr[quiet & (seg_of == sgi)]; x = x[np.isfinite(x)]
            if len(x) >= 3:
                var.append(np.var(x, ddof=1) * (len(x) - 1)); dof += len(x) - 1
        return float(np.sqrt(sum(var) / dof)) if dof > 0 else float('nan')

    def diff_sd(arr):
        """연속한 조용한 프레임 쌍의 차로 본 떨림 σ = std(Δ)/√2 (느린 이동에 둔감)."""
        ds = []
        for i in range(1, n):
            if quiet[i] and quiet[i - 1] and seg_of[i] == seg_of[i - 1] and np.isfinite(arr[i]) and np.isfinite(arr[i - 1]):
                ds.append(arr[i] - arr[i - 1])
        return float(np.std(ds, ddof=1) / math.sqrt(2)) if len(ds) >= 3 else float('nan')

    # 프레임 값으로 서 있을 때 떨림을 잴 변수(표 키 → 프레임 배열)
    L2top = np.nanmedian(0.5 * (o2['leg_len_L'] + o2['leg_len_R'])[quiet])
    L3top = np.nanmedian(0.5 * (o3['leg_len_L'] + o3['leg_len_R'])[quiet])
    Hh2 = np.nanmedian(o2['H_hip_ank'][quiet]); Hh3 = np.nanmedian(o3['H_hip_ank'][quiet])
    th2 = np.nanmedian(0.5 * (o2['thigh_len_L'] + o2['thigh_len_R'])[quiet]); th3 = np.nanmedian(0.5 * (o3['thigh_len_L'] + o3['thigh_len_R'])[quiet])
    sw2q = np.nanmedian(o2['sw'][quiet])
    fl2 = {}
    for s in SIDES:
        at = np.degrees(np.arccos(np.clip(o2[f'thigh_len_{s}'] / np.nanmedian(o2[f'thigh_len_{s}'][quiet]), -1, 1)))
        a_s = np.degrees(np.arccos(np.clip(o2[f'shin_len_{s}'] / np.nanmedian(o2[f'shin_len_{s}'][quiet]), -1, 1)))
        fl2[s] = at + a_s
    frame_series = {
        'R_hip_drop|3D': o3['H_hip_ank'] / Hh3, 'R_hip_drop|2D': o2['H_hip_ank'] / Hh2, 'R_hip_drop|2Dabs': o2['Y_hip'] / Hh2,
        'R_depth_ang|3D': mean_lr(o3, 'thigh_ang'), 'R_depth_ratio|3D': mean_lr(o3, 'hk_h') / th3, 'R_depth_ratio|2D': mean_lr(o2, 'hk_h') / th2,
        'R_knee_min_rel|3D': np.minimum(o3['flex_L'], o3['flex_R']), 'R_knee_min_rel|2D': np.minimum(fl2['L'], fl2['R']),
        'R_knee_min|3D': np.minimum(o3['flex_L'], o3['flex_R']),
        'R_foot_drift|2D': np.hypot(o2['ank_x_L'], o2['ank_yy_L']) / sw2q, 'R_foot_drift_rel|3D': o3['stance_vec'][:, 0] / np.nanmedian(o3['sw'][quiet]),
        'FPPA|2D': mean_lr(o2, 'fppa'), 'knee_out|3D': mean_lr(o3, 'ko'), 'knee_out|2D': mean_lr(o2, 'ko'),
        'KASR|3D': o3['kasr'], 'KASR|2D': o2['kasr'], 'KFA|3D': mean_lr(o3, 'kfa'), 'KTO|2D': mean_lr(o2, 'kto'),
        'F_heel_ank_rise|2D': mean_lr(o2, 'ank_y') / L2top, 'F_heel_heel_rise|2D': mean_lr(o2, 'heel_y') / L2top,
        'F_heel_lift|3D': mean_lr(o3, 'heel_lift'), 'F_heel_lift_bottom|3D': mean_lr(o3, 'heel_lift'),
        'F_heel_anktoe|3D': mean_lr(o3, 'ank_toe_h') / L3top,
        'F_hip_first_rise|3D': o3['torso_incl'],
        'F_pelvis_tilt|3D': o3['pelvis_tilt'], 'F_pelvis_tilt|2D': o2['pelvis_tilt'],
        'F_pelvis_shift|3D': o3['pelvis_shift'], 'F_pelvis_shift|2D': o2['pelvis_shift'],
        'F_knee_diff|3D': o3['flex_L'] - o3['flex_R'], 'F_knee_diff|2D': fl2['L'] - fl2['R'],
        'S_toe_ankle|3D': mean_lr(o3, 'toe'), 'S_toe_ankle|2D': mean_lr(o2, 'toe_ang'), 'S_toe_heel|3D': mean_lr(o3, 'heeltoe'),
        'S_toe_heel|2D': mean_lr(o2, 'heeltoe_ang'), 'S_stance|3D': o3['stance'], 'S_stance|2D': o2['stance'],
    }

    # ---------- CSV
    with open(out_path('vars_1219_reps.csv'), 'w', newline='', encoding='utf-8-sig') as f:
        keys = list(rv[0].keys())
        w = csv.writer(f)
        w.writerow(['rep', 'truth', 't_bottom_s'] + keys)
        for k, r in enumerate(reps):
            w.writerow([k + 1, TRUTH_1219[k], round(float(sf.t[r.i_bottom]), 2)] + [('' if not np.isfinite(rv[k][c]) else round(rv[k][c], 4)) for c in keys])
    fr_keys = [k for k in o2 if o2[k].ndim == 1]
    fr3_keys = [k for k in o3 if o3[k].ndim == 1]
    with open(out_path('vars_1219_frames.csv'), 'w', newline='', encoding='utf-8-sig') as f:
        w = csv.writer(f)
        w.writerow(['i', 't', 'knee_mean', 'standing', 'quiet', 'segment'] + [f'2D_{k}' for k in fr_keys] + [f'3D_{k}' for k in fr3_keys])
        for i in range(n):
            w.writerow([i, round(float(sf.t[i]), 3), '' if not np.isfinite(km[i]) else round(float(km[i]), 2), int(standing[i]), int(quiet[i]), int(seg_of[i])] +
                       [('' if not np.isfinite(o2[k][i]) else round(float(o2[k][i]), 4)) for k in fr_keys] +
                       [('' if not np.isfinite(o3[k][i]) else round(float(o3[k][i]), 4)) for k in fr3_keys])

    # ---------- 요약
    Lm = ['# A4-4 12:19 세트 반복별 변수 (자동 생성)', '']
    Lm.append(f"앱 피처 파리티(좌표로 다시 계산 − 로그 피처, 최대 절대차): " + ', '.join(f'{k} {v:.4f}' for k, v in parity.items()))
    Lm.append(f"서 있음 띠 = 시작 무릎각 {ref_knee:.1f}° − 10.5° = {level:.1f}°. 반복 바닥 시각(s): " + ', '.join(f'{sf.t[r.i_bottom]:.1f}' for r in reps))
    Lm.append(f"조용히 서 있는 프레임(발목·발끝 2D 가 구간 중앙값에서 8 px 안): 구간별 " + ', '.join(f'{SEG_NAMES[s]} {int((quiet & (seg_of == s)).sum())}' for s in range(5)))
    Lm.append(f"반복별 프레임 수(상단/바닥/상승/복귀 뒤): " + ' · '.join(f"{v['n_top']}/{v['n_bottom']}/{v['n_asc']}/{v['n_trail']}" for v in rv))
    Lm.append('')

    hdr = ['변수', '판'] + [f'{k+1}' for k in range(10)] + ['서 있음 SD', 'Δ프레임 σ']
    rows = []
    for name, key, sc, dp in TABLE:
        tag = key.split('|')[1]
        vals = [rv[k][key] for k in range(10)]
        fs = frame_series.get(key)
        sd = pooled_sd(fs) if fs is not None else float('nan')
        ds = diff_sd(fs) if fs is not None else float('nan')
        rows.append([name, tag] + [('—' if not np.isfinite(x) else f'{x*sc:.{dp}f}') for x in vals] +
                    [('—' if not np.isfinite(sd) else f'{sd*sc:.{dp+1}f}'), ('—' if not np.isfinite(ds) else f'{ds*sc:.{dp+1}f}')])
    Lm.append('반복별 값 (열 = 반복 1~10, 정답: 1·2 정상 · 3·4 발끝안 · 5·6 발끝밖 · 7·8 넓게 · 9·10 정상). '
              '서 있음 SD = 조용히 서 있는 프레임의 구간 내 SD(합동), Δ프레임 σ = 연속 프레임 차 SD/√2. '
              '(상대 변수의 SD 는 같은 정규화의 프레임 원값으로 잰다: 예 R_hip_drop 은 골반 높이 ÷ 서 있을 때 높이.)')
    Lm.append(md_table(hdr, rows)); Lm.append('')

    # 구간 평균 표 (정답 구간별)
    segs = [(0, 1), (2, 3), (4, 5), (6, 7), (8, 9)]
    hdr = ['변수', '판'] + SEG_NAMES + ['발끝안−정상', '발끝밖−정상', '넓게−정상', '정상 4회 SD']
    rows = []
    seg_means = {}
    for name, key, sc, dp in TABLE:
        tag = key.split('|')[1]
        m = [np.nanmean([rv[a][key], rv[b][key]]) for a, b in segs]
        seg_means[key] = m
        base = m[0]
        nrm = [rv[k][key] for k in (0, 1, 8, 9)]
        sdn = float(np.nanstd(nrm, ddof=1)) if np.isfinite(nrm).sum() >= 2 else float('nan')
        rows.append([name, tag] + [('—' if not np.isfinite(x) else f'{x*sc:.{dp}f}') for x in m] +
                    [('—' if not np.isfinite(m[i] - base) else f'{(m[i]-base)*sc:+.{dp}f}') for i in (1, 2, 3)] +
                    [('—' if not np.isfinite(sdn) else f'{sdn*sc:.{dp+1}f}')])
    Lm.append('정답 구간 평균과 시작 정상(1·2) 대비 차')
    Lm.append(md_table(hdr, rows)); Lm.append('')

    # 2D–3D 프레임 대응
    hdr = ['원값', '프레임 수', 'Pearson r(전체)', 'r(서 있음)', 'r(바닥)', '기울기 2D/3D', '비고']
    rows = []
    bot_mask = np.zeros(n, bool)
    for r in reps:
        bot_mask[r.bottom] = True
    for name, f2d, f3d in PAIRS:
        a = f2d(o2, o3); b = f3d(o2, o3)
        if name.startswith('골반–'):   # 픽셀 vs cm — 서 있을 때 값으로 나눠 무차원으로
            a = a / np.nanmedian(np.abs(a[quiet])); b = b / np.nanmedian(np.abs(b[quiet]))
        def corr(mask):
            mm = mask & np.isfinite(a) & np.isfinite(b)
            if mm.sum() < 5:
                return float('nan'), int(mm.sum())
            return float(np.corrcoef(a[mm], b[mm])[0, 1]), int(mm.sum())
        rall, nall = corr(act)
        rst, _ = corr(quiet)
        rbt, _ = corr(bot_mask)
        mm = act & np.isfinite(a) & np.isfinite(b)
        slope = float(np.polyfit((b[mm] - b[mm].mean()) / (b[mm].std() or 1), (a[mm] - a[mm].mean()) / (a[mm].std() or 1), 1)[0]) if mm.sum() > 5 else float('nan')
        rows.append([name, nall, f'{rall:.2f}', f'{rst:.2f}', f'{rbt:.2f}', f'{np.polyfit(b[mm], a[mm], 1)[0]:.2f}' if mm.sum() > 5 else '—', ''])
    Lm.append('프레임 단위 2D–3D 일치 (같은 뜻의 원값, 집계 창 프레임)')
    Lm.append(md_table(hdr, rows)); Lm.append('')

    # 반복 단위 순위 일치
    def spearman(x, y):
        x = np.asarray(x, float); y = np.asarray(y, float); m = np.isfinite(x) & np.isfinite(y)
        if m.sum() < 4:
            return float('nan')
        rx = np.argsort(np.argsort(x[m])); ry = np.argsort(np.argsort(y[m]))
        return float(np.corrcoef(rx, ry)[0, 1])
    pairs_rep = [('R_hip_drop', 'R_hip_drop|2D', 'R_hip_drop|3D'), ('R_hip_drop abs', 'R_hip_drop|2Dabs', 'R_hip_drop|3D'),
                 ('R_depth 비율', 'R_depth_ratio|2D', 'R_depth_ratio|3D'), ('R_knee_min(상단 대비)', 'R_knee_min_rel|2D', 'R_knee_min_rel|3D'),
                 ('knee_out', 'knee_out|2D', 'knee_out|3D'), ('KASR', 'KASR|2D', 'KASR|3D'), ('KTO2D ↔ KFA3D', 'KTO|2D', 'KFA|3D'),
                 ('FPPA ↔ knee_out3D', 'FPPA|2D', 'knee_out|3D'),
                 ('F_hip_first 상승 몸통', 'F_hip_first_rise|2D', 'F_hip_first_rise|3D'), ('어깨/엉덩이 비', 'F_hip_first_ratio|2D', 'F_hip_first_ratio|3D'),
                 ('골반 기울기', 'F_pelvis_tilt|2D', 'F_pelvis_tilt|3D'), ('골반 이동', 'F_pelvis_shift|2D', 'F_pelvis_shift|3D'),
                 ('무릎 굽힘 L−R', 'F_knee_diff|2D', 'F_knee_diff|3D'),
                 ('S_toe 발목', 'S_toe_ankle|2D', 'S_toe_ankle|3D'), ('S_stance', 'S_stance|2D', 'S_stance|3D')]
    Lm.append('반복 단위 순위 일치(Spearman, 10회): ' + ', '.join(f"{nm} {spearman([v[a] for v in rv], [v[b] for v in rv]):.2f}" for nm, a, b in pairs_rep))
    Lm.append('')

    # ---------- 원근 분해 (재투영)
    W = mp_world_m(sf)
    wts = np.where(sf.vis >= 0.5, 1.0, 0.0)
    persp = np.full_like(sf.xy, np.nan); ortho = np.full_like(sf.xy, np.nan)
    rms = np.full(n, np.nan)
    for i in range(n):
        if not act[i]:
            continue
        r = fit_translation(W[i], sf.xy[i], wts[i], F_PX)
        if r is None:
            continue
        T, e = r
        rms[i] = e
        Wc = W[i] + T
        persp[i] = project(Wc, F_PX)
        s = F_PX / T[2]
        ortho[i, :, 0] = CX + s * Wc[:, 0]; ortho[i, :, 1] = CY + s * Wc[:, 1]
    p2 = raw2d(persp, g, sL); q2 = raw2d(ortho, g, sL)
    dec = [('발 너비 ÷ 어깨(서 있음)', 'stance', quiet), ('KASR(바닥)', 'kasr', bot_mask), ('KASR(서 있음)', 'kasr', quiet),
           ('knee_out 2D(바닥)', 'ko', bot_mask), ('knee_out 2D(서 있음)', 'ko', quiet), ('발끝 화면각°(서 있음)', 'toe_ang', quiet),
           ('골반–무릎 높이 ÷ 허벅지(바닥)', 'hk_h', bot_mask), ('골반 기울기°(바닥)', 'pelvis_tilt', bot_mask)]
    hdr = ['변수(국면)', '관측 2D', '원근 재투영', '정사영', '원근 몫(재투영−정사영)', '불일치 몫(관측−재투영)', '3D 값']
    rows = []
    for nm, key, mask in dec:
        def val(o):
            if key in ('ko', 'toe_ang'):
                a = mean_lr(o, key)
            elif key == 'hk_h':
                a = mean_lr(o, 'hk_h') / np.nanmedian(0.5 * (o['thigh_len_L'] + o['thigh_len_R'])[quiet])
            else:
                a = o[key]
            return a
        a_obs, a_p, a_o = val(o2), val(p2), val(q2)
        if key == 'ko':
            a3 = mean_lr(o3, 'ko')
        elif key == 'toe_ang':
            a3 = mean_lr(o3, 'toe')
        elif key == 'hk_h':
            a3 = mean_lr(o3, 'hk_h') / np.nanmedian(0.5 * (o3['thigh_len_L'] + o3['thigh_len_R'])[quiet])
        else:
            a3 = o3[key]
        mm = mask & np.isfinite(a_obs) & np.isfinite(a_p) & np.isfinite(a_o)
        dp = 3 if key in ('ko',) else 2 if key in ('stance', 'kasr', 'hk_h') else 1
        rows.append([nm, f'{np.mean(a_obs[mm]):.{dp}f}', f'{np.mean(a_p[mm]):.{dp}f}', f'{np.mean(a_o[mm]):.{dp}f}',
                     f'{np.mean(a_p[mm]-a_o[mm]):+.{dp}f}', f'{np.mean(a_obs[mm]-a_p[mm]):+.{dp}f}', f'{np.nanmean(a3[mm]):.{dp}f}'])
    # 넓게 구간 발끝 각: 원근 몫
    wide = quiet & (seg_of == 3); norm0 = quiet & (seg_of == 0)
    for nm, o in (('관측 2D', o2), ('원근 재투영', p2), ('정사영', q2)):
        a = mean_lr(o, 'toe_ang')
        rows.append([f'발끝 화면각° 넓게−정상(서 있음), {nm}', f'{np.nanmean(a[wide]) - np.nanmean(a[norm0]):+.1f}', '', '', '', '', ''])
    a3 = mean_lr(o3, 'toe')
    rows.append(['발끝 3D° 넓게−정상(서 있음)', f'{np.nanmean(a3[wide]) - np.nanmean(a3[norm0]):+.1f}', '', '', '', '', ''])
    Lm.append(f'원근 분해 — 월드 좌표를 맞춘 핀홀(f = {F_PX:.0f} px, 프레임별 T)로 재투영 vs 같은 배율 정사영. 재투영 RMS 중앙 {np.nanmedian(rms):.1f} px')
    Lm.append(md_table(hdr, rows)); Lm.append('')

    # 합성 시험 — 정상 구간 서 있는 프레임에서 발(발목·뒤꿈치·발끝)만 옮기거나 돌려 같은 카메라로 투영: 순수 원근 효과
    wide_sep = np.nanmedian(o3['ank_sep'][quiet & (seg_of == 3)]) / 100.0
    norm_sep = np.nanmedian(o3['ank_sep'][quiet & (seg_of == 0)]) / 100.0
    dx = (wide_sep - norm_sep) / 2.0
    feetL, feetR = [LM['l_ank'], LM['l_heel'], LM['l_toe']], [LM['r_ank'], LM['r_heel'], LM['r_toe']]
    res_syn = {'base': [], 'wide': [], 'rot20': []}
    for i in np.where(quiet & (seg_of == 0))[0]:
        r = fit_translation(W[i], sf.xy[i], wts[i], F_PX)
        if r is None:
            continue
        T, _ = r
        base = W[i] + T
        wide_w = base.copy(); wide_w[feetL, 0] += dx; wide_w[feetR, 0] -= dx
        rot_w = base.copy()
        for feet, sgn in ((feetL, 1.0), (feetR, -1.0)):   # 발목을 축으로 수직축(월드 up) 둘레 20° 바깥 회전
            a = math.radians(20.0) * sgn
            upm = np.array([sf.up[i, 0], -sf.up[i, 1], -sf.up[i, 2]]); upm /= np.linalg.norm(upm)
            K = np.array([[0, -upm[2], upm[1]], [upm[2], 0, -upm[0]], [-upm[1], upm[0], 0]])
            R = np.eye(3) + math.sin(a) * K + (1 - math.cos(a)) * K @ K
            piv = base[feet[0]]
            for j in feet[1:]:
                rot_w[j] = piv + R @ (base[j] - piv)
        for nm, arr in (('base', base), ('wide', wide_w), ('rot20', rot_w)):
            pp = project(arr, F_PX)[None]
            o = raw2d(pp, Geo_one(g, i), sL)
            res_syn[nm].append((float(mean_lr(o, 'toe_ang')[0]), float(mean_lr(o, 'heeltoe_ang')[0]), float(o['stance'][0])))
    B = np.array(res_syn['base']); Wd = np.array(res_syn['wide']); Rt = np.array(res_syn['rot20'])
    Lm.append(f'합성 시험(정상 구간 서 있는 {len(B)}프레임, 같은 카메라로 재투영): 발만 좌우로 {dx*100:.0f} cm 씩 벌림 → 발끝 화면각 {np.mean(Wd[:,0]-B[:,0]):+.1f}° · '
              f'뒤꿈치→발끝 화면각 {np.mean(Wd[:,1]-B[:,1]):+.1f}° · 발 너비 비 {np.mean(B[:,2]):.2f}→{np.mean(Wd[:,2]):.2f}. '
              f'발만 20° 바깥 회전 → 발끝 화면각 {np.mean(Rt[:,0]-B[:,0]):+.1f}° · 뒤꿈치→발끝 {np.mean(Rt[:,1]-B[:,1]):+.1f}° · 발 너비 비 {np.mean(Rt[:,2]):.2f}.')
    Lm.append('')

    with open(out_path('A4_4_summary.md'), 'w', encoding='utf-8') as fo:
        fo.write('\n'.join(Lm) + '\n')
    print('\n'.join(Lm))


if __name__ == '__main__':
    main()
