"""네 종목 실촬영 계획/독립 반복 정답 채점. 촬영·라벨링은 사용자가 수행하며 자동 승격하지 않는다."""
from __future__ import annotations

import argparse
import csv
import json
from collections import Counter, defaultdict
from pathlib import Path
import sys

EXERCISES = ("크로스 런지", "사이드 런지", "스탠딩 니업", "스탠딩 사이드 크런치")
CASES = ("정상 교대", "왼쪽 몰아서 후 오른쪽", "같은 쪽만", "마지막 복귀 후 정지", "미복귀 종료",
         "가림 후 재개", "일시정지 후 재개", "촬영 방향 변경", "서 있기·걷기", "유사한 다른 운동", "편안한 작은 범위")


def plan(out: Path, people: int) -> None:
    out.mkdir(parents=True, exist_ok=True)
    with (out / "capture-plan.csv").open("w", encoding="utf-8-sig", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["id", "subject", "exercise", "case", "view", "app_version", "reviewer", "notes"])
        for subject in range(1, people + 1):
            for e, exercise in enumerate(EXERCISES, 1):
                for c, case in enumerate(CASES, 1):
                    writer.writerow([f"P{subject:02}-E{e}-C{c:02}", f"P{subject:02}", exercise, case, "", "", "", ""])
    (out / "rep-truth.csv").write_text("id,subject,exercise,return_ms,side\n", encoding="utf-8-sig")


def match(truth: list[int], pred: list[int], tolerance: int) -> list[tuple[int, int]]:
    """시간 순서의 일대일 대응: 일치 수를 최대화한 뒤 시각 오차를 최소화한다."""
    # 정답/예측 한 사건을 여러 번 맞춘 것으로 세지 않는다.
    dp = [[(0, 0, []) for _ in range(len(pred) + 1)] for _ in range(len(truth) + 1)]
    for i, t in enumerate(truth, 1):
        for j, p in enumerate(pred, 1):
            options = [dp[i-1][j], dp[i][j-1]]
            if abs(t-p) <= tolerance:
                n, error, path = dp[i-1][j-1]
                options.append((n+1, error-abs(t-p), path+[(i-1, j-1)]))
            dp[i][j] = max(options, key=lambda x: x[:2])
    return dp[-1][-1][2]


def score(truth_file: Path, predictions: Path, tolerance: int) -> dict:
    if tolerance < 0:
        raise ValueError("대응 시간 허용치는 음수일 수 없습니다.")
    by_id: dict[str, list[dict]] = defaultdict(list)
    with truth_file.open(encoding="utf-8-sig", newline="") as fp:
        for row in csv.DictReader(fp):
            if row["exercise"] not in EXERCISES:
                raise ValueError(f"지원하지 않는 종목: {row['exercise']}")
            by_id[row["id"]].append(row)
    if not by_id:
        raise ValueError("독립 반복 정답이 없습니다. 빈 파일을 성공으로 채점하지 않습니다.")
    raw = [json.loads(line) for line in predictions.read_text(encoding="utf-8").splitlines() if line.strip()]
    pred = {row["id"]: row for row in raw}
    if len(pred) != len(raw):
        raise ValueError("예측 id가 중복됩니다.")
    sets = []
    for key, labels in by_id.items():
        r = pred.get(key)
        if r is None or r.get("error"):
            raise ValueError(f"{key}: 재생 결과 없음/오류. 누락을 0회로 간주하지 않습니다.")
        exercise = labels[0]["exercise"]
        if any(x["exercise"] != exercise or x["subject"] != labels[0]["subject"] for x in labels):
            raise ValueError(f"{key}: 한 세트에 서로 다른 종목/사람")
        if r.get("exercise") != exercise:
            raise ValueError(f"{key}: 정답과 재생 종목 불일치")
        events = sorted((int(x["return_ms"]), x["side"].strip()) for x in labels if x["return_ms"].strip())
        if any(s not in ("L", "R", "") for _, s in events):
            raise ValueError(f"{key}: side는 L/R/빈 값만 허용합니다.")
        if exercise in EXERCISES[:2] and any(not s for _, s in events):
            raise ValueError(f"{key}: 런지의 표시 쌍을 채점하려면 독립 좌우 정답이 필요합니다.")
        times = r.get("publishedMs", [])
        forms = {x["t_ms"]: x.get("side") for x in (r.get("repForm") or {}).get("reps", [])}
        pairs = match([t for t, _ in events], times, tolerance)
        known = [(events[i][1], forms.get(times[j])) for i, j in pairs if events[i][1] and forms.get(times[j])]
        l = Counter(side for _, side in events)
        p = Counter(forms.get(t) for t in times)
        # 런지는 미확인 쪽을 쌍에 보충하지 않는다. 들기는 원시 단일 궤적마다 한 회다.
        count = min(l["L"], l["R"]) if exercise in EXERCISES[:2] else len(events)
        counted = min(p["L"], p["R"]) if exercise in EXERCISES[:2] else len(times)
        sets.append({"id": key, "subject": labels[0]["subject"], "exercise": exercise,
                     "truth_events": len(events), "pred_events": len(times), "matched": len(pairs),
                     "false_events": len(times)-len(pairs), "missed_events": len(events)-len(pairs),
                     "known_side_matches": len(known), "correct_side_matches": sum(a==b for a,b in known),
                     "truth_display_count": count, "pred_display_count": counted, "absolute_count_error": abs(count-counted)})
    return {"tolerance_ms": tolerance, "scope": "독립 반복 정답과 고정 빌드의 일치. 자세 규칙 검증/출시 승격 아님", "sets": sets,
            "summary": {"sets": len(sets), "people": len({x['subject'] for x in sets}),
                        "exact_set_fraction": sum(x['absolute_count_error']==0 for x in sets)/len(sets),
                        "count_mae": sum(x['absolute_count_error'] for x in sets)/len(sets),
                        **{k: sum(x[k] for x in sets) for k in ('truth_events','pred_events','matched','false_events','missed_events','known_side_matches','correct_side_matches')}}}


def main() -> None:
    p=argparse.ArgumentParser(description=__doc__); sub=p.add_subparsers(dest="cmd",required=True)
    a=sub.add_parser("plan"); a.add_argument("--out",type=Path,required=True); a.add_argument("--people",type=int,default=3)
    a=sub.add_parser("score"); a.add_argument("--truth",type=Path,required=True); a.add_argument("--predictions",type=Path,required=True)
    a.add_argument("--out",type=Path,required=True); a.add_argument("--tolerance-ms",type=int,default=750)
    args=p.parse_args()
    if args.cmd=="plan":
        if args.people<1: p.error("people은 1 이상이어야 합니다.")
        plan(args.out,args.people)
    else:
        result=score(args.truth,args.predictions,args.tolerance_ms)
        args.out.parent.mkdir(parents=True,exist_ok=True)
        args.out.write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding="utf-8")
        print(json.dumps(result["summary"],ensure_ascii=False))


if __name__=="__main__":
    sys.stdout.reconfigure(encoding="utf-8"); main()
