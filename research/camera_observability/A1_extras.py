# -*- coding: utf-8 -*-
"""A1 보조 분석 — 격자 밖의 세 질문.

(a) 넓은 스탠스의 '가짜 발끝 벌어짐': 낮은 정면 카메라에서 발끝 각 편향이 발목 간격에 비례하는가
    (실기기 관측 "넓게 서면 발끝 그대로여도 +20~28°", SQUAT_FAULT_CATALOG §1 #4 의 기하 성분).
(b) 발이 붙어 있을 때 잡음이 만드는 가짜 발 이동(R_foot_drift) 분포.
(c) 발끝 지렛대 길이: 가상 발끝(15 cm) 대신 AIHub 'Foot'(발목 앞 6 cm)을 쓰면 잡음에 얼마나 약한가.

출력: outputs/A1/extras.md
"""
from __future__ import annotations

import os
import sys
import warnings

import numpy as np
from scipy.stats import spearmanr

warnings.filterwarnings("ignore")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import A1_common as C  # noqa: E402

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")


def rho(a, b):
    m = np.isfinite(a) & np.isfinite(b)
    return float(spearmanr(a[m], b[m])[0])


def main():
    D = C.load_squat()
    sc = C.build_scene(D["arr"])
    tr = C.truth(sc)
    k_foot = float(np.nanmedian(C.TOE_LEN_H / tr["rep"]["_legS"]))
    rng = np.random.default_rng(7)
    noise = rng.standard_normal(sc.P.shape[:3] + (2,))
    g = lambda n: sc.P[:, :, C.J[n]]
    stance_m = C.mmean(C.nrm(C.horiz(g("LAnkle") - g("RAnkle"))), sc.S)
    L = ["# A1 보조 분석\n"]

    # (a) 가짜 발끝 벌어짐 — 편향 ~ 발목 간격 회귀
    L += ["## (a) 발끝 각 편향과 발목 간격 (방위 0°, σ0, 68°, 가운데)\n",
          "편향 = 2D 발끝 각 − 3D 발끝 각(서 있을 때, 양쪽 평균). 기울기 = 발목 간격 10 cm 당 편향 변화(°).\n",
          "| h | d | img_angle 편향 중앙값 | 기울기(°/10 cm) | r | body_prop 편향 | 기울기 | r |", "|---|---|---|---|---|---|---|---|"]
    for h in (0.05, 0.15, 0.3, 0.6, 1.0):
        for d in (2.0, 2.5, 4.0):
            cam = C.make_cam(sc, 0, h, d, 68, False)
            uv, _ = C.project(cam, sc.P)
            f2 = C.feats2d(sc, uv, cam, k_foot)
            cells = []
            for c in ("img_angle", "body_prop"):
                b = f2["rep"]["S_toe"][c] - tr["rep"]["S_toe"]
                m = np.isfinite(b) & np.isfinite(stance_m)
                sl = np.polyfit(stance_m[m], b[m], 1)[0] * 0.10
                cells += [f"{np.median(b[m]):+.1f}", f"{sl:+.1f}", f"{np.corrcoef(stance_m[m], b[m])[0, 1]:.2f}"]
            L.append(f"| {h} | {d} | " + " | ".join(cells) + " |")
    L.append(f"\n발목 간격(3D, 서 있을 때) p5/p50/p95 = {np.nanpercentile(stance_m, 5):.2f}/{np.nanpercentile(stance_m, 50):.2f}/{np.nanpercentile(stance_m, 95):.2f} m (n {int(np.isfinite(stance_m).sum())})")

    # (b) 가짜 발 이동
    t = tr["rep"]["R_foot_drift"]
    still = t < 0.10
    L += ["", f"## (b) 발이 붙어 있는 반복(3D 발목 이동 < 어깨 너비의 0.10, n {int(still.sum())}/{len(t)})의 2D 발 이동\n",
          "| 방위 | h | σ | 2D 이동 p50 | p95 | > 0.30 비율 |", "|---|---|---|---|---|---|"]
    for az, h in ((0, 0.6), (0, 0.15), (90, 0.6)):
        cam = C.make_cam(sc, az, h, 2.5, 68, False)
        uv0, _ = C.project(cam, sc.P)
        for sg in (0, 2, 4, 8):
            f2 = C.feats2d(sc, uv0 + sg * noise, cam, k_foot)
            e = f2["rep"]["R_foot_drift"]["leg_norm"] * float(np.nanmedian(tr["rep"]["_legS"] / tr["rep"]["_shW"]))
            e = e[still]
            L.append(f"| {az}° | {h} | {sg} | {np.nanmedian(e):.3f} | {np.nanpercentile(e, 95):.3f} | {np.nanmean(e > 0.30):.3f} |")
    L.append("\n(2D 이동은 다리 길이로 정규화한 뒤 모집단 다리/어깨 비로 어깨 단위로 환산 — 측면에서도 분모가 무너지지 않게)")

    # (c) 발끝 지렛대 길이
    P6 = sc.P.copy()
    P6[:, :, C.J["LToe"]] = g("LFoot")
    P6[:, :, C.J["RToe"]] = g("RFoot")
    L += ["", "## (c) 발끝 지렛대 — 가상 발끝 15 cm vs AIHub 'Foot' 6 cm (S_toe img_angle ρ, d 2.5, 68°)\n",
          "| 방위 | h | 발끝 | σ0 | σ2 | σ4 | σ8 |", "|---|---|---|---|---|---|---|"]
    for az, h in ((0, 0.6), (0, 0.15), (40, 0.6)):
        cam = C.make_cam(sc, az, h, 2.5, 68, False)
        for lab, PP in (("15 cm", sc.P), ("6 cm", P6)):
            uv0, _ = C.project(cam, PP)
            rs = []
            for sg in (0, 2, 4, 8):
                sc2 = C.Scene(**{**sc.__dict__, "P": PP})
                f2 = C.feats2d(sc2, uv0 + sg * noise, cam, k_foot)
                rs.append(rho(tr["rep"]["S_toe"], f2["rep"]["S_toe"]["img_angle"]))
            L.append(f"| {az}° | {h} | {lab} | " + " | ".join(f"{x:.2f}" for x in rs) + " |")
    # (d) 초광각으로 '가까이' 두기 — 같은 위치에선 화각이 배율만 바꾸므로, 초광각의 실제 차이는 가까이 둘 수 있다는 것
    L += ["", "## (d) 초광각을 가까이 둘 때 — 100° d 1.2/1.5 m vs 68° d 2.0/2.5 m (방위 0°, h 0.15·0.6, 가운데)\n",
          "편향은 σ0, ρ 는 σ4. 프레이밍 = 전신(관절·가상 발끝·머리 꼭대기)이 다 들어오는 클립 비율.\n",
          "| 화각 | d | h | 프레이밍 | 몸 px | S_stance x 편향 | S_toe body_prop 편향 | R_depth v_ratio 편향 | ρ S_toe img | ρ F_knee recon2d | ρ R_knee recon2d | ρ F_heel toe_rel |",
          "|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for fov, d in ((68, 2.0), (68, 2.5), (100, 1.2), (100, 1.5), (100, 2.0)):
        for h in (0.15, 0.6):
            cam = C.make_cam(sc, 0, h, d, fov, False)
            uv0, Z = C.project(cam, sc.P)
            inside = (uv0[..., 0] >= 0) & (uv0[..., 0] <= C.W) & (uv0[..., 1] >= 0) & (uv0[..., 1] <= C.H) & (Z > 0)
            inside |= ~np.isfinite(uv0[..., 0])
            fit = inside.all(axis=(1, 2)).mean()
            bpx = np.nanmedian(C.mmean(uv0[:, :, C.J["LToe"], 1] - uv0[:, :, C.J["HeadTop"], 1], sc.S))
            f0 = C.feats2d(sc, uv0, cam, k_foot)["rep"]
            f4 = C.feats2d(sc, uv0 + 4 * noise, cam, k_foot)["rep"]
            b = [np.nanmedian(f0[v][c] - tr["rep"][v]) for v, c in (("S_stance", "x_ratio"), ("S_toe", "body_prop"), ("R_depth", "v_ratio"))]
            r = [rho(tr["rep"][v], f4[v][c]) for v, c in (("S_toe", "img_angle"), ("F_knee_foot", "recon2d"), ("R_knee_min", "recon2d"), ("F_heel", "toe_rel"))]
            L.append(f"| {fov}° | {d} | {h} | {fit:.3f} | {bpx:.0f} | {b[0]:+.3f} | {b[1]:+.1f} | {b[2]:+.1f} | " + " | ".join(f"{x:.2f}" for x in r) + " |")
    # (e) 무릎 정렬 임계의 높이 이전 — 정면 영상 knee_out2d 로 '발과 무릎의 방향 일치' 위반 판별
    y = D["viol"]["knee"]
    L += ["", "## (e) 무릎 정렬 임계의 높이 이전 — knee_out2d(바닥 구간 평균, 방위 0°, d 2.5, 68°, σ0)\n",
          "각 높이에서 정상/위반 중앙값과 Youden 임계, 그리고 h 1.0 에서 정한 임계를 그대로 썼을 때 정상 오탐률·검출률.\n",
          "| h | 정상 중앙값 | 위반 중앙값 | Youden 임계 | 균형정확도(자기 임계) | h1.0 임계 → 정상 오탐 / 검출 |", "|---|---|---|---|---|---|"]
    ref_thr = None
    res = {}
    for h in (1.0, 0.05, 0.15, 0.3, 0.6, 1.4):
        cam = C.make_cam(sc, 0, h, 2.5, 68, False)
        uv, _ = C.project(cam, sc.P)
        x = C.feats2d(sc, uv, cam, k_foot)["rep"]["F_knee_foot"]["knee_out2d"]
        m = np.isfinite(x)
        xs = np.sort(np.unique(x[m]))
        best = (0, None)
        for thr in xs:
            tpr = np.mean(x[m & y] < thr)
            tnr = np.mean(x[m & ~y] >= thr)
            if (tpr + tnr) / 2 > best[0]:
                best = ((tpr + tnr) / 2, thr)
        if h == 1.0:
            ref_thr = best[1]
        fpr = np.mean(x[m & ~y] < ref_thr)
        tpr = np.mean(x[m & y] < ref_thr)
        res[h] = f"| {h} | {np.median(x[m & ~y]):.3f} | {np.median(x[m & y]):.3f} | {best[1]:.3f} | {best[0]:.3f} | {fpr:.3f} / {tpr:.3f} |"
    L += [res[h] for h in (0.05, 0.15, 0.3, 0.6, 1.0, 1.4)]
    L.append(f"\n(n 정상 {int((~y).sum())} · 위반 {int(y.sum())} 클립)")
    (C.OUT / "extras.md").write_text("\n".join(L), encoding="utf-8")
    print("\n".join(L))


if __name__ == "__main__":
    main()
