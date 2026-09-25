# -*- coding: utf-8 -*-
"""A4 과제 3 — 폰 카메라 기하 추정 (12:19 세트, 좌표 있는 유일한 스쿼트 세트).

1) 피치·롤: 프레임별 up(앱 좌표 X 오른쪽·Y 위·Z 카메라 쪽)에서 피치 = asin(−up_z)(+ = 카메라가 위를 봄), 롤 = atan2(up_x, up_y).
   헤더 tilt_deg 는 `SetLog.build` 가 **마지막** up 프레임으로 만든다 → 세트 끝에 폰을 집어 들면 그 각이 찍힌다(비교해 보인다).
2) 거리·높이: MediaPipe 월드(골반 원점, 카메라 축과 평행 가정) + T 를 핀홀(f = 480 px 가정, 주점 = 화면 중심)로 투영해 프레임마다 T 를 맞춘다.
   D = 카메라 → 골반 중점 수평 거리, 카메라 높이 = 골반 높이(발목 + 8 cm 가정) − 골반의 카메라 위 높이.
   f 는 사양에서 온 가정값이라 f 를 바꿔 가며(400~560) 재투영 오차와 D·높이의 민감도를 본다.
   교차 확인: (a) 수평선 행 y_h = c_y + f·tan(피치) 에 걸리는 몸 부위, (b) 발목 행에서 구한 높이, (c) 몸 크기 → 거리.
3) 원근 분해: 같은 월드 좌표를 (i) 원근 투영(맞춘 T), (ii) 정사영(같은 배율)으로 화면에 옮겨 변수를 다시 계산 →
   원근이 만든 몫 = (i) − (ii). 관측 2D − (i) = MediaPipe 2D 와 3D 의 불일치 몫. 변수 목록은 A4_4 의 2D 식과 같다.
출력: outputs/A4/geometry_frames.csv, A4_3_summary.md
"""
from __future__ import annotations

import csv
import math

import numpy as np

from A4_common import (CX, CY, F_PX, LM, MAIN_SET, PRE_SET, Geo, fit_translation, frames_of, load_sets, md_table,
                       mp_world_m, out_path, project, segment_reps, standing_level_from_start, utf8_stdout)

ANKLE_H = 0.08   # 발목 랜드마크의 바닥 위 높이(m) 가정


def main():
    utf8_stdout()
    S = load_sets({MAIN_SET, PRE_SET})
    d = S[MAIN_SET]
    sf = frames_of(d)
    g = Geo(sf)
    up = sf.up
    pitch = np.degrees(np.arcsin(np.clip(-up[:, 2], -1, 1)))
    roll = np.degrees(np.arctan2(up[:, 0], up[:, 1]))
    tilt = np.degrees(np.arccos(np.clip(up[:, 1] / np.linalg.norm(up, axis=1), -1, 1)))
    feat = np.array([len(f) > 0 for f in sf.feats])
    act = feat & (sf.t <= (d.get('assessment_end_t_ms') or 1e12) / 1000)

    L = []
    L.append('# A4-3 카메라 기하 (12:19 세트, 자동 생성)')
    L.append('')
    L.append(f"헤더 tilt_deg = {d.get('tilt_deg')} — 마지막 프레임 값. 세트 중(피처 있는 {act.sum()}프레임) 피치 중앙 {np.median(pitch[act]):.2f}° "
             f"(범위 {pitch[act].min():.2f}~{pitch[act].max():.2f}), 롤 중앙 {np.median(roll[act]):.2f}° (범위 {roll[act].min():.2f}~{roll[act].max():.2f}), "
             f"기울기 중앙 {np.median(tilt[act]):.2f}°. 마지막 8프레임(폰을 집음) 기울기 {', '.join(f'{x:.0f}' for x in tilt[-8:])}°.")
    L.append('')

    # 반복 분할 — 서 있는 프레임
    km = sf.feat('knee_mean')
    idx = np.where(np.isfinite(km) & (km < np.nanpercentile(km, 95) - 35))[0]
    level, ref = standing_level_from_start(sf.t, km, sf.t[idx[0]])
    stand = act & np.isfinite(km) & (km >= level)
    reps = segment_reps(sf.t, km, level)
    bottom_idx = sorted({q for r in reps for q in r.bottom})

    W = mp_world_m(sf)
    u = sf.xy
    wts = np.where(sf.vis >= 0.5, 1.0, 0.0)

    # f 스캔 — 서 있는 프레임 재투영 오차
    stand_idx = np.where(stand)[0]
    scan = []
    for f in [380, 420, 450, 480, 510, 540, 600]:
        errs = []; Ds = []; Hs = []
        for i in stand_idx:
            r = fit_translation(W[i], u[i], wts[i], f)
            if r is None:
                continue
            T, rms = r
            errs.append(rms)
        scan.append((f, float(np.median(errs)), float(np.mean(errs))))
    L.append('f 스캔(서 있는 프레임 재투영 RMS px, 중앙/평균): ' + ', '.join(f'f={f}: {a:.2f}/{b:.2f}' for f, a, b in scan) +
             ' — 차이가 작으면 f 는 데이터로 식별되지 않는다(몸의 깊이 폭이 거리보다 훨씬 작다).')
    L.append('')

    # f = F_PX 로 프레임별 T
    rows = []
    upm = np.stack([up[:, 0], -up[:, 1], -up[:, 2]], 1)   # MediaPipe 규약 up
    for i in range(sf.n):
        if not act[i]:
            continue
        r = fit_translation(W[i], u[i], wts[i], F_PX)
        if r is None:
            continue
        T, rms = r
        upi = upm[i] / np.linalg.norm(upm[i])
        hip_above_cam = float(T @ upi)
        horiz = T - upi * (T @ upi)
        D = float(np.linalg.norm(horiz))
        # 골반 높이(발목 위) — 월드
        hipmid = 0.5 * (W[i, LM['l_hip']] + W[i, LM['r_hip']])
        ankmid = 0.5 * (W[i, LM['l_ank']] + W[i, LM['r_ank']])
        hip_h = float((hipmid - ankmid) @ upi) + ANKLE_H
        rows.append(dict(i=i, t=round(float(sf.t[i]), 2), stand=bool(stand[i]), bottom=i in bottom_idx,
                         Tx=round(float(T[0]), 3), Ty=round(float(T[1]), 3), Tz=round(float(T[2]), 3), rms_px=round(rms, 2),
                         D_m=round(D, 3), hip_above_cam_m=round(hip_above_cam, 3), hip_h_m=round(hip_h, 3),
                         cam_h_m=round(hip_h - hip_above_cam, 3), pitch=round(float(pitch[i]), 2)))
    with open(out_path('geometry_frames.csv'), 'w', newline='', encoding='utf-8-sig') as fcsv:
        w = csv.DictWriter(fcsv, fieldnames=list(rows[0].keys())); w.writeheader(); w.writerows(rows)

    def seg_rows(lo, hi):
        return [r for r in rows if r['stand'] and lo <= r['t'] < hi]

    # 구간(사용자 정답 구간별 서 있는 프레임) — 반복 쌍 사이 경계는 반복 분할에서
    bounds = [0.0]
    for k in (2, 4, 6, 8):
        bounds.append((sf.t[reps[k - 1].i_end] + sf.t[reps[k].i_start]) / 2)
    bounds.append(1e9)
    names = ['정상(1·2)', '발끝 안(3·4)', '발끝 밖(5·6)', '넓게(7·8)', '정상 끝(9·10)']
    hdr = ['구간', '서 있는 프레임', 'D m', '카메라 높이 m', '골반 높이 m', '골반−카메라 m', '재투영 RMS px']
    tab = []
    for nm, lo, hi in zip(names, bounds[:-1], bounds[1:]):
        rr = seg_rows(lo, hi)
        if not rr:
            continue
        tab.append([nm, len(rr), f"{np.median([r['D_m'] for r in rr]):.2f}", f"{np.median([r['cam_h_m'] for r in rr]):.2f}",
                    f"{np.median([r['hip_h_m'] for r in rr]):.2f}", f"{np.median([r['hip_above_cam_m'] for r in rr]):.3f}",
                    f"{np.median([r['rms_px'] for r in rr]):.1f}"])
    allst = [r for r in rows if r['stand']]
    tab.append(['서 있음 전체', len(allst), f"{np.median([r['D_m'] for r in allst]):.2f}", f"{np.median([r['cam_h_m'] for r in allst]):.2f}",
                f"{np.median([r['hip_h_m'] for r in allst]):.2f}", f"{np.median([r['hip_above_cam_m'] for r in allst]):.3f}",
                f"{np.median([r['rms_px'] for r in allst]):.1f}"])
    bot = [r for r in rows if r['bottom']]
    tab.append(['바닥 프레임', len(bot), f"{np.median([r['D_m'] for r in bot]):.2f}", '—', '—', '—', f"{np.median([r['rms_px'] for r in bot]):.1f}"])
    L.append(f'핀홀 맞춤(f = {F_PX:.0f} px 가정, 발목 = 바닥 위 {ANKLE_H*100:.0f} cm 가정, 몸 크기는 MediaPipe 월드 미터 그대로):')
    L.append(md_table(hdr, tab)); L.append('')

    # f 민감도
    sens = []
    for f in [400, 440, 480, 520, 560]:
        Ds = []; Hs = []
        for i in stand_idx[::2]:
            r = fit_translation(W[i], u[i], wts[i], f)
            if r is None:
                continue
            T, rms = r
            upi = upm[i] / np.linalg.norm(upm[i])
            hipmid = 0.5 * (W[i, LM['l_hip']] + W[i, LM['r_hip']]); ankmid = 0.5 * (W[i, LM['l_ank']] + W[i, LM['r_ank']])
            Ds.append(np.linalg.norm(T - upi * (T @ upi)))
            Hs.append((hipmid - ankmid) @ upi + ANKLE_H - T @ upi)
        sens.append((f, np.median(Ds), np.median(Hs)))
    L.append('f 민감도(서 있음 중앙): ' + ', '.join(f'f={f}: D {a:.2f} m · 높이 {b:.2f} m' for f, a, b in sens))
    L.append('')

    # 교차 확인 (a) 수평선 행
    yh = CY + F_PX * np.tan(np.radians(np.median(pitch[act])))
    st = np.where(stand)[0]
    ys = {k: np.median(sf.xy[st, LM[k], 1]) for k in ['l_sh', 'l_hip', 'l_knee', 'l_ank', 'l_toe', 'r_sh', 'r_hip', 'r_knee', 'r_ank', 'r_toe']}
    ym = {k: 0.5 * (ys['l_' + k] + ys['r_' + k]) for k in ['sh', 'hip', 'knee', 'ank', 'toe']}
    nose_y = np.median(sf.xy[st, 0, 1])
    L.append(f"(a) 수평선(카메라 높이) 행 y_h = {yh:.0f} px (화면 높이 640 의 {yh/640*100:.0f}%). 서 있을 때 화면 행: 코 {nose_y:.0f} · 어깨 {ym['sh']:.0f} · "
             f"골반 {ym['hip']:.0f} · 무릎 {ym['knee']:.0f} · 발목 {ym['ank']:.0f} · 발끝 {ym['toe']:.0f} px → 수평선은 골반과 무릎 사이"
             f"(골반에서 무릎 쪽으로 {(yh-ym['hip'])/(ym['knee']-ym['hip'])*100:.0f}%).")
    # 월드 높이(발목 위)로 보간
    hts = {}
    for k in ['sh', 'hip', 'knee', 'ank']:
        a = LM['l_' + k if k != 'ank' else 'l_ank']; b = LM['r_' + k if k != 'ank' else 'r_ank']
        mid = 0.5 * (W[st, a] + W[st, b]); ank = 0.5 * (W[st, LM['l_ank']] + W[st, LM['r_ank']])
        hts[k] = float(np.median(np.sum((mid - ank) * (upm[st] / np.linalg.norm(upm[st], axis=1, keepdims=True)), 1)))
    frac = (yh - ym['hip']) / (ym['knee'] - ym['hip'])
    h_at = hts['hip'] + frac * (hts['knee'] - hts['hip']) + ANKLE_H
    L.append(f"   월드 높이(발목 위): 어깨 {hts['sh']:.2f} · 골반 {hts['hip']:.2f} · 무릎 {hts['knee']:.2f} m → 수평선 행의 몸 높이 ≈ {h_at:.2f} m(바닥 위) = 카메라 높이.")
    # (b) 발목 행에서
    s_px_per_m = (ym['ank'] - ym['hip']) / hts['hip']
    D_b = F_PX / s_px_per_m
    ang = math.atan((ym['ank'] - CY) / F_PX) - math.radians(np.median(pitch[act]))
    H_b = ANKLE_H + D_b * math.tan(ang)
    L.append(f"(b) 몸 크기: 골반–발목 {ym['ank']-ym['hip']:.0f} px ↔ {hts['hip']:.2f} m → {s_px_per_m:.0f} px/m → D ≈ f/배율 = {D_b:.2f} m. "
             f"발목 행 {ym['ank']:.0f} px 는 수평 아래 {math.degrees(ang):.1f}° → 카메라 높이 ≈ {H_b:.2f} m.")
    shw_px = np.median(np.abs(sf.xy[st, LM['l_sh'], 0] - sf.xy[st, LM['r_sh'], 0]))
    shw_m = np.median(np.linalg.norm(W[st, LM['l_sh']] - W[st, LM['r_sh']], axis=1))
    L.append(f"(c) 어깨 폭 {shw_px:.0f} px ↔ 월드 {shw_m:.2f} m → D ≈ {F_PX*shw_m/shw_px:.2f} m. "
             f"다리(골반–발목) 월드 길이 중앙 {np.median(np.linalg.norm(0.5*(W[st,LM['l_hip']]+W[st,LM['r_hip']]) - 0.5*(W[st,LM['l_ank']]+W[st,LM['r_ank']]), axis=1)):.2f} m "
             f"— MediaPipe 월드 척도는 사람 키를 모른다(평균 체형 가정). 실제 키가 x% 크면 D·높이도 x% 크다.")
    L.append('')
    # 발 위치 — 화면 가로
    ank_x = {s: np.median(sf.xy[st, LM[s + '_ank'], 0]) for s in ['l', 'r']}
    L.append(f"서 있을 때 발목 가로 위치(px, 중심 240): 왼 {ank_x['l']:.0f} · 오른 {ank_x['r']:.0f} → 광축에서 {abs(ank_x['l']-CX)/F_PX*57.3:.1f}°·{abs(ank_x['r']-CX)/F_PX*57.3:.1f}° 떨어져 있다.")
    # 월드 좌표계가 카메라 축과 평행하다는 가정의 점검 — 회전(3)까지 풀어 재투영 오차와 x축(피치) 회전을 무릎각 구간별로
    from scipy.optimize import least_squares
    from scipy.spatial.transform import Rotation as Rot
    rot_rows = []
    for i in np.where(act)[0]:
        m = np.isfinite(W[i]).all(1) & (wts[i] > 0)
        r0 = fit_translation(W[i], u[i], wts[i], F_PX)
        if r0 is None or m.sum() < 6:
            continue
        Wm, um = W[i][m], u[i][m]

        def res(x):
            R = Rot.from_rotvec(x[:3]).as_matrix()
            return (project(Wm @ R.T + x[3:], F_PX) - um).ravel()
        rr = least_squares(res, np.r_[0, 0, 0, r0[0]], method='lm')
        e = Rot.from_rotvec(rr.x[:3]).as_euler('xyz', degrees=True)
        rms_rt = float(np.sqrt(np.mean(np.sum((project(Wm @ Rot.from_rotvec(rr.x[:3]).as_matrix().T + rr.x[3:], F_PX) - um) ** 2, 1))))
        rot_rows.append((i, float(km[i]) if np.isfinite(km[i]) else np.nan, r0[1], rms_rt, e[0], e[1], e[2]))
    rot = np.array(rot_rows)
    with open(out_path('geometry_rotation.csv'), 'w', newline='', encoding='utf-8-sig') as fcsv:
        w = csv.writer(fcsv); w.writerow(['i', 'knee_mean', 'rms_T_px', 'rms_RT_px', 'rot_x_deg', 'rot_y_deg', 'rot_z_deg'])
        for rrow in rot_rows:
            w.writerow([rrow[0]] + [round(x, 3) for x in rrow[1:]])
    L.append('월드 좌표계 = 카메라 축 가정 점검(회전까지 맞춤, 무릎각 구간별 중앙): ' + '; '.join(
        f'무릎 {lo}~{hi}°: n={int(((rot[:,1]>=lo)&(rot[:,1]<hi)).sum())}, RMS {np.nanmedian(rot[(rot[:,1]>=lo)&(rot[:,1]<hi),2]):.1f}→{np.nanmedian(rot[(rot[:,1]>=lo)&(rot[:,1]<hi),3]):.1f} px, '
        f'x축 회전 {np.nanmedian(rot[(rot[:,1]>=lo)&(rot[:,1]<hi),4]):+.1f}°'
        for lo, hi in ((150, 181), (120, 150), (95, 120), (55, 95))))
    st_i = np.where(stand)[0]
    zrel = {k: float(np.median(W[st_i, LM[k], 2]) * 100) for k in ('nose', 'l_sh', 'l_hip', 'l_knee', 'l_ank')}
    L.append('서 있을 때 월드 깊이(z, 골반 기준 cm, + = 카메라에서 멀어짐): ' + ', '.join(f'{k} {v:+.0f}' for k, v in zrel.items()) +
             ' — 3D 몸이 카메라 쪽으로 ~10° 기울어 있다(서 있음 torso_incl 중앙 %.1f°).' % float(np.nanmedian(sf.feat('torso_incl')[stand])))
    L.append('')
    with open(out_path('A4_3_summary.md'), 'w', encoding='utf-8') as fo:
        fo.write('\n'.join(L) + '\n')
    print('\n'.join(L))


if __name__ == '__main__':
    main()
