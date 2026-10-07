# -*- coding: utf-8 -*-
"""바닥 반복 계열(크런치·라잉 레그 레이즈) 세트의 회별 극점·사유 표 — spec §99·docs/FLOOR_FAMILY_DESIGN.md §7.2 의 진단 스크립트(single_leg_diag.py 형식).

    python floor_diag.py <sets-YYYYMMDD.jsonl> [종목 이름 일부]                 휴대폰 세트 로그(frames[].features 의 fc_* — 앱이 카운터에 넣은 값)
    python floor_diag.py --cap <capture.cap> [--reasons default|all|none|사유,…]  랜드마크 캡처를 재생기 바닥 경로로 돌려 같은 표(--dump-features + 한 줄 매니페스트)

사이클 신호(크런치 fc_trunk_lift, 레그 레이즈 fc_thigh)가 누운 기준을 벗어난 구간(크런치는 귀 들림 fc_ear_lift 만 벗어난 '목만' 구간도)을 한 줄로 잘라
정점·진폭·귀 들림·축각(fc_axis_h, 출발 프레임)·측면도(fc_yaw, 정점)·상단 무릎·상체 들림(누운 기준 대비 fc_torso_elev)·머리 들림·두 무릎 간격·직전 바닥 다리각과
앱(또는 재생)의 판정(REP·REJ:사유·ABST = 측면 아님 유보·DISC = 소리 없이 버림)을 낸다. 띠를 바꾸기 전에 이 표를 본다. 누운 기준은 로그 reps.config.lying
(없으면 세트 처음 누운 영역 프레임의 신호 10 % 분위)이다.
"""
from __future__ import annotations

import json
import statistics as st
import subprocess
import sys
import tempfile
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

PROFILES = {
    "크런치": {"signal": "fc_trunk_lift", "enter": 3.0, "ear_enter": 6.0},
    "라잉 레그 레이즈": {"signal": "fc_thigh", "enter": 12.0, "ear_enter": None},
}


def med(xs):
    xs = [x for x in xs if x is not None]
    return st.median(xs) if xs else None


def q10(xs):
    xs = sorted(x for x in xs if x is not None)
    return xs[len(xs) // 10] if xs else None


def fmt(v, w=6, d=1):
    return f"{v:{w}.{d}f}" if isinstance(v, (int, float)) else f"{'-':>{w}}"


def excursions(F: list[tuple[int, dict]], key: str, base: float, enter: float) -> list[tuple[int, int]]:
    """신호가 기준 + enter 를 넘은 구간 — 안에서 진폭의 절반 넘게 되돌아왔다가 다시 enter 만큼 오르면 거기서 나눈다(긴장 유지로 기준까지 안 내려오는 연속 회)."""
    out, cur, peak, low, low_i = [], None, None, None, None
    for i, (_, f) in enumerate(F):
        v = f.get(key)
        if v is None:
            continue
        if cur is None:
            if v > base + enter:
                cur = [i, i]; peak = v; low = None
            continue
        if v <= base + enter:
            out.append(tuple(cur)); cur = None
            continue
        if low is not None and v >= low + enter:          # 되돌아왔다가 다시 오름 — 바닥점에서 나눈다
            out.append((cur[0], low_i)); cur = [low_i, i]; peak = v; low = None
            continue
        cur[1] = i
        if v > peak and low is None:
            peak = v
        if v <= peak - 0.5 * (peak - base):
            if low is None or v < low:
                low, low_i = v, i
    if cur is not None:
        out.append(tuple(cur))
    return out


def table(s: dict) -> None:
    ex = s["exercise"]
    prof = PROFILES[ex]
    reps = s.get("reps") or {}
    cfg = reps.get("config") or {}
    F = [(int(f["t_ms"]), f.get("features") or {}) for f in s.get("frames", [])]
    sig = prof["signal"]
    lying = cfg.get("lying") or {}
    first = [f for t, f in F if f.get(sig) is not None][:8]
    base = lying.get(sig, q10([f.get(sig) for f in first]))
    ear_base = lying.get("fc_ear_lift", q10([f.get("fc_ear_lift") for f in first]))
    torso_base = lying.get("fc_torso_elev", q10([f.get("fc_torso_elev") for f in first]))
    print("=" * 120)
    print(f"{ex} | {s.get('set_id', s.get('source', ''))} | engine {reps.get('engine')} | count {reps.get('count')} | "
          f"base {fmt(base, 0)} ear {fmt(ear_base, 0)} torso {fmt(torso_base, 0)} | frames {len(F)} | "
          f"up_ok {sum(1 for _, f in F if f.get('fc_up_ok') == 1)}/{len(F)} chain {sum(1 for _, f in F if f.get('fc_chain') == 1)}/{len(F)}")
    if base is None:
        print("  (사이클 신호가 없다)")
        return
    segs = [(a, b, "main") for a, b in excursions(F, sig, base, prof["enter"])]
    if prof["ear_enter"] is not None and ear_base is not None:
        for a, b in excursions(F, "fc_ear_lift", ear_base, prof["ear_enter"]):
            if not any(a <= y and b >= x for x, y, _ in segs):
                segs.append((a, b, "ear"))
    segs.sort()
    times = reps.get("t_ms") or []
    rej = reps.get("rejected") or []
    abst = set(reps.get("identity_abstain") or [])
    disc = reps.get("discarded") or []
    print(f"{'t0':>6}-{'t1':>6} {'k':4}| {'peak':>6} {'amp':>5} | {'earPk':>6} {'earA':>5} | {'axis':>5} {'yaw':>5} | "
          f"{'kneeT':>5} {'tilt':>5} {'head':>5} {'gap':>5} {'legLo':>5} | app")
    # 판정 시각(복귀 확정)은 그 회의 정점 뒤·다음 회의 정점 전에 온다 — 시각보다 앞선 마지막 정점의 구간에 붙인다
    sk = {"main": sig, "ear": "fc_ear_lift"}
    peaks_t = [max(F[a:b + 1], key=lambda tf: tf[1].get(sk[k], -999))[0] for a, b, k in segs]
    def owner(t):
        return max((j for j, tp in enumerate(peaks_t) if tp < t), default=None)
    verdicts = {j: [] for j in range(len(segs))}
    orphan = []
    for t in times:
        (verdicts[owner(t)] if owner(t) is not None else orphan).append(f"REP@{t}{'(ABST)' if t in abst else ''}")
    for r in rej:
        (verdicts[owner(r['t_ms'])] if owner(r['t_ms']) is not None else orphan).append(f"REJ:{r.get('feature') or r.get('reason')}@{r['t_ms']}")
    for d in disc:
        (verdicts[owner(d[0])] if owner(d[0]) is not None else orphan).append(f"DISC:{d[1]}@{d[0]}")
    for j, (a, b, kind) in enumerate(segs):
        lo = max(0, a - 1); hi = min(len(F) - 1, b + 1)
        win = [f for _, f in F[lo:hi + 1]]
        t0, t1 = F[a][0], F[b][0]
        vals = [f.get(sig) for f in win if f.get(sig) is not None]
        peak = max(vals) if vals else None
        fpk = max(win, key=lambda f: f.get(sig, -999))
        ears = [f.get("fc_ear_lift") for f in win if f.get("fc_ear_lift") is not None]
        ear_pk = max(ears) if ears else None
        tops = [f.get("fc_knee") for f in win if peak is not None and f.get(sig) is not None and f.get(sig) >= peak - 15 and f.get("fc_knee") is not None]
        tilt = max((f["fc_torso_elev"] - torso_base for f in win if f.get("fc_torso_elev") is not None and torso_base is not None), default=None)
        legs_before = [f.get("fc_leg") for _, f in F[max(0, a - 5):a + 1] if f.get("fc_leg") is not None]
        app = " ".join(verdicts[j])
        print(f"{t0:6d}-{t1:6d} {kind:4}| {fmt(peak)} {fmt(peak - base if peak is not None else None, 5)} | {fmt(ear_pk)} "
              f"{fmt(ear_pk - ear_base if ear_pk is not None and ear_base is not None else None, 5)} | {fmt(F[a][1].get('fc_axis_h'), 5)} "
              f"{fmt(fpk.get('fc_yaw'), 5, 2)} | {fmt(med(tops), 5)} {fmt(tilt, 5)} {fmt(med([f.get('fc_head_lift') for f in win]), 5)} "
              f"{fmt(fpk.get('fc_knee_gap'), 5, 2)} {fmt(min(legs_before) if legs_before else None, 5)} | {app}")
    if orphan:
        print("  첫 정점 앞 판정:", " ".join(orphan))


def from_capture(cap: Path, reasons: str) -> dict:
    """재생기 바닥 경로로 캡처 하나를 세트 로그 모양으로 — frames = --dump-features, reps = 한 줄 매니페스트 재생 결과."""
    import capture_format
    import run_replay
    binary = run_replay.require_fresh_replay()
    meta, _ = capture_format.read_capture(cap)
    if meta.get("floor") != "1":
        raise SystemExit("캡처 메타에 floor=1 이 없다 — 바닥 경로 캡처가 아니다(aihub_floor_captures.py)")
    with tempfile.TemporaryDirectory() as d:
        dump = Path(d) / "dump.jsonl"; man = Path(d) / "m.tsv"; res = Path(d) / "r.jsonl"
        subprocess.run([str(binary), "--dump-features", str(cap), str(dump)], check=True, capture_output=True)
        man.write_text("\t".join(["diag", str(cap), meta["exercise"], "live", "", "", "1", "", "", "", reasons]) + "\n", encoding="utf-8")
        subprocess.run([str(binary), str(man), str(res)], check=True, capture_output=True)
        frames = [json.loads(l) for l in dump.read_text(encoding="utf-8").splitlines() if l.strip()]
        r = json.loads(res.read_text(encoding="utf-8").splitlines()[0])
    return {"exercise": meta["exercise"], "source": f"{cap.name} (재생, 정답 {meta.get('truthReps', '?')})",
            "frames": [{"t_ms": f["t"], "features": {k: v for k, v in f["features"].items() if v is not None}} for f in frames if f["detected"]],
            "reps": {"engine": r.get("engine"), "count": r.get("reps"), "t_ms": r.get("publishedMs", []),
                     "rejected": [{"t_ms": x[0], "feature": x[1]} for x in r.get("floorRejected", [])],
                     "identity_abstain": r.get("identityAbstainMs", []), "discarded": r.get("floorDiscarded", []),
                     "config": {"lying": r.get("floorLying") or {}}}}


def main() -> int:
    argv = sys.argv[1:]
    if not argv:
        print(__doc__); return 2
    if argv[0] == "--cap":
        reasons = argv[argv.index("--reasons") + 1] if "--reasons" in argv else "default"
        table(from_capture(Path(argv[1]), reasons))
        return 0
    only = argv[1] if len(argv) > 1 else None
    for line in Path(argv[0]).read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        s = json.loads(line)
        if s.get("exercise") not in PROFILES or (only and only not in s["exercise"]):
            continue
        table(s)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
