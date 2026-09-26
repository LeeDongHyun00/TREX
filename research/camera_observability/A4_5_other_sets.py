# -*- coding: utf-8 -*-
"""A4 — 좌표 없는 스쿼트 세트까지: 변수별 가용성(관절 가시성), 서 있을 때 떨림(피처), 11:37 세트 정답 대조, 카운터 중앙값 필터 효과,
세트별 촬영 배치의 흔적.

가용성   변수마다 필요한 관절이 전부 vis ≥ 0.5(앱 MIN_VISIBILITY)인 프레임 비율 — 서 있음/바닥 국면. vis 는 모든 세트 로그에 있다.
떨림     서 있는 연속 프레임 쌍의 차 Δ 로 σ = std(Δ)/√2 (느린 이동·자세 변경에 둔감). 앱 피처(3D) 그대로.
11:37    사용자 정답(설계 §21.8): 1·2 정상, 3·4 넓게+발끝밖, 5·6 발끝밖, 7·8 발끝안, 9·10 시작보다 넓은 채. 피처만(좌표 없음).
중앙값   로그 reps.min(카운터가 3샘플 중앙값으로 거른 사이클 최소) − 원 피처 사이클 최소.
출력: outputs/A4/availability_sets.csv, jitter_sets.csv, reps_1137.csv, A4_5_summary.md
"""
from __future__ import annotations

import csv

import numpy as np

from A4_common import (MAIN_SET, SET_1137, TRUTH_1137, frames_of, kst, load_sets, md_table, out_path, segment_reps,
                       standing_level_from_start, utf8_stdout)

SQUAT = '바벨 스쿼트'
REQ = {
    'R_hip_drop': [23, 24, 27, 28], 'R_depth': [23, 24, 25, 26], 'R_knee_min': [23, 24, 25, 26, 27, 28],
    'R_foot_drift': [11, 12, 27, 28], 'knee_out·KASR': [23, 24, 25, 26, 27, 28], 'KFA·KTO': [25, 26, 27, 28, 31, 32],
    'F_heel(발목 2D)': [23, 24, 27, 28], 'F_heel(뒤꿈치)': [29, 30, 31, 32], 'F_hip_first': [11, 12, 23, 24, 27, 28],
    'F_lateral': [23, 24, 25, 26, 27, 28], 'S_toe(발목→발끝)': [27, 28, 31, 32], 'S_toe(뒤꿈치→발끝)': [29, 30, 31, 32],
    'S_stance': [11, 12, 27, 28],
}
JIT = ['knee_maxside', 'hip_height_rel', 'hip_below_knee', 'knee_out_mean', 'kneefoot_mean', 'toe_out_mean', 'stance_sh', 'stance_w',
       'heel_lift', 'foot_pitch_mean', 'torso_incl', 'torso_roll', 'knee_asym', 'hip_asym']


def phases(sf):
    km = sf.feat('knee_mean')
    ok = np.isfinite(km)
    if ok.sum() < 10:
        return None
    idx = np.where(ok & (km < np.nanpercentile(km, 95) - 35))[0]
    if len(idx) == 0:
        return None
    level, ref = standing_level_from_start(sf.t, km, sf.t[idx[0]])
    reps = segment_reps(sf.t, km, level)
    # 반복 판별(앱 §62): 더 편 무릎도 35° 이상 스윙해야 스쿼트 — 무릎 들기 제외
    kmax = sf.feat('knee_maxside')
    good = []
    for r in reps:
        pre = [q for q in r.top if np.isfinite(kmax[q])]
        cyc = [q for q in range(r.i_start, r.i_end + 1) if np.isfinite(kmax[q])]
        if pre and cyc and (np.median(kmax[pre]) - np.min(kmax[cyc])) >= 35:
            good.append(r)
    stand = ok & (km >= level)
    bottom = np.zeros(sf.n, bool)
    for r in good:
        bottom[r.bottom] = True
    return dict(level=level, ref=ref, reps=good, reps_all=reps, stand=stand, bottom=bottom, km=km)


def main():
    utf8_stdout()
    S = load_sets()
    av_rows, jit_rows, lines = [], [], []
    per_set = {}
    for sid, d in sorted(S.items()):
        if d.get('exercise') != SQUAT:
            continue
        sf = frames_of(d)
        ph = phases(sf)
        if ph is None:
            continue
        per_set[sid] = (d, sf, ph)
        # 가용성
        row = dict(set_id=sid, kst=kst(sid), front=d.get('front_camera'), reps=len(ph['reps']))
        for name, joints in REQ.items():
            ok = np.all(sf.vis[:, joints] >= 0.5, 1)
            row[f'{name}|서'] = round(float(np.mean(ok[ph['stand']]) * 100), 0) if ph['stand'].any() else np.nan
            row[f'{name}|바닥'] = round(float(np.mean(ok[ph['bottom']]) * 100), 0) if ph['bottom'].any() else np.nan
        av_rows.append(row)
        # 떨림 — 연속한 서 있는 프레임 쌍
        jr = dict(set_id=sid, kst=kst(sid))
        st = ph['stand']
        pair = st[1:] & st[:-1] & (np.diff(sf.t) < 0.5)
        for k in JIT:
            v = sf.feat(k)
            dv = (v[1:] - v[:-1])[pair]
            dv = dv[np.isfinite(dv)]
            jr[k] = round(float(np.std(dv, ddof=1) / np.sqrt(2)), 4) if len(dv) >= 5 else np.nan
        jr['n_pairs'] = int(pair.sum())
        jit_rows.append(jr)

    with open(out_path('availability_sets.csv'), 'w', newline='', encoding='utf-8-sig') as f:
        w = csv.DictWriter(f, fieldnames=list(av_rows[0].keys())); w.writeheader(); w.writerows(av_rows)
    with open(out_path('jitter_sets.csv'), 'w', newline='', encoding='utf-8-sig') as f:
        w = csv.DictWriter(f, fieldnames=list(jit_rows[0].keys())); w.writeheader(); w.writerows(jit_rows)

    lines.append('# A4-5 좌표 없는 세트 포함 — 가용성·떨림·11:37 정답 대조 (자동 생성)')
    lines.append('')
    lines.append('## 변수별 가용성 (필요 관절 전부 vis ≥ 0.5 인 프레임 %, 서 있음/바닥)')
    names = list(REQ.keys())
    hdr = ['세트', '카메라', '반복'] + names
    rows = []
    for r in av_rows:
        rows.append([f"{r['kst']} {r['set_id'][-8:]}", '전면' if r['front'] else '후면', r['reps']] +
                    [f"{r[f'{n}|서']:.0f}/{r[f'{n}|바닥']:.0f}" if np.isfinite(r[f'{n}|바닥']) else f"{r[f'{n}|서']:.0f}/—" for n in names])
    lines.append(md_table(hdr, rows)); lines.append('')

    lines.append('## 서 있을 때 떨림 σ (앱 피처 3D, 연속 프레임 차 SD/√2)')
    hdr = ['세트', '쌍'] + JIT
    rows = []
    for r in jit_rows:
        rows.append([f"{r['kst']} {r['set_id'][-8:]}", r['n_pairs']] + [('—' if not np.isfinite(r[k]) else f"{r[k]:.3f}") for k in JIT])
    lines.append(md_table(hdr, rows)); lines.append('')

    # ---------------- 촬영 배치의 흔적 (서 있을 때 중앙값)
    lines.append('## 세트별 촬영 배치의 흔적 (서 있을 때 중앙값) — 헤더 tilt_deg 는 마지막 프레임 값')
    hdr = ['세트', '카메라', 'tilt_deg(헤더)', '뒤꿈치 vis', '발끝 vis', 'torso_incl°', 'torso_pitch°', 'foot_pitch°', 'heel_lift', 'stance_w']
    rows = []
    for sid, (d, sf, ph) in per_set.items():
        st = ph['stand']
        def m(k):
            v = sf.feat(k)[st]; v = v[np.isfinite(v)]
            return float(np.median(v)) if len(v) else np.nan
        hv = np.nanmedian(sf.vis[st][:, [29, 30]]); tv = np.nanmedian(sf.vis[st][:, [31, 32]])
        rows.append([f"{kst(sid)} {sid[-8:]}", '전면' if d.get('front_camera') else '후면', f"{d.get('tilt_deg'):.1f}" if d.get('tilt_deg') is not None else '—',
                     f'{hv:.2f}', f'{tv:.2f}', f"{m('torso_incl'):.1f}", f"{m('torso_pitch'):.1f}", f"{m('foot_pitch_mean'):.1f}", f"{m('heel_lift'):.2f}", f"{m('stance_w'):.2f}"])
    lines.append(md_table(hdr, rows)); lines.append('')

    # ---------------- 바닥의 heel_lift 상승(뒤꿈치를 들지 않은 반복 포함 전부) · 카운터 중앙값 필터
    lines.append('## 모든 스쿼트 사이클: 바닥 heel_lift − 상단 (3D), 바닥 knee_out − 상단, 카운터 최소값 필터')
    hdr = ['세트', '사이클', 'heel_lift Δ 중앙 [p10, p90]', 'heel_lift 바닥 가용 %', 'knee_out 상단→바닥 중앙', 'reps.min − 원 최소 중앙° [최대]', '바닥 legLen<40cm 탈락 %']
    rows = []
    for sid, (d, sf, ph) in per_set.items():
        hl = sf.feat('heel_lift'); ko = sf.feat('knee_out_mean'); km = ph['km']
        dh, dk, avail = [], [], []
        for r in ph['reps']:
            t0 = [q for q in r.top if np.isfinite(hl[q])]; b = [q for q in r.bottom if np.isfinite(hl[q])]
            avail.append(len(b) / max(1, len(r.bottom)))
            if t0 and b:
                dh.append(np.mean(hl[b]) - np.median(hl[t0]))
            t1 = [q for q in r.top if np.isfinite(ko[q])]; b1 = [q for q in r.bottom if np.isfinite(ko[q])]
            if t1 and b1:
                dk.append((np.median(ko[t1]), np.mean(ko[b1])))
        # 카운터 reps.min 대 원 최소
        rep = d.get('reps') or {}
        mins = rep.get('min') or []
        tms = [t / 1000 for t in (rep.get('t_ms') or [])]
        diffs = []
        for tm, mn in zip(tms, mins):
            cand = [r for r in ph['reps_all'] if sf.t[r.i_bottom] <= tm + 0.05]
            if not cand or mn is None:
                continue
            r = max(cand, key=lambda r: sf.t[r.i_bottom])
            if tm - sf.t[r.i_bottom] < 4.0:
                diffs.append(mn - r.vmin)
        dh = np.array(dh)
        rows.append([f"{kst(sid)} {sid[-8:]}", len(ph['reps']),
                     f"{np.median(dh):+.2f} [{np.percentile(dh,10):+.2f}, {np.percentile(dh,90):+.2f}]" if len(dh) else '—',
                     f"{np.mean(avail)*100:.0f}",
                     f"{np.median([a for a, b in dk]):+.3f}→{np.median([b for a, b in dk]):+.3f}" if dk else '—',
                     f"{np.median(diffs):+.1f} [{np.max(diffs):+.1f}]" if diffs else '—',
                     f"{np.mean(~np.isfinite(sf.feat('hip_height_rel')[ph['bottom']]) & np.isfinite(sf.feat('knee_mean')[ph['bottom']])) * 100:.0f}"])
    lines.append(md_table(hdr, rows)); lines.append('')

    # ---------------- 정답 세트 반복별(피처만) — 11:37 과 12:19(좌표 판과 대조용)
    lines.append('## 정답 세트 반복별 (앱 피처만으로) — 11:37 · 12:19')
    lines.append('주의: 앱 피처 hip_height_rel·hip_below_knee 는 **현재 프레임의 골반–발목 직선 길이**로 나눠 바닥에서도 ≈0.9 에 머문다(골반 하강을 못 본다). '
                 '그래서 골반 높이는 ankle_y_mean(골반 원점 기준 발목 높이, cm)의 부호를 뒤집어 쓰고(H = −ankle_y_mean), '
                 '골반–무릎 높이 = hip_below_knee ÷ hip_height_rel × H 로 되살린다. 단 앱은 골반–발목 직선이 40 cm 미만이면(legLen 게이트) 이 두 피처를 버려 '
                 '**가장 깊은 프레임에서 R_depth_ratio 가 빠진다**(아래 R_depth_ratio 는 남은 프레임의 최소라 얕게 나올 수 있다).')
    for sid_t, truth in ((SET_1137, TRUTH_1137), (MAIN_SET, None)):
        d, sf, ph = per_set[sid_t]
        reps = ph['reps']
        F = {k: sf.feat(k) for k in ['hip_height_rel', 'hip_below_knee', 'ankle_y_mean', 'knee_maxside', 'knee_out_mean', 'kneefoot_mean',
                                    'toe_out_mean', 'stance_sh', 'heel_lift', 'torso_incl', 'knee_asym', 'torso_roll']}
        H = -F['ankle_y_mean']
        HK = F['hip_below_knee'] / F['hip_height_rel'] * H
        rep_rows = []
        for k, r in enumerate(reps):
            top, bot = list(r.top), list(r.bottom)
            cyc = list(range(r.i_start, r.i_end + 1))
            trail = [q for q in range(r.i_end, min(sf.n, r.i_end + 6)) if ph['stand'][q]][:5]
            stand = top + trail
            asc = list(range(r.i_bottom, r.i_end + 1))

            def st(a, idx, fn):
                v = np.asarray(a)[idx]; v = v[np.isfinite(v)]
                return float(fn(v)) if len(v) else np.nan
            Ht = st(H, top, np.median); HKt = st(HK, top, np.median)
            ti = F['torso_incl']
            rep_rows.append(dict(
                rep=k + 1, truth=(truth[k] if truth and k < len(truth) else ''), t_bottom=round(float(sf.t[r.i_bottom]), 1),
                R_hip_drop=round((Ht - st(H, cyc, np.min)) / Ht, 2), R_depth_ratio=round(st(HK, cyc, np.min) / HKt, 2),
                R_knee_min=round(180 - st(F['knee_maxside'], cyc, np.min), 0),
                hip_height_rel_min=round(st(F['hip_height_rel'], cyc, np.min), 2),
                knee_out_bot=round(st(F['knee_out_mean'], bot, np.mean), 3), kneefoot_bot=round(st(F['kneefoot_mean'], bot, np.mean), 0),
                toe_stand=round(st(F['toe_out_mean'], stand, np.median), 0), stance_sh_stand=round(st(F['stance_sh'], stand, np.median), 2),
                heel_lift_d=round(st(F['heel_lift'], bot, np.mean) - st(F['heel_lift'], top, np.median), 2),
                torso_rise=round(st(ti, asc, np.max) - ti[r.i_bottom], 1) if np.isfinite(ti[r.i_bottom]) else np.nan,
                knee_asym_bot=round(st(F['knee_asym'], bot, np.mean), 1),
                torso_roll_d=round(st(F['torso_roll'], bot, np.mean) - st(F['torso_roll'], top, np.median), 1),
            ))
        name = 'reps_1137.csv' if sid_t == SET_1137 else 'reps_1219_features.csv'
        with open(out_path(name), 'w', newline='', encoding='utf-8-sig') as f:
            w = csv.DictWriter(f, fieldnames=list(rep_rows[0].keys())); w.writeheader(); w.writerows(rep_rows)
        keys = [k for k in rep_rows[0].keys() if k not in ('rep', 'truth', 't_bottom')]
        lines.append(f'### {kst(sid_t)} {sid_t[-8:]} — 판별 통과 사이클 {len(reps)}개')
        hdr = ['반복', '정답'] + keys
        rows = [[r['rep'], r['truth']] + [('—' if not np.isfinite(r[k]) else r[k]) for k in keys] for r in rep_rows]
        lines.append(md_table(hdr, rows)); lines.append('')

    with open(out_path('A4_5_summary.md'), 'w', encoding='utf-8') as fo:
        fo.write('\n'.join(lines) + '\n')
    print('\n'.join(lines))


if __name__ == '__main__':
    main()
