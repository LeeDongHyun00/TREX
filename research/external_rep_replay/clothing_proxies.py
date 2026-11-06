# -*- coding: utf-8 -*-
"""세트 로그에서 '옷 불확실' 대리 지표를 세트별로 낸다 (docs/LOOSE_CLOTHING_RESEARCH.md §3).

  python research/external_rep_replay/clothing_proxies.py <저장소 루트> [--exercise 덤벨 컬 ...] [--min-frames 40]

세트마다:
  - 관절 쌍 가시성 중앙값(min(L, R)) — 어깨·팔꿈치·손목·골반·무릎·발목
  - 2D 관절 흔들림: 연속 판정 프레임(300 ms) 사이 이동 ÷ 2D 몸통의 p90 — 팔꿈치·무릎·발목
  - 월드 3D 마디 길이 CV(표준편차 ÷ 평균) — 뼈는 안 변하니 0 이 이상. 옷·가림이 올린다는 가설(§3.1)
옷 라벨은 로그에 없다 — 자가 라벨 메모(`labels/set_labels.jsonl` 의 note)에 "옷: …" 을 적어 두고 세트 id 로 견준다.
"""
import argparse, glob, json, math, statistics as st, sys

sys.stdout.reconfigure(encoding="utf-8")

PAIRS = dict(sh=(11, 12), el=(13, 14), wr=(15, 16), hip=(23, 24), kn=(25, 26), an=(27, 28))
SEG = {"상완L": (11, 13), "전완L": (13, 15), "상완R": (12, 14), "전완R": (14, 16),
       "허벅지L": (23, 25), "정강이L": (25, 27), "허벅지R": (24, 26), "정강이R": (26, 28), "몸통": (11, 23)}
ASPECT = 0.75
MIN_VIS = 0.5


def med(v):
    return st.median(v) if v else float("nan")


def cv(v):
    return (st.pstdev(v) / st.mean(v)) if len(v) >= 10 and st.mean(v) > 0 else float("nan")


def torso2d(xy):
    sx = (xy[22] + xy[24]) / 2 * ASPECT; sy = (xy[23] + xy[25]) / 2
    hx = (xy[46] + xy[48]) / 2 * ASPECT; hy = (xy[47] + xy[49]) / 2
    return math.hypot(sx - hx, sy - hy)


def jitter_p90(frames, idx):
    d = []; prev = None
    for f in frames:
        xy = f["xy"]; vis = f["vis"]
        if vis[idx] < MIN_VIS:
            prev = None; continue
        t = torso2d(xy)
        if t < 0.08:
            prev = None; continue
        p = (xy[idx * 2] * ASPECT, xy[idx * 2 + 1])
        if prev is not None:
            d.append(math.hypot(p[0] - prev[0], p[1] - prev[1]) / t)
        prev = p
    if not d:
        return float("nan")
    d.sort(); return d[int(len(d) * 0.9)]


def seg_cv(frames, a, b):
    ls = []
    for f in frames:
        if f["vis"][a] < MIN_VIS or f["vis"][b] < MIN_VIS:
            continue
        w = f["w"]
        ls.append(math.dist(w[a * 3:a * 3 + 3], w[b * 3:b * 3 + 3]))
    return cv(ls)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("root")
    ap.add_argument("--exercise", nargs="*", default=None)
    ap.add_argument("--min-frames", type=int, default=40)
    args = ap.parse_args()
    paths = sorted(set(glob.glob(f"{args.root}/research/aihub_fitness/outputs/logs/sets-*.jsonl") +
                       glob.glob(f"{args.root}/data/phone/**/sets-*.jsonl", recursive=True)))
    seen = set(); rows = []
    for p in paths:
        for line in open(p, encoding="utf-8"):
            try:
                d = json.loads(line)
            except Exception:
                continue
            ex = d.get("exercise")
            if args.exercise and ex not in args.exercise:
                continue
            if d.get("set_id") in seen:
                continue
            seen.add(d["set_id"])
            fr = [f for f in d.get("frames", []) if f.get("vis") and f.get("xy") and f.get("w")]
            if len(fr) < args.min_frames:
                continue
            vis = {k: med([min(f["vis"][a], f["vis"][b]) for f in fr]) for k, (a, b) in PAIRS.items()}
            jit = {k: jitter_p90(fr, a) for k, (a, b) in PAIRS.items() if k in ("el", "kn", "an")}
            seg = {k: seg_cv(fr, a, b) for k, (a, b) in SEG.items()}
            rows.append((d["created_at"][:16], ex, (d.get("view") or {}).get("class"), d.get("set_id", "")[:8], len(fr),
                         (d.get("reps") or {}).get("count"), vis, jit, seg))
    rows.sort()
    names = list(SEG)
    print(f"{'시각':16} {'종목':8} {'뷰':7} {'set':8} {'n':>4} {'회':>3} | vis sh el wr hip kn an | jit90 el kn an | 월드 마디 CV " + " ".join(names))
    for t, ex, v, sid, n, c, vis, jit, seg in rows:
        print(f"{t:16} {ex:8} {str(v):7} {sid:8} {n:4} {str(c):>3} | "
              + " ".join(f"{vis[k]:.2f}" for k in PAIRS) + " | "
              + " ".join(f"{jit[k]:.3f}" for k in ("el", "kn", "an")) + " | "
              + " ".join(f"{seg[k]:.2f}" for k in names))


if __name__ == "__main__":
    main()
