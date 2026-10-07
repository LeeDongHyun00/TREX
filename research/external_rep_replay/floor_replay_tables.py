# -*- coding: utf-8 -*-
"""바닥 계열 엔진 재생 표(spec §99, docs/FLOOR_FAMILY_DESIGN.md §7.1 b·c·d) — 실제 엔진(replay-jvm 바닥 경로)이 나눈 회·시간으로 잰다.

  (c) MM-Fit 윗몸일으키기 15세트: 세트별 엔진 횟수 vs 정답, 정확·±1, w06·w14 가 0회가 아닌가, 세트 밖 헛사이클, w19 완전 윗몸의 sit_up 기각률.
  (b) AIHub 크런치·라잉 레그 레이즈 C(와 E): 판별 사유별 **정상 회 기각률(엔진이 나눈 회 단위)** — 사유를 하나씩 켠 진단 구성(매니페스트 11열과 같은
      `--floor-clips … <사유>`)으로 돌려 사유끼리 가리지 않게 잰다(판별을 켜고 꺼도 사이클 분할은 같다). 위반 클립 검출률, 수행자별 최댓값, 95 % 신뢰구간(Clopper-Pearson).
  (d) AIHub 플랭크 C(와 E): 시간 게이트 통과율(프레임), 멈춤 사유 분포, 정상 클립의 거짓 멈춤(멈춘 뒤 같은 클립에서 다시 버틴 멈춤).

'정상' 의 정의(판별마다 — AIHub 조건 중 그 판별과 관계있는 것만 본다):
  크런치  neck_only·shallow = '견갑골이 지면으로부터 충분히 올라옴' 충족 클립(위반 = 고개만 → 검출), sit_up = 전 클립(AIHub 에 끝까지 일어남 위반이 없다).
  레그    knee_bent = '허벅지와 종아리 각도 고정' 충족(위반 = 검출), feet_touch = '이완 시 다리 긴장유지' 충족(위반 = 발을 바닥에 내림 → 검출),
          trunk_up·shallow·one_leg = 전 클립(대응하는 AIHub 위반이 없다). 전조건정상(크런치 489·레그 473)은 따로 적는다.
기각률의 분모 = 엔진 회(센 회 + 거둔 첫 회 + 기각한 회). 측면 판정 회 = 엔진 회 − 판별 유보 회(정점 fc_yaw > 0.15 또는 없음). **입장선은 측면 판정 회 기준
≤ 2 %**(유보는 통과라 전체 분모로 나누면 측면이 아닌 촬영이 기각률을 낮춰 보이게 한다 — 폰은 측면에서 찍으므로 보수적인 쪽을 쓴다).

AIHub 은 16 키프레임·시각 없음(600 ms 가정)·서 있는 높이 카메라·연기된 위반이다(설계 §3.6). 변형: aihub_up = 화면 위를 중력으로(앱 경로, 1차),
aihub = 중력 모름(게이트 없는 경로), aihub_up_trim = 앞 2·뒤 4 키프레임 제외(진입·이탈 제외).

사용: python floor_replay_tables.py [--root data/floor_family/replay] [--skip-run]
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from collections import Counter, defaultdict
from pathlib import Path

from scipy.stats import beta

import aihub_floor_captures as caps
import run_replay

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

CRUNCH, LEG, PLANK = "크런치", "라잉 레그 레이즈", "플랭크"
REASONS = {CRUNCH: ["sit_up", "neck_only", "shallow"], LEG: ["trunk_up", "knee_bent", "shallow", "one_leg", "feet_touch"]}
DEFAULT_ON: dict[str, set[str]] = {}   # FloorProfile.defaultEnabled — 기본 구성 재생 결과의 floorEnabled 에서 읽는다(표의 켬/끔 표시)
SCAP = "견갑골이 지면으로부터 충분히 올라옴"
KNEE = "허벅지와 종아리 각도 고정"
TENSION = "이완 시 다리 긴장유지"
ALIGN = "몸통과 엉덩이의 정렬 유지"
GATE = 0.02
SET_MARGIN_MS = 1_500        # MM-Fit 세트 구간 ± 1.5 s(설계 원형 m2_count*.py 와 같다)
VARIANTS = ["aihub_up", "aihub", "aihub_up_trim"]


def cp(k: int, n: int) -> tuple[float, float]:
    if n == 0:
        return (float("nan"), float("nan"))
    lo = 0.0 if k == 0 else beta.ppf(0.025, k, n - k + 1)
    hi = 1.0 if k == n else beta.ppf(0.975, k + 1, n - k)
    return (lo, hi)


def pct(k: int, n: int) -> str:
    return f"{k}/{n} = {100 * k / n:.1f} %" if n else f"{k}/0"


def ci_text(k: int, n: int) -> str:
    lo, hi = cp(k, n)
    return f"[{100 * lo:.1f}, {100 * hi:.1f}]" if n else "—"


def load_jsonl(path: Path) -> list[dict]:
    return [json.loads(l) for l in path.read_text(encoding="utf-8").splitlines() if l.strip()]


# ---------------------------------------------------------------- 재생

def run_floor_clips(root: Path, out_dir: Path, skip: bool) -> dict:
    """{(변형, 종목, 뷰, 구성): [클립 결과]}."""
    binary = run_replay.require_fresh_replay() if not skip else run_replay.REPLAY_BIN
    res = {}
    for variant in VARIANTS:
        for ex in (PLANK, CRUNCH, LEG):
            for view in caps.VIEWS:
                cap = root / variant / ex / f"{view}.cap"
                if not cap.is_file():
                    continue
                configs = ["default"] if ex == PLANK else ["default"] + REASONS[ex]
                if variant == "aihub_up_trim" and ex != PLANK:
                    configs = ["default"] + (["knee_bent"] if ex == LEG else [])
                for cfg in configs:
                    out = out_dir / f"{variant}__{ex}__{view}__{cfg}.jsonl"
                    if not skip or not out.is_file():
                        subprocess.run([str(binary), "--floor-clips", str(cap), str(out), cfg], check=True, capture_output=True)
                    res[(variant, ex, view, cfg)] = load_jsonl(out)
    return res


def run_mmfit(root: Path, out_dir: Path, skip: bool) -> dict:
    idx = json.loads((root / "index.json").read_text(encoding="utf-8"))
    rows = []
    for c in idx["captures"]:
        if c["set"] != "mmfit":
            continue
        for cfg in ("default", "none"):
            rid = f"{c['variant']}|{c['subject']}|{c['setIndex']}|{cfg}"
            rows.append((rid, str(root / c["capture"]), CRUNCH, "live", "", "", "1", "", "", "", cfg))
    manifest = out_dir / "mmfit_manifest.tsv"
    results = out_dir / "mmfit_results.jsonl"
    manifest.write_text("".join("\t".join(r) + "\n" for r in rows), encoding="utf-8")
    if not skip or not results.is_file():
        binary = run_replay.require_fresh_replay() if not skip else run_replay.REPLAY_BIN
        subprocess.run([str(binary), str(manifest), str(results)], check=True, capture_output=True)
    by_id = {r["id"]: r for r in load_jsonl(results)}
    return {"index": [c for c in idx["captures"] if c["set"] == "mmfit"], "results": by_id}


# ---------------------------------------------------------------- (c) MM-Fit

def table_mmfit(mm: dict) -> tuple[str, dict]:
    lines = ["### (c) MM-Fit 윗몸일으키기 15세트 — 세트별 엔진 횟수(세트 구간 ±1.5 s 안) − 정답 10", "",
             "| 구성 | 정확 | ±1 | 세트 밖 헛사이클 | w06·w14 0회 세트 | 세트별 오차(w06 · w14 · w18 · w19 · w20) |", "|---|---|---|---|---|---|"]
    summary = {}
    caps_by = defaultdict(dict)
    for c in mm["index"]:
        caps_by[c["variant"]][(c["subject"], c["setIndex"])] = c
    for variant, label in (("mmfit_up", "중력 = 화면 위(축 게이트)"), ("mmfit", "중력 모름(게이트 없음)")):
        for cfg in ("none", "default"):
            first = mm["results"][f"{variant}|w06|0|{cfg}"]
            cl = "판별 끔" if cfg == "none" else f"기본 판별({'·'.join(first.get('floorEnabled', [])) or '없음'})"
            errs, outs, zero, per = [], 0, 0, {}
            situp = [0, 0]
            for (w, k), c in sorted(caps_by[variant].items()):
                r = mm["results"][f"{variant}|{w}|{k}|{cfg}"]
                lo, hi = c["setStartMs"] - SET_MARGIN_MS, c["setEndMs"] + SET_MARGIN_MS
                pub = r["publishedMs"]
                ins = [t for t in pub if lo <= t <= hi]
                rej = [x for x in r.get("floorRejected", []) if lo <= x[0] <= hi]
                ret = [t for t in r.get("floorRetractedMs", []) if lo <= t <= hi]
                outs += len(pub) - len(ins)
                n = len(ins) if cfg == "none" else len(ins)
                err = n - c["truthReps"]
                errs.append(err)
                if w in ("w06", "w14") and n == 0:
                    zero += 1
                per[f"{w}_s{k}"] = {"counted": len(ins), "rejected": Counter(x[1] for x in rej), "retracted": len(ret), "out": len(pub) - len(ins),
                                    "discarded": Counter(d[1] for d in r.get("floorDiscarded", []))}
                if w == "w19" and cfg == "default":
                    situp[0] += sum(1 for x in rej if x[1] == "sit_up"); situp[1] += len(ins) + len(rej) + len(ret)
            exact = sum(e == 0 for e in errs); pm1 = sum(abs(e) <= 1 for e in errs)
            groups = " · ".join(",".join(f"{e:+d}" for e in errs[i:i + 3]) for i in range(0, 15, 3))
            lines.append(f"| {label} + {cl} | {exact}/15 | {pm1}/15 | {outs} | {zero}/6 | {groups} |")
            summary[f"{variant}|{cfg}"] = {"exact": exact, "pm1": pm1, "out": outs, "zeroW06W14": zero, "errors": errs, "perSet": {
                k: {**v, "rejected": dict(v["rejected"]), "discarded": dict(v["discarded"])} for k, v in per.items()}}
            if cfg == "default":
                summary[f"{variant}|w19_sit_up"] = situp
    s = summary["mmfit_up|w19_sit_up"]
    lines += ["", f"w19(완전 윗몸일으키기) sit_up 기각: 중력 = 화면 위 + 기본 판별에서 엔진 회(센·거둔·기각) 중 {pct(s[0], s[1])}."]
    # 기본 판별 구성의 세트별 기각 사유
    det = summary["mmfit_up|default"]["perSet"]
    lines.append("기본 판별 세트별(센/기각 사유): " + " · ".join(
        f"{k} {v['counted']}/{','.join(f'{r}{n}' for r, n in v['rejected'].items()) or '-'}" for k, v in det.items()))
    return "\n".join(lines), summary


# ---------------------------------------------------------------- (b) AIHub 판별

def clip_meta(root: Path, variant: str, ex: str, view: str) -> list[dict]:
    return json.loads((root / variant / ex / f"{view}.clips.json").read_text(encoding="utf-8"))["clips"]


def engine_reps(r: dict) -> tuple[int, int]:
    """(엔진 회, 측면 판정 회). 엔진 회 = 센 회 + 거둔 첫 회 + 기각한 회."""
    if "error" in r:
        return 0, 0
    n = len(r["publishedMs"]) + len(r.get("floorRetractedMs", [])) + len(r.get("floorRejected", []))
    return n, n - len(r.get("identityAbstainMs", []))


def normal_sets(ex: str, reason: str, meta: dict) -> tuple[bool, bool | None]:
    """(정상 회로 셀 클립인가, 위반(검출 대상) 클립인가 — 대응 위반이 없으면 None)."""
    cond = meta["conditions"]
    if ex == CRUNCH:
        if reason in ("neck_only", "shallow"):
            ok = cond.get(SCAP)
            return bool(ok), (ok is False)
        return True, None
    if reason == "knee_bent":
        ok = cond.get(KNEE); return bool(ok), (ok is False)
    if reason == "feet_touch":
        ok = cond.get(TENSION); return bool(ok), (ok is False)
    return True, None


def table_discrimination(res: dict, root: Path) -> tuple[str, dict]:
    for (variant, ex, view, cfg), rs in res.items():
        if cfg == "default" and ex in REASONS and rs and "floorEnabled" in rs[0]:
            DEFAULT_ON[ex] = set(rs[0]["floorEnabled"])
    lines = ["### (b) AIHub 엔진 재생 — 판별 사유별 정상 회 기각률(엔진이 나눈 회, 사유를 하나씩 켠 구성)", "",
             "| 종목·사유(기본) | 변형·뷰 | 정상 회 기각(측면 판정 회) | 95 % CI | 전체 분모 | 전조건정상 | 수행자 최댓값 | 위반 클립 검출 |",
             "|---|---|---|---|---|---|---|---|"]
    summary = {}
    for ex in (CRUNCH, LEG):
        allok = "489" if ex == CRUNCH else "473"
        for reason in REASONS[ex]:
            for variant in VARIANTS:
                for view in caps.VIEWS:
                    key = (variant, ex, view, reason)
                    if key not in res:
                        continue
                    meta = clip_meta(root, variant, ex, view)
                    k_side = n_side = n_all = k_all473 = n_all473 = 0
                    perf = defaultdict(lambda: [0, 0])
                    det = [0, 0]
                    for r in res[key]:
                        m = meta[r["i"]]
                        normal, viol = normal_sets(ex, reason, m)
                        rej = sum(1 for x in r.get("floorRejected", []) if x[1] == reason)
                        n, side = engine_reps(r)
                        if normal:
                            k_side += rej; n_side += side; n_all += n
                            perf[m["performer"]][0] += rej; perf[m["performer"]][1] += side
                            if m["type_key"].startswith(allok):
                                k_all473 += rej; n_all473 += side
                        if viol:
                            det[1] += 1; det[0] += rej > 0
                    pmax = max(((k / n, p, k, n) for p, (k, n) in perf.items() if n >= 3), default=(0.0, "-", 0, 0))
                    rate = k_side / n_side if n_side else float("nan")
                    on = "켬" if reason in DEFAULT_ON[ex] else "끔"
                    lines.append(f"| {ex} {reason}({on}) | {variant} {view} | {pct(k_side, n_side)} | {ci_text(k_side, n_side)} | {n_all} | "
                                 f"{pct(k_all473, n_all473)} | {pmax[1]} {100 * pmax[0]:.0f} % ({pmax[2]}/{pmax[3]}) | "
                                 f"{pct(det[0], det[1]) if det[1] else '—'} |")
                    summary["|".join(key)] = {"k": k_side, "n": n_side, "nAll": n_all, "rate": rate, "ci": cp(k_side, n_side),
                                              "allNormal": [k_all473, n_all473], "perfMax": [pmax[1], pmax[2], pmax[3]], "detect": det}
    # 엔진 분할 점검 — 클립당 엔진 회·센 회·기각·판별 유보(기본 구성)
    lines += ["", "엔진 분할(기본 구성, 클립당 평균): " + " · ".join(
        f"{variant} {ex} {view} 회 {sum(engine_reps(r)[0] for r in res[(variant, ex, view, 'default')]) / max(1, len(res[(variant, ex, view, 'default')])):.2f}"
        f" 유보 {sum(len(r.get('identityAbstainMs', [])) for r in res[(variant, ex, view, 'default')])}"
        f" 0회클립 {sum(1 for r in res[(variant, ex, view, 'default')] if engine_reps(r)[0] == 0)}/{len(res[(variant, ex, view, 'default')])}"
        for variant in ("aihub_up", "aihub") for ex in (CRUNCH, LEG) for view in caps.VIEWS if (variant, ex, view, "default") in res)]
    return "\n".join(lines), summary


# ---------------------------------------------------------------- (d) 플랭크

def table_plank(res: dict, root: Path) -> tuple[str, dict]:
    lines = ["### (d) AIHub 플랭크 — 시간 게이트(PlankHoldClock.stopReason)와 멈춤", "",
             "게이트 통과(pike 제외) = 설계 §3.4 의 시간 게이트(사슬·수평·무릎·지지·골반 뜸, 0.743)와 같은 조건 — 솟음(pike)은 마지막 사유라 그 프레임이 pike 를 빼면 통과다.",
             "pike 프레임 = 첫 사유가 솟음인 프레임 비율(정렬 충족 / 정렬 위반 클립).", "",
             "| 변형·뷰 | 게이트 통과(전 프레임) | 게이트 통과(사람 있는 프레임) | 게이트 통과(pike 제외, 전 프레임) | 프레임 멈춤 사유 분포 | 확정 멈춤(STOP) 사유 | pike 프레임 충족/위반 | 정상 클립 거짓 멈춤 | 정상 클립 인정 시간 / 벽시계 |",
             "|---|---|---|---|---|---|---|---|---|"]
    summary = {}
    for variant in VARIANTS:
        for view in caps.VIEWS:
            key = (variant, PLANK, view, "default")
            if key not in res:
                continue
            meta = clip_meta(root, variant, PLANK, view)
            frames = gate = det = 0
            reasons = Counter(); stops = Counter()
            nclips = false_clips = false_stops = 0
            held = wall = 0
            pike_viol = [0, 0]; pike_norm = [0, 0]
            pf_norm = [0, 0]; pf_viol = [0, 0]   # (pike 프레임, 프레임)
            for r in res[key]:
                m = meta[r["i"]]
                frames += r["frames"]; gate += r["gateFrames"]; det += r["detectedFrames"]
                reasons.update(r["frameReasons"])
                ev = r["events"]
                stops.update(e[2] for e in ev if e[0] == "STOP")
                normal = m["conditions"].get(ALIGN) is True
                # 거짓 멈춤 = 멈춘 뒤 같은 클립에서 다시 버틴(RESUME) 멈춤 — 끝의 무릎 대기·이탈 멈춤은 세지 않는다
                mids = [e for i, e in enumerate(ev) if e[0] == "STOP" and any(x[0] == "RESUME" for x in ev[i + 1:])]
                has_pike = any(e[0] == "STOP" and e[2] == "pike" for e in ev)
                pk = r["frameReasons"].get("pike", 0)
                if normal:
                    pf_norm[0] += pk; pf_norm[1] += r["frames"]
                elif m["conditions"].get(ALIGN) is False:
                    pf_viol[0] += pk; pf_viol[1] += r["frames"]
                if normal:
                    nclips += 1; false_clips += bool(mids); false_stops += len(mids)
                    held += r["heldMs"]; wall += r["wallMs"]
                    pike_norm[0] += has_pike; pike_norm[1] += 1
                elif m["conditions"].get(ALIGN) is False:
                    pike_viol[0] += has_pike; pike_viol[1] += 1
            dist = ", ".join(f"{k} {100 * v / frames:.1f} %" for k, v in reasons.most_common())
            sdist = ", ".join(f"{k} {v}" for k, v in stops.most_common()) or "-"
            nopike = gate + reasons.get("pike", 0)
            lines.append(f"| {variant} {view} | {pct(gate, frames)} | {pct(gate, det)} | {pct(nopike, frames)} | {dist} | {sdist} | "
                         f"{100 * pf_norm[0] / max(1, pf_norm[1]):.1f} % / {100 * pf_viol[0] / max(1, pf_viol[1]):.1f} % | "
                         f"{false_stops} 회 · 클립 {pct(false_clips, nclips)} | {held / 1000:.0f} s / {wall / 1000:.0f} s = {100 * held / max(1, wall):.0f} % |")
            summary[f"{variant}|{view}"] = {"gate": [gate, frames], "gateDetected": [gate, det], "frameReasons": dict(reasons), "stops": dict(stops),
                                            "falseStops": false_stops, "falseClips": [false_clips, nclips], "held": held, "wall": wall,
                                            "pikeStopViolation": pike_viol, "pikeStopNormal": pike_norm, "gateNoPike": [nopike, frames],
                                            "pikeFramesNormal": pf_norm, "pikeFramesViolation": pf_viol}
    s = summary.get("aihub_up|C")
    if s:
        lines += ["", f"솟음(pike) 멈춤이 난 클립: 정렬 위반 {pct(*s['pikeStopViolation'])} vs 정렬 충족 {pct(*s['pikeStopNormal'])} (aihub_up C)."]
    return "\n".join(lines), summary


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--root", type=Path, default=caps.DEFAULT_OUT)
    ap.add_argument("--skip-run", action="store_true", help="이미 있는 재생 결과를 다시 쓴다(재생기를 돌리지 않음)")
    a = ap.parse_args()
    out_dir = a.root / "results"
    out_dir.mkdir(parents=True, exist_ok=True)
    mm = run_mmfit(a.root, out_dir, a.skip_run)
    res = run_floor_clips(a.root, out_dir, a.skip_run)
    t_c, s_c = table_mmfit(mm)
    t_b, s_b = table_discrimination(res, a.root)
    t_d, s_d = table_plank(res, a.root)
    text = "\n\n".join([t_c, t_b, t_d]) + "\n"
    (out_dir / "floor_replay_tables.md").write_text(text, encoding="utf-8")
    (out_dir / "floor_replay_tables.json").write_text(json.dumps({"mmfit": s_c, "discrimination": s_b, "plank": s_d}, ensure_ascii=False, indent=1,
                                                                 default=str), encoding="utf-8")
    print(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
