# -*- coding: utf-8 -*-
"""캡처 → 앱 RepCounter 재생(JVM) → 세트별 반복 수 채점.

index.json(추출기·변환기가 쓴 세트 목록과 정답)을 읽어 매니페스트를 만들고, replay-jvm 의 재생기를 돌린 뒤
세트 단위 오차를 집계한다. 채점 정의는 다른 계열 측정(docs/mmfit-rep-counting.v1.md)과 같게 맞춰 숫자를 나란히 놓는다:
    MAE  = 세트별 |검출 − 정답| 평균,  OBO = |오차| ≤ 1 인 세트 비율
    count P/R/F1 = Σmin(검출,정답)/Σ검출, Σmin/Σ정답, 조화평균 — 반복 경계 F1 이 아니라 그 상한이다

세트 경계(설계 §14) — index 의 세트에 captureStartMs/captureEndMs 가 있으면(extract_mediapipe.py --pre-s/--post-s,
mmfit_pair_compare.py synth) 캡처가 세트 앞뒤 음성 구간까지 담고 있다. 그때는 카운터를 캡처 전체에 이어서 돌리고
    세트 카운트   = 발화 시각이 [startMs − 0.5 s, endMs + 0.5 s] 안인 발표 반복 (stress_battery.py 와 같은 창)
    앞 헛카운트   = 발화 시각이 그 창 앞인 발표 반복,  뒤 헛카운트 = 창 뒤인 것
으로 나눈다. 새 코어는 첫 두 사이클을 함께 발표하므로 '발화 시각'(publishedMs)으로 가른다 — 세트 앞에서 발화해 세트 첫 반복과
짝지어 발표된 사이클은 헛카운트다. 두 필드가 없는 캡처(0.5 s 여유)는 캡처 전체의 카운트를 세트 카운트로 본다(예전과 같다).

카운터 구성 (Replay.kt 참고)
    live         앱 세션이 실제로 쓰는 것 — RepCounter.forSession(...) (지금은 레거시 복귀형 ReturnRepTracker)
    hysteresis   새 코어(복귀 히스테리시스 + 시작 확정, 설계 §4.2·§4.3). **앱에서는 아직 꺼져 있다**(RepSignals polarity = null)
    reversal     RepCounter 기본 구성(반전 확정). 앱 세션은 쓰지 않는다 — 진단용
    live+<피처> / hysteresis+<피처>   신호만 바꾼 진단. 앱 동작이 아니다
    live@log · hysteresis@log   세트 로그 재생에서 로그의 신호가 지금 등록부 신호와 다를 때만 — 로그를 쓴 빌드의 신호·게이트로
                 파리티를 본다. 파리티는 로그의 reps.engine 이 가리키는 구성(return_v1 → live, hysteresis_v1 → hysteresis)에서만 본다

함께 싣는 것
    항상-N 기준선   모든 세트를 N 회(기본 10, MM-Fit 세트의 94%)로 부르는 예측 — 설계 §1 "같은 표에 병기"
    사람별 행       MM-Fit 은 워크아웃 → 사람 표(아래 MMFIT_SUBJECTS), 휴대폰 로그는 index 의 subject
    군집 부트스트랩 사람이 5명 이상이면 사람 단위 재표집 95% 구간(세트는 사람 안에서 독립이 아니다)

사용법
    python run_replay.py <캡처 폴더(index.json 포함)> --out <결과 폴더> [--series] [--configs live,hysteresis]
                         [--always 10] [--subject-map map.json] [--boot 2000] [--skip-replay]
"""
from __future__ import annotations

import argparse
import json
import math
import random
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
# installDist 는 두 실행 스크립트를 만든다 — 윈도우(사용자 PC)에서는 .bat 이어야 subprocess 가 실행한다
REPLAY_BIN = HERE / "replay-jvm" / "build" / "install" / "trex-rep-replay" / "bin" / (
    "trex-rep-replay.bat" if sys.platform == "win32" else "trex-rep-replay")

REPLAY_SOURCES = (HERE / "replay-jvm" / "src", HERE / "replay-jvm" / "build.gradle.kts",
                  HERE.parents[1] / "app" / "src" / "main" / "java" / "com" / "example" / "trex_kotlin" / "posture")


def require_fresh_replay() -> Path:
    """설치된 재생기가 지금 소스로 빌드됐는지 확인한다 — 브랜치를 바꾼 뒤 옛 바이너리가 새 옵션(--dump-features 등)을
    모른 채 인자를 매니페스트로 읽고 죽은 일이 있다(빌드 #3). 자동 빌드는 하지 않는다(윈도우 gradlew.bat·맥/리눅스 gradle 이 갈린다)."""
    stamp = REPLAY_BIN.parent.parent / "lib" / ".built"   # build.gradle.kts stampInstall — installDist 마다 새로 쓴다
    how = "(cd replay-jvm && gradle -q test installDist — 윈도우는 gradlew.bat -p research\\external_rep_replay\\replay-jvm test installDist)"
    if not REPLAY_BIN.is_file():
        raise SystemExit(f"재생기가 없다: {REPLAY_BIN}\n  {how}")
    newest = max((q.stat().st_mtime for src in REPLAY_SOURCES
                  for q in ([src] if src.is_file() else src.rglob("*.kt"))), default=0.0)
    if not stamp.is_file() or newest > stamp.stat().st_mtime:
        raise SystemExit(f"재생기가 소스보다 오래됐다(브랜치를 바꿨거나 소스를 고쳤다) — 다시 빌드한다:\n  {how}")
    return REPLAY_BIN


SET_MARGIN_MS = 500          # 세트 창 = 라벨 ± 0.5 s (stress_battery.py·README §4 와 같다)
MAX_GAP_MS = 1500            # RepCounter.forSession 의 끊김 초기화 기준

# 진단용 신호 교체 — 앱의 다른 종목이 이미 쓰는 신호, 또는 이 종목이 예전에 쓰던 신호만 고른다(새 신호를 만들어 맞추지 않는다).
DIAGNOSTIC_SIGNALS = {
    # 런지: 앱은 knee_mean 으로 바꿨다(설계 §4.1). 예전 앱 신호(knee_out_mean 0.10)와 '바벨 런지' 신호(knee_minside)를 나란히 둔다
    "스텝 포워드 다이나믹 런지": [("knee_out_mean", 0.10), ("knee_minside", 35.0)],
    # 교대 컬에서 한쪽 팔의 굽힘을 그대로 보는 신호(앱의 다른 종목도 minside 계열을 쓴다)
    "덤벨 컬": [("elbow_minside", 35.0)],
}
# 새 코어의 신호 후보(설계 §15 #9·#11 — 영상·Gate A 결과로 정한다). elbow_minside_both 는 재생기의 연구용 파생 신호:
# 두 팔이 다 보일 때만 min(L, R) — 앱 PostureCore 의 한쪽 폴백 없이(설계 §4.4 후보).
HYSTERESIS_DIAGNOSTICS = {
    "스텝 포워드 다이나믹 런지": [("knee_minside", 35.0)],
    "덤벨 컬": [("elbow_minside", 35.0), ("elbow_minside_both", 35.0)],
}
# 세트 로그 파리티(live@log)용 — 예전 빌드가 쓰던 신호의 게이트. 지금 등록부에 없는 신호만 적는다.
HISTORICAL_MIN_AMP = {"knee_out_mean": 0.10}
# 새 코어(hysteresis)를 재생할 수 있는 종목과 극성 — Replay.kt RESEARCH_POLARITY 와 같다(앱 등록부는 아직 어느 종목에도 극성을
# 켜지 않는다, 설계 §4.2). 여기 없는 종목은 hysteresis 행을 만들지 않는다: 반복 중 커지는 신호(힙쓰러스트·크런치·랫풀 다운 등)를
# DOWN 으로 돌리면 모든 세트가 마지막 반복을 잃어 구조적으로 틀린 숫자가 된다(설계 §13 부호 반전 조건).
# 로그가 극성을 적었으면(새 코어로 센 로그의 reps.config.polarity) 그것이 우선이다.
HYSTERESIS_POLARITY = {"바벨 스쿼트": "down", "스텝 포워드 다이나믹 런지": "down", "덤벨 컬": "down",
                       "푸시업": "down", "니푸쉬업": "down"}

# MM-Fit 워크아웃 → 사람. P0·P1 은 MM-Fit EDA 기준(각 6개 워크아웃). 나머지는 워크아웃 하나 = 한 사람으로 **가정**한다
# (참여자는 10명인데 워크아웃 21개 = 12 + 9 라 나머지 중 한 사람은 두 워크아웃을 했을 수 있다 — 누구인지 확인하지 못했다.
#  이전 스크래치 분석은 w00·w05 를 한 사람으로 묶었지만 출처가 없다). 그래서 'u:' 접두사로 '신원 미확인' 을 표시한다.
# 확인되면 --subject-map 으로 덮는다. 같은 사람을 둘로 세면 부트스트랩 구간이 약간 좁아진다(낙관 쪽 오차).
MMFIT_SUBJECTS = {**{w: "P0" for w in "w01 w03 w06 w08 w10 w14".split()},
                  **{w: "P1" for w in "w02 w04 w07 w09 w11 w15".split()}}


def subject_of(s: dict, overrides: dict[str, str] | None = None) -> str:
    """세트의 사람. index 의 subject(휴대폰 로그·계획표) → 덮어쓰기 표 → MM-Fit 표 → 'u:<워크아웃>' → 'unknown'."""
    if s.get("subject"):
        return str(s["subject"])
    w = s.get("workout")
    if w is None:
        return "unknown"
    if overrides and w in overrides:
        return overrides[w]
    return MMFIT_SUBJECTS.get(w, f"u:{w}")


def set_key(s: dict) -> str:
    return s["capture"].replace("/", "__").replace("\\", "__").removesuffix(".cap").removesuffix(".fcap")


def hysteresis_polarity(s: dict) -> str | None:
    """이 세트를 새 코어로 재생할 때의 극성(down/up). 로그의 reps.config.polarity → 연구 표. 모르면 None(재생하지 않는다)."""
    cfg = s.get("loggedConfig") or {}
    return cfg.get("polarity") or HYSTERESIS_POLARITY.get(s["exercise"])


# ---------------------------------------------------------------- 매니페스트 · 재생

def manifest_rows(index: dict, configs: set[str] | None = None) -> list[tuple]:
    """(id, capture, exercise, mode, feature, minAmp, floor, romDirection, romThreshold, polarity). configs=None 이면 전부.
    hysteresis 행은 극성을 아는 종목(hysteresis_polarity)만 만든다."""
    rows = []

    def want(name: str) -> bool:
        return configs is None or name in configs or name.split("+", 1)[0] + "+*" in configs

    for s in index["sets"]:
        key = set_key(s)
        floor = "1" if s.get("floor") else "0"
        rom_dir = s.get("romDirection") or ""
        rom_thr = "" if s.get("romThreshold") is None else str(s["romThreshold"])
        extra = (floor, rom_dir, rom_thr)
        pol = hysteresis_polarity(s)
        for mode in ("live", "hysteresis", "reversal"):
            if mode == "hysteresis" and pol is None:
                continue
            if want(mode):
                rows.append((f"{key}|{mode}", s["capture"], s["exercise"], mode, "", "", *extra,
                             pol if mode == "hysteresis" else ""))
        for feature, amp in DIAGNOSTIC_SIGNALS.get(s["exercise"], []):
            if want(f"live+{feature}"):
                rows.append((f"{key}|live+{feature}", s["capture"], s["exercise"], "live", feature, str(amp), *extra, ""))
        for feature, amp in HYSTERESIS_DIAGNOSTICS.get(s["exercise"], []):
            if pol is not None and want(f"hysteresis+{feature}"):
                rows.append((f"{key}|hysteresis+{feature}", s["capture"], s["exercise"], "hysteresis", feature, str(amp), *extra, pol))
    return rows


def write_manifest(rows: list[tuple], path: Path, base: Path) -> None:
    """캡처 경로는 절대경로로 적는다(매니페스트를 결과 폴더에 둘 수 있게)."""
    lines = []
    for r in rows:
        cap = Path(r[1])
        cap = cap if cap.is_absolute() else (base / cap).resolve()
        lines.append("\t".join([r[0], str(cap), *r[2:]]))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(line + "\n" for line in lines), encoding="utf-8")


def run_jvm(manifest: Path, results: Path, series: Path | None = None) -> dict[str, dict]:
    if not REPLAY_BIN.is_file():
        raise SystemExit(f"재생기가 없다: {REPLAY_BIN}\n  (cd replay-jvm && gradle test installDist)")
    cmd = [str(REPLAY_BIN), str(manifest), str(results)]
    if series is not None:
        cmd.append(str(series))
    subprocess.run(cmd, check=True)
    return load_results(results)


def load_results(path: Path) -> dict[str, dict]:
    out = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            r = json.loads(line)
            out[r["id"]] = r
    return out


def parity_mode(s: dict) -> str:
    """이 세트를 센 앱 카운터에 해당하는 재생 구성. spec §58 이전 로그(engine 없음)는 레거시 복귀형이다."""
    return "hysteresis" if s.get("loggedEngine") == "hysteresis_v1" else "live"


def parity_rows(index: dict, results: dict[str, dict]) -> list[tuple]:
    """로그 신호가 지금 등록부 신호와 다른 세트에 <구성>@log 행을 만든다(두 번째 재생). 게이트는 로그의 reps.config.min_amp,
    없으면(§58 이전) HISTORICAL_MIN_AMP. 게이트를 모르는 신호는 만들지 않는다 — 추측한 구성으로 파리티를 적지 않는다."""
    rows = []
    for s in index["sets"]:
        mode = parity_mode(s)
        r = results.get(f"{set_key(s)}|{mode}")
        if not r or r.get("signalMatchesLog") is not False:
            continue
        feature = r.get("loggedSignal")
        amp = s.get("loggedMinAmp") or HISTORICAL_MIN_AMP.get(feature)
        if feature is None or amp is None:
            continue
        floor = "1" if s.get("floor") else "0"
        pol = hysteresis_polarity(s) if mode == "hysteresis" else ""
        if pol is None:
            continue
        rows.append((f"{set_key(s)}|{mode}@log", s["capture"], s["exercise"], mode, feature, str(amp), floor, "", "", pol))
    return rows


# ---------------------------------------------------------------- 세트 단위 분해

def fire_times(r: dict) -> list[int]:
    """발표된 반복의 발화 시각(새 코어: 사이클이 복귀한 시각, 레거시: 완료 프레임). 옛 결과 파일은 repTimesMs 만 있다."""
    return list(r.get("publishedMs", r.get("repTimesMs", [])))


def _span_overlap_s(spans: list, lo: float, hi: float) -> float:
    """[a, b] 구간들이 [lo, hi] 와 겹치는 길이(초)."""
    return sum(max(0.0, min(b, hi) - max(a, lo)) for a, b in spans) / 1000.0


def split_set(s: dict, r: dict) -> dict:
    """한 세트의 재생 결과를 (세트 안 · 앞 · 뒤) 로 나눈다. 앞뒤 창이 없는 캡처는 lead/after = None.

    창 길이(leadS/afterS)는 캡처 시각으로 잰 창의 길이다 — 창이 있는가(분모 '창 수')만 가른다.
    관측 시간(leadObsS/afterObsS)은 그 창 안에서 카운터가 실제로 값을 본 시간이다(재생기의 valueSpans — 1.5 s 넘는 틈으로 끊긴
    값 프레임 구간). 사람이 안 잡힌 프레임은 헛카운트를 낼 수 없으므로 분당 비율의 분모는 관측 시간이어야 한다.
    옛 결과 파일(valueSpans 없음)은 창 길이로 대신한다."""
    times = fire_times(r)
    if s.get("captureStartMs") is None or s.get("startMs") is None or s.get("endMs") is None:
        return {"count": r["reps"], "lead": None, "after": None, "leadS": 0.0, "afterS": 0.0,
                "leadObsS": 0.0, "afterObsS": 0.0, "times": times}
    lo, hi = s["startMs"] - SET_MARGIN_MS, s["endMs"] + SET_MARGIN_MS
    lead = sum(t < lo for t in times)
    after = sum(t > hi for t in times)
    first = r.get("firstMs") if r.get("firstMs") is not None else s["captureStartMs"]
    last = r.get("lastMs") if r.get("lastMs") is not None else s["captureEndMs"]
    lead_s = max(0.0, (lo - first) / 1000.0)
    after_s = max(0.0, (last - hi) / 1000.0)
    spans = r.get("valueSpans")
    if spans is not None:
        lead_obs = _span_overlap_s(spans, float("-inf"), lo)
        after_obs = _span_overlap_s(spans, hi, float("inf"))
    else:
        lead_obs, after_obs = lead_s, after_s
    return {"count": len(times) - lead - after, "lead": lead if lead_s > 0 else None,
            "after": after if after_s > 0 else None, "leadS": lead_s, "afterS": after_s,
            "leadObsS": lead_obs, "afterObsS": after_obs, "times": times}


def set_rows(sets: list[dict], results: dict[str, dict], config: str, overrides: dict | None) -> list[dict]:
    rows = []
    for s in sets:
        r = results.get(f"{set_key(s)}|{config}")
        if r is None or "error" in r:
            continue
        sp = split_set(s, r)
        rows.append({"set": s["capture"], "exercise": s["exercise"], "subject": subject_of(s, overrides),
                     "condition": s.get("condition"), "truth": s.get("truthReps"), "detected": sp["count"],
                     "lead": sp["lead"], "after": sp["after"], "leadS": sp["leadS"], "afterS": sp["afterS"],
                     "leadObsS": sp["leadObsS"], "afterObsS": sp["afterObsS"],
                     "totalReps": r["reps"], "valid": r.get("valid", 0), "invalid": r.get("invalid", 0),
                     "romUnjudged": r.get("romUnjudged", 0), "pending": r.get("pendingReps", 0),
                     "inProgress": bool(r.get("inProgress")), "frames": r["frames"], "valueFrames": r["valueFrames"],
                     "feature": r["feature"], "minAmp": r["minAmp"], "hysteresis": r.get("hysteresis", False),
                     "parityCount": r.get("parityCount"), "parityTimes": r.get("parityTimes"),
                     "signalMatchesLog": r.get("signalMatchesLog"), "gaps": r.get("gapsOverMaxGap", 0),
                     "loggedReps": r.get("loggedReps"), "loggedEngine": s.get("loggedEngine"),
                     "resetsApplied": r.get("resetsApplied", 0), "parityDropped": r.get("parityDropped"),
                     "parityPending": r.get("parityPending")})
    return rows


# ---------------------------------------------------------------- 지표

def metrics(rows: list[dict]) -> dict:
    """정답이 있는 세트만 정확도에 쓴다. 정답 없는 세트는 unlabeled 로 센다(판정하지 않은 것을 맞았다고 세지 않는다)."""
    lab = [x for x in rows if x["truth"] is not None]
    out = {"sets": len(lab), "unlabeled": len(rows) - len(lab)}
    if lab:
        truth = [x["truth"] for x in lab]
        det = [x["detected"] for x in lab]
        err = [d - t for d, t in zip(det, truth)]
        hit = sum(min(d, t) for d, t in zip(det, truth))
        p = hit / sum(det) if sum(det) else float("nan")
        rc = hit / sum(truth) if sum(truth) else float("nan")
        f1 = 2 * p * rc / (p + rc) if p == p and rc == rc and p + rc > 0 else 0.0
        tot = [x["detected"] + (x["lead"] or 0) + (x["after"] or 0) for x in lab]
        if any(x["lead"] is not None or x["after"] is not None for x in lab):
            # 앞뒤 창까지 이어 센 전체(앱이 세트 끝에 보여 줄 수) — stress_battery 의 lead/after 조건 정확 일치와 같은 정의
            out["exactWithWindows"] = sum(t == u for t, u in zip(tot, truth)) / len(lab)
            out["overWithWindows"] = sum(t > u for t, u in zip(tot, truth))
        out.update({
            "truth": sum(truth), "detected": sum(det),
            "MAE": sum(abs(e) for e in err) / len(err), "OBO": sum(abs(e) <= 1 for e in err) / len(err),
            "exact": sum(e == 0 for e in err) / len(err), "under": sum(e < 0 for e in err), "over": sum(e > 0 for e in err),
            "zeroSets": sum(d == 0 for d in det), "precision": p, "recall": rc, "countF1": f1,
        })
    rom = lab or rows   # ROM 집계도 행의 세트(정답 있는 세트)와 같은 모집단으로 — 정답이 하나도 없을 때만 전부
    out.update({
        "valid": sum(x["valid"] for x in rom), "invalid": sum(x["invalid"] for x in rom),
        "romUnjudged": sum(x["romUnjudged"] for x in rom),
        "pendingAtEnd": sum(x["pending"] for x in rows), "inProgressAtEnd": sum(x["inProgress"] for x in rows),
        "valueCoverage": sum(x["valueFrames"] for x in rows) / max(1, sum(x["frames"] for x in rows)),
        "feature": rows[0]["feature"] if rows else None, "minAmp": rows[0]["minAmp"] if rows else None,
    })
    for side, sec, obs in (("lead", "leadS", "leadObsS"), ("after", "afterS", "afterObsS")):
        win = [x for x in rows if x[side] is not None]
        if not win:
            continue
        total = sum(x[side] for x in win)
        seconds = sum(x[sec] for x in win)
        observed = sum(x.get(obs, x[sec]) for x in win)
        # 분당 비율의 분모는 카운터가 값을 실제로 본 시간(관측) — 사람이 안 잡힌 프레임까지 세면 비율이 낮게 나온다
        out[side] = {"windows": len(win), "falseCounts": total, "perSet": total / len(win),
                     "setsWithFalse": sum(x[side] > 0 for x in win), "seconds": seconds, "observedSeconds": observed,
                     "perMinute": total / (observed / 60.0) if observed > 0 else float("nan")}
    return out


def always_metrics(rows: list[dict], n: int) -> dict:
    lab = [x for x in rows if x["truth"] is not None]
    if not lab:
        return {"sets": 0}
    truth = [x["truth"] for x in lab]
    err = [n - t for t in truth]
    return {"n": n, "sets": len(lab), "exact": sum(e == 0 for e in err) / len(err),
            "OBO": sum(abs(e) <= 1 for e in err) / len(err), "MAE": sum(abs(e) for e in err) / len(err),
            "over": sum(e > 0 for e in err), "recall": sum(min(n, t) for t in truth) / sum(truth)}


def _pooled(rows: list[dict], always_n: int) -> dict:
    lab = [x for x in rows if x["truth"] is not None]
    if not lab:
        return {}
    t = sum(x["truth"] for x in lab)
    out = {"exact": sum(x["detected"] == x["truth"] for x in lab) / len(lab),
           "recall": sum(min(x["detected"], x["truth"]) for x in lab) / t if t else float("nan"),
           "MAE": sum(abs(x["detected"] - x["truth"]) for x in lab) / len(lab),
           "exactMinusAlways": (sum(x["detected"] == x["truth"] for x in lab) - sum(x["truth"] == always_n for x in lab)) / len(lab)}
    win = [x for x in lab if x["lead"] is not None]
    if win:
        out["leadPerSet"] = sum(x["lead"] for x in win) / len(win)
    win = [x for x in lab if x["after"] is not None]
    if win:
        out["afterPerSet"] = sum(x["after"] for x in win) / len(win)
    return out


def cluster_bootstrap(rows: list[dict], always_n: int, n_boot: int, seed: int) -> dict | None:
    """사람 단위 재표집 백분위 95% 구간. 사람이 5명 미만이면 None(구간이 뜻을 갖지 못한다)."""
    by = defaultdict(list)
    for x in rows:
        if x["truth"] is not None:
            by[x["subject"]].append(x)
    subjects = sorted(by)
    if len(subjects) < 5:
        return None
    rng = random.Random(seed)
    point = _pooled([x for s in subjects for x in by[s]], always_n)
    draws = defaultdict(list)
    for _ in range(n_boot):
        pick = [rng.choice(subjects) for _ in subjects]
        m = _pooled([x for s in pick for x in by[s]], always_n)
        for k, v in m.items():
            if v == v:
                draws[k].append(v)
    out = {"subjects": len(subjects), "boot": n_boot, "seed": seed}
    for k, v in point.items():
        d = sorted(draws.get(k, []))
        if not d:
            continue
        lo = d[max(0, int(math.floor(0.025 * (len(d) - 1))))]
        hi = d[min(len(d) - 1, int(math.ceil(0.975 * (len(d) - 1))))]
        out[k] = {"point": v, "lo": lo, "hi": hi}
    return out


def per_subject(rows: list[dict]) -> dict:
    by = defaultdict(list)
    for x in rows:
        by[x["subject"]].append(x)
    out = {}
    for sub, xs in sorted(by.items()):
        m = metrics(xs)
        keep = {k: m[k] for k in ("sets", "unlabeled", "exact", "recall", "MAE", "over", "zeroSets") if k in m}
        if "lead" in m:
            keep["leadPerSet"] = m["lead"]["perSet"]
        if "after" in m:
            keep["afterPerSet"] = m["after"]["perSet"]
        out[sub] = keep
    return out


def parity_summary(rows: list[dict]) -> dict | None:
    with_log = [x for x in rows if x["loggedReps"] is not None]
    if not with_log:
        return None
    times = [x for x in with_log if x["parityTimes"] is not None]
    pend = [x for x in with_log if x["parityPending"] is not None]
    return {"sets": len(with_log), "countMatch": sum(bool(x["parityCount"]) for x in with_log),
            "timesCompared": len(times), "timesMatch": sum(bool(x["parityTimes"]) for x in times),
            "signalDiffers": sum(x["signalMatchesLog"] is False for x in with_log),
            "setsWithGap": sum(x["gaps"] > 0 for x in with_log), "setsWithReset": sum(x["resetsApplied"] > 0 for x in with_log),
            "pendingCompared": len(pend), "pendingMatch": sum(bool(x["parityPending"]) and bool(x["parityDropped"]) for x in pend),
            "mismatches": [{"set": x["set"], "logged": x["loggedReps"], "replayed": x["totalReps"], "gaps": x["gaps"],
                            "signalMatchesLog": x["signalMatchesLog"]} for x in with_log if not x["parityCount"]]}


# ---------------------------------------------------------------- 반복 경계 (REHAB24-6)

BOUNDARY_TOLERANCES_MS = (0, 500, 1000)


def boundary(sets: list[dict], results: dict[str, dict], config: str) -> dict:
    """반복 경계 정답(REHAB24-6)이 있을 때만. 카운터가 반복을 발표한 순간이 정답 반복 구간 안에 들어오면 일치.

    발표 순간 = onFrame 이 true 를 돌려준 프레임(앱이 숫자를 올리는 순간). 복귀형은 준비 위치로 돌아온 뒤
    두 샘플을 더 보고 확정하므로 정답 구간의 끝보다 조금 늦을 수 있다 — 그래서 허용 오차별로 따로 적는다.
    일치는 시간 순서대로 1:1 (발표 하나가 정답 둘을 먹지 않는다).
    """
    groups: dict[tuple, list] = defaultdict(list)
    for s in sets:
        if "reps" not in s:
            continue
        r = results.get(f"{set_key(s)}|{config}")
        if r is None or "error" in r:
            continue
        groups[(s["exercise"], s.get("camera", ""))].append((s, r))
    out = {}
    for (ex, cam), rows in groups.items():
        entry = {"recordings": len(rows)}
        for tol in BOUNDARY_TOLERANCES_MS:
            tp = fp = fn = 0
            for s, r in rows:
                truth = [(a - tol, b + tol) for a, b, _ in s["reps"]]
                used = [False] * len(truth)
                for t in sorted(r["repTimesMs"]):
                    match = next((i for i, (a, b) in enumerate(truth) if not used[i] and a <= t <= b), None)
                    if match is None:
                        fp += 1
                    else:
                        used[match] = True
                        tp += 1
                fn += used.count(False)
            p = tp / (tp + fp) if tp + fp else float("nan")
            rc = tp / (tp + fn) if tp + fn else float("nan")
            entry[f"tol{tol}"] = {"tp": tp, "fp": fp, "fn": fn, "precision": p, "recall": rc,
                                  "f1": 2 * p * rc / (p + rc) if p == p and rc == rc and p + rc > 0 else 0.0}
        out[f"{ex}|cam{cam}"] = entry
    return out


# ---------------------------------------------------------------- 요약

def summarize(index: dict, results: dict[str, dict], configs: list[str], always_n: int, overrides: dict | None,
              n_boot: int, seed: int) -> dict:
    summary = {"source": index.get("source"), "alwaysN": always_n, "setMarginMs": SET_MARGIN_MS,
               "subjectAssumption": "MM-Fit: P0·P1 = EDA 표, 나머지 워크아웃은 한 사람씩으로 가정(u:), --subject-map 으로 덮음",
               "configs": {}, "always": {}, "subjects": {}, "bootstrap": {}, "parity": {}, "perSet": {}}
    for c in configs:
        rows = set_rows(index["sets"], results, c, overrides)
        by_ex = defaultdict(list)
        for x in rows:
            by_ex[x["exercise"]].append(x)
        summary["configs"][c] = {ex: metrics(xs) for ex, xs in sorted(by_ex.items())}
        summary["subjects"][c] = {ex: per_subject(xs) for ex, xs in sorted(by_ex.items())}
        summary["bootstrap"][c] = {ex: b for ex, xs in sorted(by_ex.items())
                                   if (b := cluster_bootstrap(xs, always_n, n_boot, seed)) is not None}
        # 파리티는 그 세트를 실제로 센 구성에만 뜻이 있다 — 로그의 reps.engine 이 return_v1(또는 없음)이면 live,
        # hysteresis_v1 이면 hysteresis 행(신호가 다르면 각각의 @log 행). 진단 구성은 앱 로그와 비교하지 않는다.
        if c in ("live", "live@log", "hysteresis", "hysteresis@log"):
            base = c.split("@", 1)[0]
            summary["parity"][c] = {}
            for ex, xs in sorted(by_ex.items()):
                mine = [x for x in xs if ("hysteresis" if x["loggedEngine"] == "hysteresis_v1" else "live") == base]
                if (p := parity_summary(mine)) is not None:
                    summary["parity"][c][ex] = p
        summary["perSet"][c] = rows
        for ex, xs in by_ex.items():
            summary["always"].setdefault(ex, always_metrics(xs, always_n))
    return summary


def _f(v, fmt=".2f") -> str:
    if v is None or (isinstance(v, float) and v != v):
        return "—"
    return format(v, fmt)


def markdown(summary: dict) -> str:
    L = [f"source: {summary['source']}", "",
         "| 구성 | 종목 | 신호 | 세트 | 정답 | 검출 | ROM 유효/미달/미판정 (캡처 전체 발표) | MAE | OBO | 정확 | 과다 세트 | 0회 | P | R | count F1 | 앞 헛카운트/세트 (창·관측 분당) | 뒤 헛카운트/세트 (창) |",
         "|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---|---|"]
    for c, by_ex in summary["configs"].items():
        for ex, m in by_ex.items():
            if not m.get("sets"):
                continue
            lead = m.get("lead")
            after = m.get("after")
            lead_s = f"{lead['perSet']:.2f} ({lead['windows']}창 · {_f(lead['perMinute'])}/분)" if lead else "—"
            after_s = f"{after['perSet']:.2f} ({after['windows']}창)" if after else "—"
            if "exactWithWindows" in m:
                after_s += f" · 앞뒤 포함 정확 {m['exactWithWindows']:.2f} (과다 {m['overWithWindows']})"
            L.append(f"| {c} | {ex} | `{m['feature']}` {m['minAmp']:g} | {m['sets']} | {m['truth']} | {m['detected']} | "
                     f"{m['valid']}/{m['invalid']}/{m['romUnjudged']} | {m['MAE']:.2f} | {m['OBO']:.2f} | {m['exact']:.2f} | "
                     f"{m['over']} | {m['zeroSets']} | {_f(m['precision'], '.3f')} | {_f(m['recall'], '.3f')} | {m['countF1']:.3f} | {lead_s} | {after_s} |")
    L += ["", f"항상-{summary['alwaysN']} 기준선 (모든 세트를 {summary['alwaysN']}회로 부름)", "",
          "| 종목 | 세트 | 정확 | OBO | MAE | 재현율 | 과다 세트 |", "|---|---:|---:|---:|---:|---:|---:|"]
    for ex, m in sorted(summary["always"].items()):
        if m.get("sets"):
            L.append(f"| {ex} | {m['sets']} | {m['exact']:.2f} | {m['OBO']:.2f} | {m['MAE']:.2f} | {m['recall']:.3f} | {m['over']} |")
    boot_lines = []
    for c, by_ex in summary["bootstrap"].items():
        for ex, b in by_ex.items():
            def ci(k):
                v = b.get(k)
                return f"{v['point']:.2f} [{v['lo']:.2f}, {v['hi']:.2f}]" if v else "—"
            boot_lines.append(f"| {c} | {ex} | {b['subjects']} | {ci('exact')} | {ci('recall')} | {ci('exactMinusAlways')} | "
                              f"{ci('leadPerSet')} | {ci('afterPerSet')} |")
    if boot_lines:
        L += ["", "사람 단위 군집 부트스트랩 (백분위 95%, 사람 ≥ 5명일 때만)", "",
              "| 구성 | 종목 | 사람 | 정확 | 재현율 | 정확 − 항상N | 앞 헛카운트/세트 | 뒤 헛카운트/세트 |",
              "|---|---|---:|---|---|---|---|---|"] + boot_lines
    sub_lines = []
    for c in ("live", "hysteresis"):
        for ex, subs in summary["subjects"].get(c, {}).items():
            for sub, m in subs.items():
                if m.get("sets"):
                    sub_lines.append(f"| {c} | {ex} | {sub} | {m['sets']} | {_f(m.get('exact'))} | {_f(m.get('recall'), '.3f')} | "
                                     f"{_f(m.get('MAE'))} | {_f(m.get('leadPerSet'))} | {_f(m.get('afterPerSet'))} |")
    if sub_lines:
        L += ["", "사람별 (live · hysteresis)", "",
              "| 구성 | 종목 | 사람 | 세트 | 정확 | 재현율 | MAE | 앞 헛/세트 | 뒤 헛/세트 |",
              "|---|---|---|---:|---:|---:|---:|---:|---:|"] + sub_lines
    par_lines = []
    for c, by_ex in summary["parity"].items():
        for ex, p in by_ex.items():
            par_lines.append(f"| {c} | {ex} | {p['sets']} | {p['countMatch']}/{p['sets']} | {p['timesMatch']}/{p['timesCompared']} | "
                             f"{p['pendingMatch']}/{p['pendingCompared']} | {p['signalDiffers']} | {p['setsWithReset']} | {p['setsWithGap']} |")
    if par_lines:
        L += ["", "세트 로그 파리티 (재생 카운트 vs 앱이 로그한 카운트)", "",
              "| 구성 | 종목 | 로그 세트 | 카운트 일치 | 발화 시각 일치 | 버림·대기 일치 (새 코어 로그) | 신호가 로그와 다름 | 리셋을 재생한 세트 | 1.5 s 넘는 틈이 있는 세트 |",
              "|---|---|---:|---|---|---|---:|---:|---:|"] + par_lines
    if "boundary" in summary:
        L += ["", "| 구성 | 종목·카메라 | 녹화 | " + " | ".join(f"경계 F1 ±{t}ms (P/R)" for t in BOUNDARY_TOLERANCES_MS) + " |",
              "|---|---|---:|" + "---|" * len(BOUNDARY_TOLERANCES_MS)]
        for c, groups in summary["boundary"].items():
            for group, e in sorted(groups.items()):
                cells = [f"{e[f'tol{t}']['f1']:.3f} ({e[f'tol{t}']['precision']:.2f}/{e[f'tol{t}']['recall']:.2f})"
                         for t in BOUNDARY_TOLERANCES_MS]
                L.append(f"| {c} | {group} | {e['recordings']} | " + " | ".join(cells) + " |")
    return "\n".join(L) + "\n"


def replay_index(captures: Path, out: Path, configs: set[str] | None = None, series: bool = False,
                 skip_replay: bool = False) -> tuple[dict, dict[str, dict], list[str]]:
    """index 를 읽어 재생하고 (index, results, 구성 이름 목록)을 돌려준다. 다른 도구(mmfit_pair_compare 등)도 쓴다."""
    index = json.loads((captures / "index.json").read_text(encoding="utf-8"))
    rows = manifest_rows(index, configs)
    out.mkdir(parents=True, exist_ok=True)
    manifest = out / "manifest.tsv"
    results_path = out / "replay.jsonl"
    if skip_replay and results_path.is_file():
        results = load_results(results_path)
    else:
        write_manifest(rows, manifest, captures)
        results = run_jvm(manifest, results_path, out / "series" if series else None)
    extra = parity_rows(index, results)
    if extra:
        m2, r2 = out / "manifest_log.tsv", out / "replay_log.jsonl"
        if not (skip_replay and r2.is_file()):
            write_manifest(extra, m2, captures)
            run_jvm(m2, r2)
        results.update(load_results(r2))
        rows = rows + extra
    names = list(dict.fromkeys(r[0].split("|", 1)[1] for r in rows))
    return index, results, names


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("captures", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--series", action="store_true", help="프레임별 신호값을 <out>/series 에 남긴다")
    parser.add_argument("--configs", default=None,
                        help="콤마 목록(예: live,hysteresis,hysteresis+elbow_minside_both). 'live+*' 는 그 모드의 진단 전부. 기본 전부")
    parser.add_argument("--always", type=int, default=10, help="항상-N 기준선의 N (기본 10)")
    parser.add_argument("--subject-map", type=Path, default=None, help="워크아웃 → 사람 JSON (MM-Fit 표를 덮는다)")
    parser.add_argument("--boot", type=int, default=2000, help="군집 부트스트랩 반복 수")
    parser.add_argument("--seed", type=int, default=20260924)
    parser.add_argument("--skip-replay", action="store_true", help="<out>/replay.jsonl 이 있으면 재생하지 않고 채점만")
    args = parser.parse_args()

    configs = set(args.configs.split(",")) if args.configs else None
    overrides = json.loads(args.subject_map.read_text(encoding="utf-8")) if args.subject_map else None
    index, results, names = replay_index(args.captures, args.out, configs, args.series, args.skip_replay)
    summary = summarize(index, results, names, args.always, overrides, args.boot, args.seed)
    if any("reps" in s for s in index["sets"]):
        summary["boundary"] = {c: boundary(index["sets"], results, c) for c in names}
    (args.out / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=1), encoding="utf-8")
    md = markdown(summary)
    (args.out / "summary.md").write_text(md, encoding="utf-8")
    print(md)
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    raise SystemExit(main())
