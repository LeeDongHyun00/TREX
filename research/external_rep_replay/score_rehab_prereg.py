# -*- coding: utf-8 -*-
"""REHAB24-6 사전 등록 채점 — docs/REP_ENGINE_DESIGN.md §17 의 판정을 기계적으로 적용한다(결과를 보기 전에 커밋).

입력
    captures   extract_mediapipe.py rehab 산출 폴더(index.json — 녹화×카메라마다 reps[[시작ms, 끝ms, 정자세]], camera, exercise)
    results    run_replay.py --out 폴더(replay.jsonl — 녹화×카메라×구성마다 한 줄)

무엇을 재나 (종목 × 카메라, 카메라는 합치지 않는다 — 같은 반복을 두 번 세지 않게)
    경계 일치  반복의 **발화 시각**(publishedMs — 새 코어는 사이클이 복귀한 시각, 레거시는 완료 프레임)이 정답 반복 구간 ± 허용 오차
              안이면 일치. 시간순 1:1 탐욕 배정(한 발화는 한 반복만, 한 반복은 한 발화만). 정답 구간 밖 발화는 오탐 — 녹화 앞뒤의
              준비·정리 동작에서 난 발화도 오탐으로 센다(보수적).
              발화 시각을 쓰는 이유: 새 코어는 첫 두 사이클을 둘째 사이클 때 함께 **발표**한다. 발표 시각으로 맞추면 첫 반복이 늘
              자기 구간 밖에 떨어져 "첫 반복을 놓치고 둘째를 두 번 셌다" 로 채점된다 — 카운터가 어떤 사이클을 셌는지가 아니라
              화면에 언제 떴는지를 재게 된다. 그 몫(표시 지연)은 발표 시각 기준 F1 로 따로 적는다(판정에 쓰지 않는다).
    재현율      전체 · 정자세 반복(correctness=1) · 비정자세 반복을 따로. 비정자세는 얕은 반복일 수 있어 판정에 쓰지 않는다.
    녹화 단위  발표 수가 정답보다 많은 녹화(과다), 0회 녹화.

판정 (§17, 새 코어 = 구성 'hysteresis', 허용 오차 ±500 ms). 한 종목이 "MM-Fit 설계가 옮겨 간다" 는 두 카메라 모두에서:
    P1 정자세 반복 재현율 ≥ 0.95     P2 정밀도 ≥ 0.95     P3 경계 F1 ≥ 0.90     P4 새 코어 재현율 ≥ 지금 앱(live) 재현율
하나라도 못 넘으면 실패 모드를 적고 **조정하지 않는다**(CC BY-NC, 측정 전용 — 원칙 #4). 넘어도 성능 주장이 아니다(Gate B 에서만).

사용법
    python score_rehab_prereg.py <captures> <results> --out <폴더>      # rehab_prereg.json + rehab_prereg.md
    python score_rehab_prereg.py --self-test
"""
from __future__ import annotations

import argparse
import json
import sys
import tempfile
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from run_replay import fire_times, set_key  # noqa: E402

EXERCISES = ("바벨 스쿼트", "스텝 포워드 다이나믹 런지")
TOLERANCES_MS = (0, 500, 1000)
JUDGE_TOL_MS = 500
CRITERIA = {"P1": 0.95, "P2": 0.95, "P3": 0.90}
JUDGED, BASELINE = "hysteresis", "live"


def match(times: list[int], reps: list[list], tol: int) -> dict:
    """시간순 1:1 탐욕 배정. reps = [[시작ms, 끝ms, 정자세], ...]."""
    truth = sorted(((a - tol, b + tol, bool(c)) for a, b, c in reps), key=lambda x: x[0])
    used = [False] * len(truth)
    tp = fp = 0
    for t in sorted(times):
        k = next((i for i, (a, b, _) in enumerate(truth) if not used[i] and a <= t <= b), None)
        if k is None:
            fp += 1
        else:
            used[k] = True
            tp += 1
    hit_c = sum(1 for i, x in enumerate(truth) if used[i] and x[2])
    hit_i = sum(1 for i, x in enumerate(truth) if used[i] and not x[2])
    n_c = sum(1 for x in truth if x[2])
    return {"tp": tp, "fp": fp, "fn": used.count(False), "correct": n_c, "correctHit": hit_c,
            "incorrect": len(truth) - n_c, "incorrectHit": hit_i}


def _ratio(a: int, b: int) -> float | None:
    return a / b if b else None


def _f1(tp: int, fp: int, fn: int) -> float:
    return 2 * tp / (2 * tp + fp + fn) if tp else 0.0


def aggregate(rows: list[tuple[dict, dict]], tol: int, publish: bool = False) -> dict:
    s = defaultdict(int)
    over = zero = 0
    for st, r in rows:
        times = list(r.get("repTimesMs", [])) if publish else fire_times(r)
        m = match(times, st["reps"], tol)
        for k, v in m.items():
            s[k] += v
        n = len(fire_times(r))
        over += n > len(st["reps"])
        zero += n == 0
    return {"recordings": len(rows), "tp": s["tp"], "fp": s["fp"], "fn": s["fn"],
            "precision": _ratio(s["tp"], s["tp"] + s["fp"]), "recall": _ratio(s["tp"], s["tp"] + s["fn"]),
            "f1": _f1(s["tp"], s["fp"], s["fn"]),
            "recallCorrect": _ratio(s["correctHit"], s["correct"]), "correctReps": s["correct"],
            "recallIncorrect": _ratio(s["incorrectHit"], s["incorrect"]), "incorrectReps": s["incorrect"],
            "overRecordings": over, "zeroRecordings": zero}


def score(captures: Path, results: Path) -> dict:
    index = json.loads((captures / "index.json").read_text(encoding="utf-8"))
    res: dict[str, dict] = {}
    for line in (results / "replay.jsonl").read_text(encoding="utf-8").splitlines():
        if line.strip():
            r = json.loads(line)
            res[r["id"]] = r
    configs = sorted({k.split("|", 1)[1] for k in res})
    groups: dict[tuple, list] = defaultdict(list)
    for s in index["sets"]:
        if s.get("exercise") not in EXERCISES or "reps" not in s:
            continue
        for c in configs:
            r = res.get(f"{set_key(s)}|{c}")
            if r is not None and "error" not in r:
                groups[(c, s["exercise"], str(s.get("camera", "")))].append((s, r))
    table = {}
    for (c, ex, cam), rows in sorted(groups.items()):
        table[f"{c}|{ex}|cam{cam}"] = {
            "config": c, "exercise": ex, "camera": cam,
            **{f"tol{t}": aggregate(rows, t) for t in TOLERANCES_MS},
            "publishTol500": aggregate(rows, JUDGE_TOL_MS, publish=True),
        }
    verdicts = {}
    for ex in EXERCISES:
        cams = sorted({v["camera"] for v in table.values() if v["exercise"] == ex and v["config"] == JUDGED})
        per_cam, ok_all = {}, bool(cams)
        for cam in cams:
            j = table.get(f"{JUDGED}|{ex}|cam{cam}", {}).get(f"tol{JUDGE_TOL_MS}")
            b = table.get(f"{BASELINE}|{ex}|cam{cam}", {}).get(f"tol{JUDGE_TOL_MS}")
            if j is None:
                ok_all = False
                continue
            checks = {
                "P1": j["recallCorrect"] is not None and j["recallCorrect"] >= CRITERIA["P1"],
                "P2": j["precision"] is not None and j["precision"] >= CRITERIA["P2"],
                "P3": j["f1"] >= CRITERIA["P3"],
                "P4": b is not None and j["recall"] is not None and b["recall"] is not None and j["recall"] >= b["recall"],
            }
            per_cam[f"cam{cam}"] = {"checks": checks, "pass": all(checks.values())}
            ok_all = ok_all and all(checks.values())
        verdicts[ex] = {"cameras": per_cam, "transfers": ok_all if per_cam else None}
    return {"prereg": "docs/REP_ENGINE_DESIGN.md §17", "judgedConfig": JUDGED, "baselineConfig": BASELINE,
            "toleranceMs": JUDGE_TOL_MS, "criteria": CRITERIA, "configs": configs, "table": table, "verdicts": verdicts}


def _f(x, fmt=".3f") -> str:
    return "—" if x is None else format(x, fmt)


def markdown(out: dict) -> str:
    L = ["# REHAB24-6 사전 등록 채점 (설계 §17)", "",
         f"판정 구성 `{out['judgedConfig']}`, 기준선 `{out['baselineConfig']}`, 허용 오차 ±{out['toleranceMs']} ms, 발화 시각 기준. "
         "CC BY-NC — 측정 전용, 이 결과로 어떤 상수도 바꾸지 않는다.", "",
         "## 판정", "", "| 종목 | 카메라 | P1 정자세 재현율 ≥ 0.95 | P2 정밀도 ≥ 0.95 | P3 경계 F1 ≥ 0.90 | P4 재현율 ≥ 지금 앱 | 통과 |",
         "|---|---|---|---|---|---|---|"]
    for ex, v in out["verdicts"].items():
        for cam, c in v["cameras"].items():
            j = out["table"][f"{out['judgedConfig']}|{ex}|{cam}"][f"tol{out['toleranceMs']}"]
            b = out["table"].get(f"{out['baselineConfig']}|{ex}|{cam}", {}).get(f"tol{out['toleranceMs']}", {})
            mark = lambda k: "✓" if c["checks"][k] else "✗"
            L.append(f"| {ex} | {cam} | {mark('P1')} {_f(j['recallCorrect'])} | {mark('P2')} {_f(j['precision'])} | "
                     f"{mark('P3')} {_f(j['f1'])} | {mark('P4')} {_f(j['recall'])} vs {_f(b.get('recall'))} | {'통과' if c['pass'] else '실패'} |")
        L.append(f"| {ex} | **두 카메라** | | | | | **{'옮겨 감' if v['transfers'] else '옮겨 가지 않음'}** |")
    L += ["", "## 구성별 (허용 오차별 F1 · 재현율)", "",
          "| 구성 | 종목 | 카메라 | 녹화 | 정답 반복 (정자세) | F1 ±0 | ±500 (P/R) | ±1000 | 정자세 / 비정자세 재현율 (±500) | 과다 녹화 | 0회 녹화 | 발표 시각 F1 ±500 |",
          "|---|---|---|---:|---|---:|---|---:|---|---:|---:|---:|"]
    for key, v in out["table"].items():
        t0, t5, t10, pb = v["tol0"], v["tol500"], v["tol1000"], v["publishTol500"]
        L.append(f"| {v['config']} | {v['exercise']} | cam{v['camera']} | {t5['recordings']} | {t5['tp'] + t5['fn']} ({t5['correctReps']}) | "
                 f"{t0['f1']:.3f} | {t5['f1']:.3f} ({_f(t5['precision'])}/{_f(t5['recall'])}) | {t10['f1']:.3f} | "
                 f"{_f(t5['recallCorrect'])} / {_f(t5['recallIncorrect'])} | {t5['overRecordings']} | {t5['zeroRecordings']} | {pb['f1']:.3f} |")
    return "\n".join(L) + "\n"


def self_test() -> int:
    """합성 녹화 둘로 판정 규약을 확인한다: 발화 vs 발표 시각, 정자세 분리, 오탐, P4."""
    work = Path(tempfile.mkdtemp(prefix="rehab_prereg_selftest_"))
    cap, res = work / "cap", work / "res"
    cap.mkdir(); res.mkdir()
    reps = [[1000, 3000, 1], [3000, 5000, 1], [5000, 7000, 0]]
    sets = []
    for cam in ("17", "18"):
        for ex in EXERCISES:
            sets.append({"video": "V1", "camera": cam, "exercise": ex, "truthReps": 3, "reps": reps,
                         "capture": f"V1_{cam}_{ex}/V1.cap"})
    (cap / "index.json").write_text(json.dumps({"sets": sets}, ensure_ascii=False), encoding="utf-8")
    lines = []
    for s in sets:
        k = set_key(s)
        # 새 코어: 발화 2500·4500·6500 (첫 쌍은 4500 에 함께 발표), 정답 밖 9000 에 오탐 하나
        lines.append({"id": f"{k}|hysteresis", "publishedMs": [2500, 4500, 6500, 9000], "repTimesMs": [4500, 4500, 6500, 9000]})
        # 지금 앱: 둘째만 센다
        lines.append({"id": f"{k}|live", "publishedMs": [4600], "repTimesMs": [4600]})
    (res / "replay.jsonl").write_text("\n".join(json.dumps(x, ensure_ascii=False) for x in lines), encoding="utf-8")
    out = score(cap, res)
    checks = []
    t = out["table"][f"hysteresis|{EXERCISES[0]}|cam17"]
    checks.append(("발화 시각: 3/3 일치, 오탐 1", t["tol0"]["tp"] == 3 and t["tol0"]["fp"] == 1))
    checks.append(("발표 시각: 첫 반복이 둘째 구간에 떨어져 일치 2", t["publishTol500"]["tp"] == 2))
    checks.append(("정자세 재현율 1.0, 비정자세 1.0", t["tol500"]["recallCorrect"] == 1.0 and t["tol500"]["recallIncorrect"] == 1.0))
    checks.append(("정밀도 0.75 → P2 실패", out["verdicts"][EXERCISES[0]]["cameras"]["cam17"]["checks"]["P2"] is False))
    checks.append(("P4 새 코어 재현율 1.0 ≥ 지금 앱 0.33", out["verdicts"][EXERCISES[0]]["cameras"]["cam17"]["checks"]["P4"] is True))
    checks.append(("과다 녹화 1 (4 > 3)", t["tol500"]["overRecordings"] == 1))
    checks.append(("판정: 옮겨 가지 않음", out["verdicts"][EXERCISES[0]]["transfers"] is False))
    md = markdown(out)
    checks.append(("마크다운에 두 카메라 행", md.count("**두 카메라**") == len(EXERCISES)))
    for name, ok in checks:
        print(f"{'PASS' if ok else 'FAIL'}  {name}")
    n_ok = sum(ok for _, ok in checks)
    print(f"\n{n_ok}/{len(checks)} 통과 — 작업 폴더 {work}")
    return 0 if n_ok == len(checks) else 1


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("captures", type=Path, nargs="?")
    ap.add_argument("results", type=Path, nargs="?")
    ap.add_argument("--out", type=Path)
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    if not (a.captures and a.results and a.out):
        ap.error("captures, results, --out 이 필요하다")
    out = score(a.captures, a.results)
    a.out.mkdir(parents=True, exist_ok=True)
    (a.out / "rehab_prereg.json").write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")
    (a.out / "rehab_prereg.md").write_text(markdown(out), encoding="utf-8")
    print(markdown(out))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
