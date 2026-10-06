# -*- coding: utf-8 -*-
"""한 다리 계열(사이드 런지·크로스 런지·스탠딩 니업·스탠딩 사이드 크런치) 세트 로그의 회별 극점 표 — spec §98·설계 §1a 의 진단 스크립트.

    python single_leg_diag.py <sets-YYYYMMDD.jsonl> [종목 이름 일부] [--engine legcycle_v3_beta]

다리마다 신호(사이드 무릎각 / 크로스 발목 교차 / 니업·크런치 허벅지각)가 기준을 벗어난 구간을 한 회로 잘라, 극점의 무릎·허벅지·몸통 전후 기울기(출발·극점·최대)·골반 높이(hip_height_rel 최소)·
골반 하강(2D)·무릎-발끝(발 방향 투영, 발끝 z 편향 있음)·측굴(어깨선 − 골반선)·팔꿈치–무릎·무릎 바깥 위치(knee_lat)와 앱의 판정(REP/REJ)을 한 줄로 낸다. 띠를 바꾸기 전에 이 표를 본다."""
import json, sys, math, statistics as st
args = [a for a in sys.argv[1:] if not a.startswith("--")]
ENGINE = next((sys.argv[i + 1] for i, a in enumerate(sys.argv) if a == "--engine" and i + 1 < len(sys.argv)), None)
if not args: print(__doc__); sys.exit(2)
p = args[0]
sets = [json.loads(l) for l in open(p, encoding="utf-8")]
v2 = [s for s in sets if str((s.get("reps") or {}).get("engine", "")).startswith(ENGINE or "legcycle_")]
ONLY = args[1] if len(args) > 1 else None
NOSE, LEAR, REAR, LSH, RSH, LEL, REL, LHIP, RHIP, LKN, RKN, LAN, RAN, LHEEL, RHEEL, LFT, RFT = 0, 7, 8, 11, 12, 13, 14, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32
def med(xs):
    xs = [x for x in xs if x is not None]
    return st.median(xs) if xs else None
def v3(w, i): return (w[3*i], w[3*i+1], w[3*i+2])
def sub(a, b): return (a[0]-b[0], a[1]-b[1], a[2]-b[2])
def dot(a, b): return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]
def cross(a, b): return (a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0])
def norm(a): return math.sqrt(dot(a, a))
def unit(a):
    n = norm(a); return (a[0]/n, a[1]/n, a[2]/n) if n > 1e-6 else None
def flat(a, up):  # 수평 성분
    d = dot(a, up); return (a[0]-d*up[0], a[1]-d*up[1], a[2]-d*up[2])
def ang2d(a, b, c, asp):  # 2D 각 (b 꼭짓점)
    ax, ay = (a[0]-b[0])*asp, a[1]-b[1]; cx, cy = (c[0]-b[0])*asp, c[1]-b[1]
    na, nc = math.hypot(ax, ay), math.hypot(cx, cy)
    if na < 1e-6 or nc < 1e-6: return None
    return math.degrees(math.acos(max(-1, min(1, (ax*cx+ay*cy)/(na*nc)))))
def extra(fr, asp):
    """프레임 하나의 추가 기하."""
    out = {}
    w = fr.get("w"); xy = fr.get("xy"); up = fr.get("up")
    if not w or not xy or not up: return out
    up = unit(tuple(up))
    for s, hip, kn, an, ft, sh, el in (("L", LHIP, LKN, LAN, LFT, LSH, LEL), ("R", RHIP, RKN, RAN, RFT, RSH, REL)):
        H, K, A, T = v3(w, hip), v3(w, kn), v3(w, an), v3(w, ft)
        shin = norm(sub(K, A)); footH = flat(sub(T, A), up); fl = norm(footH)
        if shin > 0.01 and fl > 0.01:
            fd = unit(footH)
            out[f"ktoe_{s}"] = (dot(sub(K, A), fd) - fl) / shin   # +: 무릎이 발끝보다 발 방향으로 더 나감(정강이 길이 단위)
            out[f"kfwdfoot_{s}"] = dot(sub(K, A), fd) / shin
            out[f"footlen_{s}"] = fl / shin
        # 2D
        P = lambda i: (xy[2*i], xy[2*i+1])
        out[f"knee2d_{s}"] = ang2d(P(hip), P(kn), P(an), asp)
        torso2d = math.hypot((P(sh)[0]-P(hip)[0])*asp, P(sh)[1]-P(hip)[1])
        out[f"flank_{s}"] = torso2d
    # 측굴: 어깨선 기울기 − 골반선 기울기 (°), 사용자 왼쪽이 아래로 +
    P = lambda i: (xy[2*i]*asp, xy[2*i+1])
    sh = math.degrees(math.atan2(P(LSH)[1]-P(RSH)[1], P(LSH)[0]-P(RSH)[0]))
    hp = math.degrees(math.atan2(P(LHIP)[1]-P(RHIP)[1], P(LHIP)[0]-P(RHIP)[0]))
    out["latflex_L"] = sh - hp; out["latflex_R"] = hp - sh
    out["shtilt"] = sh; out["hiptilt"] = hp
    out["hip_y"] = (P(LHIP)[1]+P(RHIP)[1])/2
    return out

for s in v2:
    ex = s["exercise"]
    if ONLY and ONLY not in ex: continue
    frames = s["frames"]; asp = s["image"]["w"]/s["image"]["h"]
    cfg = s["reps"]["config"]; standing = cfg.get("standing") or {}
    reps_t = s["reps"]["t_ms"]; rej = s["reps"]["rejected"]
    sides_logged = [r.get("side") for r in s["rep_form"]["reps"]]
    F = [(f["t_ms"], {**(f.get("features") or {}), **extra(f, asp)}) for f in frames]
    first = [f for t, f in F if t <= 1500 and f]
    base = {k: med([f.get(k) for f in first]) for k in ["torso_pitch", "hip_y", "flank_L", "flank_R", "latflex_L", "latflex_R", "knee_L", "knee_R", "thigh_L", "thigh_R", "leg_torso2d"]}
    print("=" * 110); print(ex, "| count", s["reps"]["count"], "| base", {k: (round(v, 2) if v is not None else None) for k, v in base.items()})
    if "런지" in ex and "사이드" in ex:
        legs = [("L", "knee_L"), ("R", "knee_R")]; thr = lambda k, b: k < b - 30
    elif "크로스" in ex:
        legs = [("X", "leg_cross")]; thr = lambda v, b: v < -0.15
    else:
        legs = [("L", "thigh_L"), ("R", "thigh_R")]; thr = lambda v, b: v < b - 25
    exc = []
    for side, key in legs:
        b = standing.get(key, base.get(key)) if key != "leg_cross" else 0
        cur = None
        for i, (t, f) in enumerate(F):
            v = f.get(key); inside = v is not None and thr(v, b)
            if inside and cur is None: cur = [i, i]
            elif inside: cur[1] = i
            elif cur is not None and v is not None: exc.append((side, key, cur[0], cur[1])); cur = None
        if cur is not None: exc.append((side, key, cur[0], cur[1]))
    exc.sort(key=lambda e: e[2])
    fmt = lambda v, w=5, d=1: (f"{v:{w}.{d}f}" if isinstance(v, (int, float)) else f"{'-':>{w}}")
    print(f"{'t0':>6}-{'t1':>6} s | {'tp@dep':>6} {'tp@min':>6} {'tpMax':>5} | {'hipDrop':>7} | {'ktoeMx':>6} {'kfwdF':>5} {'ftlen':>5} | {'k2d':>5} {'k3d':>5} | {'flankR':>6} {'latFx':>5} {'ek':>5} {'kLat':>5} | app")
    for side, key, i0, i1 in exc:
        a = max(0, i0 - 1); z = min(len(F) - 1, i1 + 1)
        win = [f for t, f in F[a:z + 1] if f]; t0, t1 = F[i0][0], F[i1][0]
        tp_dep = F[a][1].get("torso_pitch"); tpmax = max(f.get("torso_pitch", -99) for f in win)
        hipdrop = (max(f.get("hip_y", -9) for f in win) - base["hip_y"]) / base["leg_torso2d"] if base["hip_y"] is not None else None
        if side == "X":
            kmin = min(min(f.get("knee_L", 999), f.get("knee_R", 999)) for f in win)
            fk = min(win, key=lambda f: min(f.get("knee_L", 999), f.get("knee_R", 999)))
            tp_min = fk.get("torso_pitch")
            # 뒷발(= 움직인 다리) 추정: 무릎이 더 굽은 쪽이 앞다리, 뒷다리는 반대
            s_front = "L" if fk.get("knee_L", 999) < fk.get("knee_R", 999) else "R"
            ktoe = max(f.get(f"ktoe_{s_front}", -9) for f in win); kff = fk.get(f"kfwdfoot_{s_front}"); ftl = fk.get(f"footlen_{s_front}")
            k2d = fk.get(f"knee2d_{s_front}"); k3d = kmin; flr = None; lfx = None; ek = None; klat = None; mv = s_front + "f"
        else:
            mv = side
            fth = min(win, key=lambda f: f.get(f"thigh_{side}", 999)); fkn = min(win, key=lambda f: f.get(f"knee_{side}", 999))
            top = fth if "니업" in ex or "크런치" in ex else fkn
            tp_min = top.get("torso_pitch")
            ktoe = max(f.get(f"ktoe_{side}", -9) for f in win); kff = fkn.get(f"kfwdfoot_{side}"); ftl = fkn.get(f"footlen_{side}")
            k2d = top.get(f"knee2d_{side}"); k3d = top.get(f"knee_{side}")
            flr = (top.get(f"flank_{side}") / base[f"flank_{side}"]) if top.get(f"flank_{side}") and base.get(f"flank_{side}") else None
            lfx = (top.get(f"latflex_{side}") - base[f"latflex_{side}"]) if top.get(f"latflex_{side}") is not None and base.get(f"latflex_{side}") is not None else None
            ek = min(f.get(f"elbow_knee_{side}", 9) for f in win); klat = top.get(f"knee_lat_{side}")
        app = ""
        for rt, sd in zip(reps_t, sides_logged):
            if t0 - 300 <= rt <= t1 + 2500: app += f"REP@{rt}{sd or '?'} "
        for r in rej:
            if t0 - 300 <= r["t_ms"] <= t1 + 2500: app += f"REJ:{r['feature']} "
        hhr = min(f.get("hip_height_rel", 9) for f in win)
        print(f"{t0:6d}-{t1:6d} {mv:2}| hhr={fmt(hhr,4,2)} {fmt(tp_dep,6)} {fmt(tp_min,6)} {fmt(tpmax)} | {fmt(hipdrop,7,2)} | {fmt(ktoe,6,2)} {fmt(kff,5,2)} {fmt(ftl,5,2)} | {fmt(k2d)} {fmt(k3d)} | {fmt(flr,6,2)} {fmt(lfx)} {fmt(ek,5,2)} {fmt(klat,5,2)} | {app}")
