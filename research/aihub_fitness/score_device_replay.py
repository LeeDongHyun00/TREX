"""휴대폰 출력과 원본 조건 라벨을 대조한다. 유보와 시간 정답 부재를 정확도에서 숨기지 않는다."""
from collections import Counter, defaultdict
import json
import hashlib
import math
from pathlib import Path
import statistics
import sys
import zipfile

HERE = Path(__file__).resolve().parent
OUT = HERE / "outputs/device_replay"


def rate(a, b): return None if not b else round(a / b, 4)
def pct(v): return "—" if v is None else f"{100*v:.1f}%"


def summary(rows):
    c = Counter()
    for row in rows:
        good, verdict = row["truth_good"], row["verdict"]
        c["n"] += 1
        c["normal" if good else "violation"] += 1
        if verdict == "ABSTAIN": c["abstain"] += 1; continue
        c["judged"] += 1
        c[("FP" if good else "TP") if verdict == "VIOLATION" else ("TN" if good else "FN")] += 1
    return dict(c, coverage=rate(c["judged"], c["n"]), fpr=rate(c["FP"], c["FP"]+c["TN"]),
                recall_judged=rate(c["TP"], c["TP"]+c["FN"]), recall_all=rate(c["TP"], c["violation"]),
                accuracy_judged=rate(c["TP"]+c["TN"], c["judged"]))


def main():
    manifest = json.loads((OUT / "manifest.json").read_text(encoding="utf-8"))
    sequences = {s["id"]: s for s in manifest["sequences"]}
    results = [json.loads(line) for line in (OUT / "results.jsonl").read_text(encoding="utf-8").splitlines() if line]
    with zipfile.ZipFile(OUT / "replay.zip") as archive:
        manifest_bytes = archive.read("manifest.json")
    assert json.loads(manifest_bytes) == manifest, "단말 전송 묶음과 채점 manifest가 다르다"
    expected_hash = hashlib.sha256(manifest_bytes).hexdigest()
    assert all(r["manifest_sha256"] == expected_hash for r in results), "다른 데이터셋 결과가 섞였다"
    assert len(results) == len(sequences) and {r["id"] for r in results} == set(sequences), "미완료·중복 결과는 채점하지 않는다"
    rules = json.loads((HERE / "rules/rules_floor_v0.json").read_text(encoding="utf-8"))["rules"]
    by_id = {r["id"]: r for r in rules}
    rows, timings, decode_timings, cases, pose = [], [], [], [], defaultdict(list)
    joint_map = {"Nose": 0, "Left Eye": 2, "Right Eye": 5, "Left Ear": 7, "Right Ear": 8,
                 "Left Shoulder": 11, "Right Shoulder": 12, "Left Elbow": 13, "Right Elbow": 14,
                 "Left Wrist": 15, "Right Wrist": 16, "Left Hip": 23, "Right Hip": 24,
                 "Left Knee": 25, "Right Knee": 26, "Left Ankle": 27, "Right Ankle": 28}
    for result in results:
        seq = sequences[result["id"]]
        for stage in ("live_results", "clip_results"):
            for rr in result[stage]:
                # 시간·반복 정답이 없으므로 관찰 결과를 보존하되 정확도 분모에서 제외한다.
                if rr["kind"] != "window": continue
                condition = " ".join(rr["condition"].split())
                if condition not in seq["conditions"]: continue
                truth = seq["conditions"][condition]
                assert type(truth) is bool
                row = dict(stage=stage, exercise=result["exercise"], view=seq["view"], rule_id=rr["id"],
                           condition=condition, truth_good=truth, verdict=rr["verdict"],
                           preferred_view=seq["view"] == by_id[rr["id"]].get("view_best_front"),
                           clip_id=seq["clip_id"], reason=rr.get("abstain_reason"), value=rr.get("value"), n=rr["n"])
                rows.append(row)
                if row["preferred_view"] and rr["verdict"] != "ABSTAIN" and ((rr["verdict"] == "OK") != truth): cases.append(row)
        for i, frame in enumerate(result["frames"]):
            if i > 0:  # 각 클립 첫 추론 워밍업은 따로 제외
                timings.append(frame["infer_ms"]); decode_timings.append(frame["decode_infer_features_ms"])
            if not frame["detected"] or seq["gt_active"][i] != "Yes": continue
            gt = seq["gt"][i]
            required = [gt.get(k) for k in ("Left Shoulder", "Right Shoulder", "Left Hip", "Right Hip")]
            if any(p is None for p in required): continue
            ls, rs, lh, rh = required
            torso = math.hypot((ls["x"]+rs["x"]-lh["x"]-rh["x"])/2, (ls["y"]+rs["y"]-lh["y"]-rh["y"])/2)
            if torso < 20: continue
            for key, idx in joint_map.items():
                p = gt.get(key)
                if p is None or frame["visibility"][idx] < .35: continue
                x, y = frame["xy"][2*idx]*frame["w"], frame["xy"][2*idx+1]*frame["h"]
                if not (0 <= p["x"] < frame["w"] and 0 <= p["y"] < frame["h"]): continue
                pose[result["exercise"]].append(math.hypot(x-p["x"], y-p["y"])/torso)
    grouped = defaultdict(list)
    for row in rows: grouped[(row["stage"], row["rule_id"], row["view"])].append(row)
    metrics = [dict(stage=k[0], rule_id=k[1], view=k[2], **summary(v)) for k, v in grouped.items()]
    preferred = {stage: summary([r for r in rows if r["stage"] == stage and r["preferred_view"]])
                 for stage in ("live_results", "clip_results")}
    plank_holds = Counter(rr["verdict"] for r in results if r["exercise"] == "플랭크"
                          for rr in r["live_results"] if rr["kind"] == "hold")
    data = dict(split=manifest["split"], sequences=len(results), clips=len({r["clip_id"] for r in results}),
                frames=sum(len(r["frames"]) for r in results), detected_frames=sum(r["detected_frames"] for r in results),
                measured_frames=sum(r["measured_frames"] for r in results), devices=dict(Counter(r["model"] for r in results)),
                delegates=dict(Counter(r["delegate"] for r in results)),
                infer_p50_ms=statistics.median(timings), infer_p95_ms=sorted(timings)[int(.95*(len(timings)-1))],
                decode_infer_features_p50_ms=statistics.median(decode_timings), per_rule_view=metrics,
                preferred_view_summary=preferred, plank_live_hold_verdicts=dict(plank_holds),
                mismatch_cases=cases, landmark_error={ex: dict(observed_joints=len(v), median_torso_normalized=statistics.median(v),
                    pck_at_0_2=rate(sum(x <= .2 for x in v), len(v))) for ex, v in pose.items()})
    (OUT / "scores.json").write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
    lines = ["# 휴대폰 AIHub 재생 진단 결과", "", f"{data['split']} · {data['clips']}클립 · {data['sequences']}개 클립/뷰 · {data['frames']}장", "",
             "Training 재사용 진단이다. 독립 검증 정확도·집에서의 정확도·새 사용자 성능으로 해석하지 않는다. 조건별 정상/위반 균형 표본도 아니다.", "",
             f"실행 기기 {data['devices']} / delegate {data['delegates']}",
             f"추론 지연(클립 첫 프레임 제외): 중앙값 {data['infer_p50_ms']}ms / p95 {data['infer_p95_ms']}ms. 카메라 캡처·렌더링 시간과 실제 서비스 FPS는 포함하지 않는다.", "",
             "JSON의 시각이 없어 600ms 간격을 가정했다. 반복·플랭크 유지 정확도는 채점하지 않았다. 유보를 정상으로 세지 않았다.", "",
             "## 확인된 문제와 판정 범위", "",
             f"- 피처가 계산된 이미지는 {data['measured_frames']:,}/{data['frames']:,}장({pct(rate(data['measured_frames'], data['frames']))})이다. 자세 판정 정확도를 뜻하지 않는다.",
             f"- 고정 권장 뷰의 window 조건 사례는 앱 창(live)에서 {preferred['live_results'].get('judged',0)}/{preferred['live_results']['n']}건 판정, {preferred['live_results'].get('abstain',0)}건 유보다. 전체 프레임 창(clip)은 {preferred['clip_results'].get('judged',0)}/{preferred['clip_results']['n']}건 판정, {preferred['clip_results'].get('abstain',0)}건 유보다. 같은 클립의 여러 조건이 포함되므로 독립 표본 수가 아니다.",
             f"- 라벨상 위반 {preferred['live_results']['violation']}건 중 검출은 live {preferred['live_results'].get('TP',0)}건, clip {preferred['clip_results'].get('TP',0)}건이다. 유보된 위반도 전체 검출률의 분모에 포함한다.",
             "- 전체 프레임 창에서도 니푸쉬업·푸시업 고개 조건의 위반 누락과 힙쓰러스트 고개 조건의 정상 오탐이 있었다. 아래 사례를 보존했으며 이 표본에 맞춰 임계값을 조정하지 않았다.",
             f"- 플랭크의 최종 hold 결과는 {plank_holds.get('ABSTAIN',0)}/{sum(plank_holds.values())}개가 유보다. 약 9초짜리 합성 시간축은 시작 앵커와 초기 5초 기준 구간을 확보하기에 짧다. 실제 운동 세션의 유지 판정 정확도는 별도 검증해야 한다.",
             "- 크런치·Y - Exercise에는 현재 활성 자세 판정 규칙이 없다. 재생 완료나 관절 검출을 자세 정상 판정으로 바꾸지 않는다.",
             "- 제공 위치의 원본 tar 36개에서 Validation 이미지 매칭은 0건이었다. 누락된 Validation 원본과 실제 시간·반복 라벨이 다음 검증에 필요하다. 파일 조사 근거는 DEVICE_REPLAY_INVENTORY.md에 있다.", "",
             "## 고정 권장 뷰에서 조건별 결과", "", "live = 합성 시각에 앱 앵커·종료 창 적용. clip = 전체 프레임 창을 평가한 원인 분석용 결과. 클립 전체 라벨을 대조하므로 프레임별 정답 검증은 아니다. 권장 뷰는 실행 전에 규칙 파일에 지정된 값이며 실제 휴대폰 측면 촬영과 같다고 보장하지 않는다.", "",
             "| 단계 | 운동 / 조건 | 뷰 | 정상/위반 표본 | 판정/전체 | 정상 오탐률 | 위반 검출률(판정 중) | 위반 검출률(유보 포함 전체) |", "|---|---|---|---:|---:|---:|---:|---:|"]
    for rule in rules:
        if rule.get("kind", "window") != "window" or rule["status"] == "exclude": continue
        for stage in ("live_results", "clip_results"):
            group = [r for r in rows if r["rule_id"] == rule["id"] and r["stage"] == stage and r["preferred_view"]]
            m = summary(group)
            lines.append(f"| {stage.split('_')[0]} | {rule['exercise']} / {rule['condition']} | {rule.get('view_best_front')} | {m.get('normal',0)}/{m.get('violation',0)} | {m.get('judged',0)}/{m.get('n',0)} | {pct(m['fpr'])} ({m.get('FP',0)}/{m.get('FP',0)+m.get('TN',0)}) | {pct(m['recall_judged'])} ({m.get('TP',0)}/{m.get('TP',0)+m.get('FN',0)}) | {pct(m['recall_all'])} ({m.get('TP',0)}/{m.get('violation',0)}) |")
    lines += ["", "## 재확인할 불일치 사례", "",
              "클립 단위 라벨과 해당 평가 창의 결과가 어긋난 사례다. 특히 live 창은 클립 일부만 보므로 프레임 단위 재라벨 없이 원인을 모두 임계값 오류로 단정할 수 없다.", "",
              "| 단계 | 클립 | 운동 / 조건 | 뷰 | 라벨 | 앱 결과 | 측정값 | 프레임 수 |",
              "|---|---|---|---|---|---|---:|---:|"]
    for case in cases:
        lines.append(f"| {case['stage'].split('_')[0]} | {case['clip_id']} | {case['exercise']} / {case['condition']} | {case['view']} | {'정상' if case['truth_good'] else '위반'} | {case['verdict']} | {case['value']:.3f} | {case['n']} |")
    lines += ["", "## 제한", "", "- 표본이 작은 초기 진단이다. 서로 다른 뷰를 독립된 사람 표본처럼 세지 않는다.",
              "- floor 3D GT는 품질 문제가 있어 자세 정답으로 쓰지 않았다. 2D 관절 오차도 가시 관절/active 프레임의 조건부 지표다.",
              "- 앱의 현재 규칙/모델/최종 집계 함수를 공유하지만 이미지 입력은 카메라/IMU·실시간 스케줄러를 우회한다.",
              "- 권장 뷰 이외 결과는 scores.json에 별도 보존한다. 결과를 본 뒤 좋은 뷰만 선택하지 않는다.",
              "- 이 실행은 임계값을 변경하거나 beta를 ship으로 승격시키지 않는다.", ""]
    (HERE / "DEVICE_REPLAY_RESULTS.md").write_text("\n".join(lines), encoding="utf-8")
    print(json.dumps({k:v for k,v in data.items() if k not in ("per_rule_view", "mismatch_cases")}, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
