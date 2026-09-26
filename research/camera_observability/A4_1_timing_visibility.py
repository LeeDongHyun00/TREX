# -*- coding: utf-8 -*-
"""A4 과제 1·2 — 실제 폰 로그의 샘플 간격·추론 시간·발열, 반복당·국면당 프레임 수, 관절별 가시성.

입력: data/phone/*/sets-2026092*.jsonl (같은 set_id 는 한 번만)
출력: outputs/A4/timing_sets.csv, reps_phase_frames.csv, visibility_sets.csv, A4_1_summary.md

정의
    dt            연속 기록 프레임의 t_ms 차. 로그는 **검출된 프레임만** 남기므로(`PostureLive`: s.detected 일 때만 기록)
                  끊김은 dt 로 나타난다. 빠진 프레임 추정 = Σ max(0, round(dt/중앙 dt) − 1).
    발열          헤더 thermal{start,changes}. 대리 지표로 infer_ms 의 세트 앞 1/3 대 뒤 1/3 중앙값 차와 dt > 400 ms 비율.
    반복 분할     knee_mean 피처(앱 카운터 신호). 서 있음 띠 = 첫 반복 전 무릎각 상위 5개 중앙값 − 10.5°(앱 §21.11).
                  진폭 ≥ 35° 인 사이클만. 국면: 하강(띠 이탈~최소 전) · 바닥(최소 + 진폭/3 이하, 앱 BOTTOM) ·
                  상승(최소 다음~복귀) · 상승 초반(최소 다음~진폭 1/2 회복까지, 그 프레임 포함).
    바닥 놓침     최소 프레임과 앞뒤 이웃으로 포물선을 맞춰 참 최소를 추정 — 샘플 최소가 얕게 읽힌 양(°).
    가시성        vis = min(visibility, presence). 앱은 0.5 미만 관절을 버린다(PostureAnalyzer MIN_VISIBILITY).
"""
from __future__ import annotations

import csv
import math

import numpy as np

from A4_common import (BACK_SET, LM_NAME, LOWER, MAIN_SET, frames_of, kst, load_sets, md_table, out_path,
                       segment_reps, standing_level_from_start, utf8_stdout)

SQUAT = '바벨 스쿼트'


def timing_row(d, sf):
    t = sf.t
    dt = np.diff(t) * 1000 if sf.n > 1 else np.array([np.nan])
    med = float(np.median(dt))
    missing = int(np.sum(np.maximum(0, np.round(dt / med) - 1))) if sf.n > 1 else 0
    n3 = max(1, sf.n // 3)
    inf_a = float(np.median(sf.infer[:n3])); inf_b = float(np.median(sf.infer[-n3:]))
    th = d.get('thermal')
    return dict(
        set_id=sf.set_id, kst=kst(sf.set_id), exercise=d.get('exercise'), front=d.get('front_camera'),
        app=d.get('app_version') or '(09-23 빌드)', frames=sf.n, dur_s=round(float(t[-1] - t[0]), 1),
        dt_med=round(med, 1), dt_p10=round(float(np.percentile(dt, 10)), 1), dt_p90=round(float(np.percentile(dt, 90)), 1),
        dt_p99=round(float(np.percentile(dt, 99)), 1), dt_max=round(float(np.max(dt)), 1),
        pct_dt_gt400=round(float(np.mean(dt > 400) * 100), 1), gaps_gt1500=int(np.sum(dt > 1500)), missing_est=missing,
        fps_eff=round(1000.0 / med, 2),
        infer_med=float(np.median(sf.infer)), infer_p90=float(np.percentile(sf.infer, 90)), infer_max=float(np.max(sf.infer)),
        infer_first3=inf_a, infer_last3=inf_b,
        thermal_start=(th or {}).get('start'), thermal_changes=len((th or {}).get('changes', [])) if th else None,
    )


def parabola_min(tt, vv):
    """세 점 포물선의 꼭짓점 (t*, v*). 위로 볼록이 아니면 None."""
    A = np.vstack([tt ** 2, tt, np.ones(3)]).T
    try:
        a, b, c = np.linalg.solve(A, vv)
    except np.linalg.LinAlgError:
        return None
    if a <= 0:
        return None
    ts = -b / (2 * a)
    if ts < tt[0] or ts > tt[2]:
        return None
    return ts, c - b * b / (4 * a)


def rep_rows(sf, sig_name='knee_mean'):
    sig = sf.feat(sig_name)
    t = sf.t
    ok = np.isfinite(sig)
    if ok.sum() < 10:
        return []
    # 첫 바닥 후보: 서 있음 최대 − 35° 아래로 처음 내려간 뒤의 최소
    vmax = np.nanpercentile(sig, 95)
    idx = np.where(ok & (sig < vmax - 35))[0]
    if len(idx) == 0:
        return []
    level, ref = standing_level_from_start(t, sig, t[idx[0]])
    reps = segment_reps(t, sig, level)
    # 반복 판별(앱 §62 과 같은 뜻): 더 편 무릎(knee_maxside)도 35° 이상 굽어야 스쿼트 사이클 — 한쪽 무릎 들기 제외
    kmax = sf.feat('knee_maxside')
    keep = []
    for r in reps:
        pre = [q for q in r.top if np.isfinite(kmax[q])]
        cyc = [q for q in range(r.i_start, r.i_end + 1) if np.isfinite(kmax[q])]
        if pre and cyc and (np.median(kmax[pre]) - np.min(kmax[cyc])) >= 35:
            keep.append(r)
    reps = keep
    rows = []
    for r in reps:
        i0 = r.i_start; i1 = r.i_end
        amp = r.vmax - r.vmin
        early_strict = [q for q in r.early_ascent if sig[q] < r.vmin + 0.5 * amp]
        # 포물선 참 최소
        ib = r.i_bottom
        miss = np.nan; dt_vertex = np.nan
        if 0 < ib < sf.n - 1 and ok[ib - 1] and ok[ib + 1]:
            pm = parabola_min(t[ib - 1:ib + 2] - t[ib], sig[ib - 1:ib + 2])
            if pm is not None:
                miss = float(sig[ib] - pm[1]); dt_vertex = float(pm[0])
        rows.append(dict(
            set_id=sf.set_id, kst=kst(sf.set_id), rep=r.k, t_bottom=round(float(t[ib]), 2),
            vmin=round(float(r.vmin), 1), amp=round(float(amp), 1),
            dur_s=round(float(t[i1] - t[i0]), 2), frames_cycle=int(i1 - i0 + 1),
            n_top=len(r.top), n_descent=len(r.descent), n_bottom=len(r.bottom), n_ascent=len(r.ascent),
            n_early_ascent=len(early_strict),
            descent_s=round(float(t[ib] - t[i0]), 2), ascent_s=round(float(t[i1] - t[ib]), 2),
            bottom_miss_deg=round(miss, 1) if math.isfinite(miss) else '', vertex_offset_s=round(dt_vertex, 3) if math.isfinite(dt_vertex) else '',
        ))
    return rows


def active_mask(d, sf):
    """집계 창: 앵커 이후 ~ 평가 끝(없으면 세트 전체), 피처가 계산된 프레임."""
    a = (d.get('anchor_t_ms') or 0) / 1000.0
    e = (d.get('assessment_end_t_ms') or sf.t[-1] * 1000) / 1000.0
    feat = np.array([len(f) > 0 for f in sf.feats])
    return (sf.t >= a) & (sf.t <= e) & feat


def vis_rows(d, sf, reps_rows):
    m = active_mask(d, sf)
    # 국면 마스크(스쿼트): 서 있음 / 바닥
    sig = sf.feat('knee_mean')
    stand = np.zeros(sf.n, bool); bottom = np.zeros(sf.n, bool)
    if reps_rows:
        vmax = np.nanpercentile(sig, 95)
        idx = np.where(np.isfinite(sig) & (sig < vmax - 35))[0]
        level, ref = standing_level_from_start(sf.t, sig, sf.t[idx[0]])
        stand = np.isfinite(sig) & (sig >= level)
        for r in segment_reps(sf.t, sig, level):
            bottom[r.bottom] = True
    out = []
    for j in LOWER:
        v = sf.vis[:, j]
        def stat(mask):
            x = v[mask & np.isfinite(v)]
            return (float(np.median(x)) if len(x) else np.nan, float(np.mean(x >= 0.5) * 100) if len(x) else np.nan, int(len(x)))
        a_med, a_ok, a_n = stat(m)
        s_med, s_ok, _ = stat(m & stand)
        b_med, b_ok, _ = stat(m & bottom)
        out.append(dict(set_id=sf.set_id, kst=kst(sf.set_id), front=d.get('front_camera'), joint=LM_NAME[j], idx=j,
                        n=a_n, vis_med=round(a_med, 3), pct_ge05=round(a_ok, 1),
                        stand_vis_med=round(s_med, 3), stand_pct_ge05=round(s_ok, 1),
                        bottom_vis_med=round(b_med, 3), bottom_pct_ge05=round(b_ok, 1)))
    return out


def write_csv(name, rows):
    if not rows:
        return
    with open(out_path(name), 'w', newline='', encoding='utf-8-sig') as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader(); w.writerows(rows)


def main():
    utf8_stdout()
    S = load_sets()
    t_rows, r_rows, v_rows = [], [], []
    for sid, d in sorted(S.items()):
        sf = frames_of(d)
        t_rows.append(timing_row(d, sf))
        if d.get('exercise') == SQUAT and sf.n >= 40:
            rr = rep_rows(sf)
            r_rows += rr
            v_rows += vis_rows(d, sf, rr)
    write_csv('timing_sets.csv', t_rows)
    write_csv('reps_phase_frames.csv', r_rows)
    write_csv('visibility_sets.csv', v_rows)

    # ---------------- 요약
    lines = ['# A4-1 샘플 간격·추론·국면 프레임·가시성 (자동 생성)', '']
    all_dt = []
    for sid, d in S.items():
        sf = frames_of(d)
        if sf.n > 1:
            dt = np.diff(sf.t) * 1000
            all_dt += list(dt[dt < 1500])
    all_dt = np.array(all_dt)
    lines.append(f'전체 {len(S)}세트, 연속 프레임 간격 {len(all_dt)}개(1.5 s 미만): 중앙 {np.median(all_dt):.0f} ms · p10 {np.percentile(all_dt,10):.0f} · '
                 f'p90 {np.percentile(all_dt,90):.0f} · p99 {np.percentile(all_dt,99):.0f} · 300~340 ms 비율 {np.mean((all_dt>=295)&(all_dt<=345))*100:.0f}%')
    lines.append('')
    hdr = ['세트(KST)', '종목', '카메라', '프레임', '길이 s', 'dt 중앙', 'dt p90', 'dt>400 %', '틈>1.5s', '빠진 추정', 'infer 중앙/p90', '앞/뒤 1/3', '열']
    rows = []
    for r in t_rows:
        rows.append([f"{r['kst']} {r['set_id'][-8:]}", r['exercise'], '전면' if r['front'] else '후면', r['frames'], r['dur_s'], r['dt_med'], r['dt_p90'],
                     r['pct_dt_gt400'], r['gaps_gt1500'], r['missing_est'], f"{r['infer_med']:.0f}/{r['infer_p90']:.0f}",
                     f"{r['infer_first3']:.0f}/{r['infer_last3']:.0f}", f"{r['thermal_start']}·{r['thermal_changes']}" if r['thermal_start'] is not None else '—'])
    lines.append(md_table(hdr, rows)); lines.append('')

    if r_rows:
        lines.append('## 반복당·국면당 프레임 (스쿼트, knee_mean 진폭 ≥ 35° 이고 더 편 무릎도 ≥ 35° 굽은 사이클)')
        by = {}
        for r in r_rows:
            by.setdefault((r['kst'], r['set_id']), []).append(r)
        hdr = ['세트', '사이클', '사이클 s', '프레임/사이클', '하강', '바닥', '상승', '상승 초반(½)', '초반 0프레임 %', '바닥 놓침° 중앙/p90']
        rows = []
        allr = []
        for (k, sid), rr in sorted(by.items()):
            allr += rr
            miss = np.array([x['bottom_miss_deg'] for x in rr if x['bottom_miss_deg'] != ''], float)
            rows.append([f'{k} {sid[-8:]}', len(rr), f"{np.median([x['dur_s'] for x in rr]):.2f}", f"{np.median([x['frames_cycle'] for x in rr]):.0f}",
                         f"{np.median([x['n_descent'] for x in rr]):.0f}", f"{np.median([x['n_bottom'] for x in rr]):.0f}",
                         f"{np.median([x['n_ascent'] for x in rr]):.0f}", f"{np.median([x['n_early_ascent'] for x in rr]):.0f}",
                         f"{np.mean([x['n_early_ascent'] == 0 for x in rr])*100:.0f}",
                         f"{np.median(miss):.1f}/{np.percentile(miss,90):.1f}" if len(miss) else '—'])
        miss = np.array([x['bottom_miss_deg'] for x in allr if x['bottom_miss_deg'] != ''], float)
        rows.append(['**전체**', len(allr), f"{np.median([x['dur_s'] for x in allr]):.2f}", f"{np.median([x['frames_cycle'] for x in allr]):.0f}",
                     f"{np.median([x['n_descent'] for x in allr]):.0f}", f"{np.median([x['n_bottom'] for x in allr]):.0f}",
                     f"{np.median([x['n_ascent'] for x in allr]):.0f}", f"{np.median([x['n_early_ascent'] for x in allr]):.0f}",
                     f"{np.mean([x['n_early_ascent'] == 0 for x in allr])*100:.0f}", f"{np.median(miss):.1f}/{np.percentile(miss,90):.1f}"])
        lines.append(md_table(hdr, rows)); lines.append('')
        lines.append(f"상승 초반 프레임 분포(전체 {len(allr)}사이클): " + ', '.join(f"{k}개 {sum(1 for x in allr if x['n_early_ascent']==k)}" for k in range(0, 4)))
        lines.append(f"바닥 프레임 분포: " + ', '.join(f"{k}개 {sum(1 for x in allr if x['n_bottom']==k)}" for k in range(1, 6)))
        lines.append('')

    if v_rows:
        lines.append('## 관절 가시성 (집계 창, vis ≥ 0.5 비율 %) — 서 있음/바닥')
        by = {}
        for r in v_rows:
            by.setdefault((r['kst'], r['set_id'], r['front']), {})[r['joint']] = r
        joints = ['l_hip', 'r_hip', 'l_knee', 'r_knee', 'l_ank', 'r_ank', 'l_heel', 'r_heel', 'l_toe', 'r_toe']
        hdr = ['세트', '카메라'] + joints
        rows = []
        for (k, sid, fr), jd in sorted(by.items()):
            rows.append([f'{k} {sid[-8:]}', '전면' if fr else '후면'] +
                        [f"{jd[j]['stand_pct_ge05']:.0f}/{jd[j]['bottom_pct_ge05']:.0f}" if j in jd else '—' for j in joints])
        lines.append(md_table(hdr, rows)); lines.append('')
    with open(out_path('A4_1_summary.md'), 'w', encoding='utf-8') as f:
        f.write('\n'.join(lines) + '\n')
    print('\n'.join(lines))


if __name__ == '__main__':
    main()
