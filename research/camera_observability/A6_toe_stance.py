"""A6 — 넓게 서면 발끝 각이 부풀어 읽히는 현상의 원인 (2026-09-25 후속).

사용자 확인: 12:19 세트 7·8회(넓게)의 발끝은 '시작 자세 기준 그대로'였다. 그런데 앱 판독은 +24~27° 벌어졌고,
기하(원근)로 설명되는 몫은 +2~4° 뿐이다(A1·A4). 나머지가 어디서 오는지 두 데이터로 본다.

1) 폰 12:19 세트(좌표 로그): 조용히 서 있는 프레임에서 구간(정답)별로 발끝 판독(발목→발끝, 뒤꿈치→발끝),
   정강이 기울기(무릎→발목 선의 바깥 기울기), 발 너비, 발 가시성을 비교한다.
   가설 — MediaPipe 가 가려진·작은 발의 방향을 정강이 방향에서 추론한다: 넓게 서면 정강이가 바깥으로 기울고
   (발은 그대로여도) 발 방향이 따라 돌아간 것으로 읽힌다.
2) AIHub 정면(C) 706클립: MediaPipe 2D 발끝 판독을 GT 3D 발 방향 + GT 발 너비로 회귀하고, 같은 뷰의 GT 2D 판독
   (사람 주석 = 기하만)과 기울기를 비교한다. MediaPipe 쪽의 발 너비 계수가 더 크면 기하 밖 편향이다.

출력: outputs/A6/summary.md
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.stdout.reconfigure(encoding="utf-8")
ROOT = Path(__file__).resolve().parents[2]
OUT = Path(__file__).resolve().parent / "outputs" / "A6"
OUT.mkdir(parents=True, exist_ok=True)
W, H = 480, 640
SEG = ["정상 1·2", "발끝 안 3·4", "발끝 밖 5·6", "넓게 7·8(발끝 시작 그대로)", "정상 9·10"]
lines: list[str] = ["# A6 — 넓은 스탠스의 발끝 부풀림: 원인 확인\n"]


def log(s: str = "") -> None:
    print(s)
    lines.append(s)


# ---------------------------------------------------------------- 1) 폰 12:19
def load_set(set_id: str) -> dict:
    for p in sorted((ROOT / "data" / "phone").glob("*/sets-20260925.jsonl")):
        for l in p.open(encoding="utf-8"):
            d = json.loads(l)
            if d["set_id"] == set_id:
                return d
    raise SystemExit(f"세트 없음: {set_id}")


d = load_set("20260925T031938-6e1e8e89")
fr = d["frames"]
tab = pd.read_csv(Path(__file__).resolve().parent / "outputs" / "A4" / "vars_1219_frames.csv", encoding="utf-8-sig")
assert len(tab) == len(fr), (len(tab), len(fr))
xy = np.array([f["xy"] for f in fr], float).reshape(len(fr), 33, 2) * [W, H]
vis = np.array([f["vis"] for f in fr], float)
wd = np.array([f["w"] for f in fr], float).reshape(len(fr), 33, 3)
up = np.array([f["up"] for f in fr], float)

# 사람 왼쪽이 화면 어느 쪽인가(정면이면 +1: 왼골반 x > 오른골반 x)
lat = np.sign(xy[:, 23, 0] - xy[:, 24, 0])
IDX = {"L": (25, 27, 29, 31, 1.0), "R": (26, 28, 30, 32, -1.0)}   # 무릎, 발목, 뒤꿈치, 발끝, 부호
rows = []
for s, (K, A, He, T, sg) in IDX.items():
    out_x = sg * lat  # 이 다리의 '바깥' 이 화면 x 에서 +1 인지 −1 인지
    shin = xy[:, A] - xy[:, K]                       # 무릎→발목
    shin_abd = np.degrees(np.arctan2(out_x * shin[:, 0], shin[:, 1]))   # 정강이 아래끝이 바깥으로 기운 각(+)
    at = xy[:, T] - xy[:, A]
    toe_img = np.degrees(np.arctan2(out_x * at[:, 0], np.abs(at[:, 1]) + 1e-9))
    ht = xy[:, T] - xy[:, He]
    heeltoe_img = np.degrees(np.arctan2(out_x * ht[:, 0], np.abs(ht[:, 1]) + 1e-9))
    # 월드: 수평면에 눕힌 무릎→발목(정강이) 바깥 기울기 — 몸 좌우축 기준
    rows.append(pd.DataFrame({"side": s, "i": np.arange(len(fr)), "shin_abd2d": shin_abd, "toe2d": toe_img,
                              "heeltoe2d": heeltoe_img, "vis_heel": vis[:, He], "vis_toe": vis[:, T],
                              "ank_x": xy[:, A, 0]}))
F = pd.concat(rows).merge(tab[["i", "segment", "quiet", "2D_stance"]], on="i")
Q = F[F["quiet"] == 1]
log("## 1. 폰 12:19 세트 — 조용히 서 있는 프레임, 구간 중앙값 (좌·우)\n")
log("| 구간 | n | 발 너비(2D) | 정강이 바깥 기울기° | 발목→발끝 판독° | 뒤꿈치→발끝 판독° | 뒤꿈치 vis | 발끝 vis |")
log("|---|---|---|---|---|---|---|---|")
med = {}
for sgi, name in enumerate(SEG):
    g = Q[Q["segment"] == sgi]
    if g.empty:
        continue
    m = g.groupby("side")[["shin_abd2d", "toe2d", "heeltoe2d", "vis_heel", "vis_toe"]].median()
    st = g["2D_stance"].median()
    med[sgi] = m
    f = lambda c, k=1: f"{m.loc['L', c]:.{k}f} · {m.loc['R', c]:.{k}f}"
    log(f"| {name} | {len(g)//2} | {st:.2f} | {f('shin_abd2d')} | {f('toe2d')} | {f('heeltoe2d')} | {f('vis_heel', 2)} | {f('vis_toe', 2)} |")

if 0 in med and 3 in med and 4 in med:
    base = (med[0] + med[4]) / 2
    dw = med[3] - base
    log("\n넓게(7·8) − 정상(1·2·9·10 평균), 좌 · 우:")
    for c, nm in [("shin_abd2d", "정강이 바깥 기울기"), ("toe2d", "발목→발끝 판독"), ("heeltoe2d", "뒤꿈치→발끝 판독")]:
        log(f"- {nm}: {dw.loc['L', c]:+.1f}° · {dw.loc['R', c]:+.1f}°")
    dt = med[2] - base
    log("발끝 밖(5·6) − 정상, 좌 · 우 (실제로 발을 돌린 구간 — 대조):")
    for c, nm in [("shin_abd2d", "정강이 바깥 기울기"), ("toe2d", "발목→발끝 판독"), ("heeltoe2d", "뒤꿈치→발끝 판독")]:
        log(f"- {nm}: {dt.loc['L', c]:+.1f}° · {dt.loc['R', c]:+.1f}°")

# 프레임 수준: 정상·넓게 구간(발끝을 돌리지 않은 구간)만 모아 발끝 판독 ~ 정강이 기울기
NT = Q[Q["segment"].isin([0, 3, 4])]
log("\n발끝을 돌리지 않은 구간(정상·넓게)의 프레임 상관 — 발끝 판독 vs 정강이 기울기 / vs 발 너비:")
for s in ("L", "R"):
    g = NT[NT["side"] == s].dropna(subset=["toe2d", "shin_abd2d", "2D_stance"])
    r1 = np.corrcoef(g["toe2d"], g["shin_abd2d"])[0, 1]
    r2 = np.corrcoef(g["toe2d"], g["2D_stance"])[0, 1]
    b = np.polyfit(g["shin_abd2d"], g["toe2d"], 1)[0]
    log(f"- {s}: r(발끝, 정강이) = {r1:.2f} (기울기 {b:.2f}°/°) · r(발끝, 발 너비) = {r2:.2f} · n = {len(g)}")

# ---------------------------------------------------------------- 2) AIHub 정면
log("\n## 2. AIHub 정면(C) — MediaPipe 발끝 판독의 발 너비 의존 (서 있음 프레임, 클립 단위)\n")
R = pd.read_parquet(Path(__file__).resolve().parent / "outputs" / "A2" / "A2_rep_values.parquet")
gt = R[(R["rep"] == "GT3D")].set_index("clip_id")[["toe", "stance"]].rename(columns={"toe": "toe3", "stance": "st3"})


def fit(rep: str, variant: str, view: str = "C"):
    y = R[(R["rep"] == rep) & (R["variant"] == variant) & (R["view"] == view)].set_index("clip_id")["toe"]
    j = gt.join(y.rename("y")).dropna()
    X = np.column_stack([np.ones(len(j)), j["toe3"], j["st3"]])
    beta, *_ = np.linalg.lstsq(X, j["y"].to_numpy(), rcond=None)
    res = j["y"].to_numpy() - X @ beta
    # 부트스트랩 95 % 구간 — 발 너비 계수
    rng = np.random.default_rng(7)
    bs = []
    for _ in range(400):
        k = rng.integers(0, len(j), len(j))
        bb, *_ = np.linalg.lstsq(X[k], j["y"].to_numpy()[k], rcond=None)
        bs.append(bb[2])
    lo, hi = np.percentile(bs, [2.5, 97.5])
    return beta, (lo, hi), len(j), float(np.std(res)), j


log(f"GT 3D 발 너비 분포(발목 간격 ÷ 어깨 간격): p5 {gt['st3'].quantile(.05):.2f} · p50 {gt['st3'].median():.2f} · p95 {gt['st3'].quantile(.95):.2f}\n")
log("모형: 판독 = a + b·(GT 3D 발 방향) + c·(GT 3D 발 너비). c = 발 너비 1.0 늘 때 판독이 몇 ° 변하나.\n")
log("| 판독 | n | b (발 방향) | c (발 너비) °/1.0 | c 95 % | 잔차 SD° |")
log("|---|---|---|---|---|---|")
res = {}
for rep, var, nm in [("GT2D", "-", "GT 2D 주석(기하만, 발끝 = 발 중간점)"), ("MP2D", "full", "MediaPipe 2D(full)"),
                     ("MP2D", "app", "MediaPipe 2D(앱 480×640)"), ("MPW0", "full", "MediaPipe 월드(full)")]:
    try:
        beta, ci, n, sd, j = fit(rep, var)
    except Exception as e:  # noqa: BLE001
        log(f"| {nm} | — | — | — | — | {e} |")
        continue
    res[nm] = beta
    log(f"| {nm} | {n} | {beta[1]:.2f} | {beta[2]:+.1f} | {ci[0]:+.1f} ~ {ci[1]:+.1f} | {sd:.1f} |")

log("\n해석 메모는 보고서(A6_REPORT.md)에 쓴다.")
(OUT / "summary.md").write_text("\n".join(lines), encoding="utf-8")
