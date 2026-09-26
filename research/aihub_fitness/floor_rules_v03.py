#!/usr/bin/env python
"""바닥 규칙 v0.3 — FLOOR_POSTURE_DEFINITION.md(spec §34) 의 처분표를 규칙 JSON 에 적용한다.

v0.2 → v0.3 변경 (전부 beta 유지):
  재정의  플랭크 정렬        : 세트 mean(방향 없음) → kind=hold. 부호 있는 hip_dev_ankle, **사용자 자신의 초반 안정 구간**을 기준으로
                              위/아래 허용폭을 넘는 상태가 2초 이상 지속되면 '무너짐' 이벤트. 허용폭은 AIHub 정상 클립의 클립 내 위·아래 폭 중앙값.
  재정의  힙쓰러스트 상단     : v0.2 `hip_dev_ankle__p10 > -0.14` 는 정상 클립의 95% 를 위반으로 본다(§28d 수정이 틀림). → kind=rep,
                              렙별 cycleMax ≥ 정상 max p10(0.134) 이면 유효. 정상 89% / 위반('깔짝깔짝') 48% 유효 = 판별력 4.7×.
  재정의  힙쓰러스트 고개     : std(흔들림) → p10 수준형 `head_trunk_ang__p10 < thr` (AUC 0.83). 문구 '고개 들려 있어요' 와 근거가 일치.
  재정의  시저크로스 다리 거리 : 방향 유지, 임계값을 Youden(정상 오탐률 0.34) → 정상 p10(오탐률 0.10) 으로. 검출률 0.78→0.36 을 감수.
  통계량  푸시업/니푸쉬업 깊이 : 세트 min → kind=rep, 렙별 cycleMin ≤ ROM 임계(REP_VALIDITY, 판별력 검증) 이면 유효.
  중단    크런치 견갑골, Y 경추 : exclude. 크런치는 MP 가 어깨-지면 높이를 못 재고(ρ≈0) 머리 프록시가 '목만 당김' 을 통과시킴,
                              Y 는 목을 잰다면서 골반을 잼(감사 A·D).
  유지    시저 시선 · 레그 레이즈 고개 · 푸시업/니푸쉬업 고개 · 손 위치 2
  미채택  푸시업 몸통 일직선(채택 뷰 AUC 0.64 < 컷 0.72), 레그 레이즈 무릎각(측면 투영 붕괴, 정상 최소 6°), 다리 하단 높이(AUC 0.61)

입력: rules/rules_floor_v0.json(v0.2), outputs/{clips,kp2d,conditions}.parquet   출력: rules/rules_floor_v0.json(v0.3), FLOOR_RULES_V03.md
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.metrics import roc_auc_score, roc_curve

import export_floor_rules as ef

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

HERE = Path(__file__).resolve().parent
OUT = HERE / "outputs"
RULES = HERE / "rules" / "rules_floor_v0.json"
ASSET = HERE.parent.parent / "app" / "src" / "main" / "assets" / "posture" / "rules_floor_v0.json"
HOLD_MIN_BREAK_MS = 2000
HOLD_BASELINE_MS = 5000
REP_MAX_INVALID_FRAC = 0.34
REP_MIN_REPS = 2
ROM = {"푸시업": ("min", 0.7101), "니푸쉬업": ("min", 0.8377)}   # RepCounter.ROM 과 동일 (REP_VALIDITY.md)


def clip_features(view_of: dict[str, str]) -> pd.DataFrame:
    cols = ["clip_id", "view_letter", "frame_idx"] + [f"{j}_{a}" for j in ef.JOINTS for a in "xy"]
    clips = pd.read_parquet(OUT / "clips.parquet")[["clip_id", "exercise", "performer"]]
    k2 = pd.read_parquet(OUT / "kp2d.parquet", columns=cols).merge(clips, on="clip_id")
    k2 = k2[k2.exercise.isin(view_of)]
    rows = []
    for (cid, view), d in k2.sort_values(["clip_id", "view_letter", "frame_idx"]).groupby(["clip_id", "view_letter"]):
        ex = d.exercise.iloc[0]
        if view != view_of[ex] or len(d) < ef.MIN_FRAMES:
            continue
        frames = np.stack([d[[f"{j}_x", f"{j}_y"]].to_numpy(dtype=np.float64) for j in ef.JOINTS], axis=1)
        F = ef.frame_features_stream(frames, ef.JOINTS)
        r = dict(clip_id=cid, exercise=ex, performer=str(d.performer.iloc[0]))
        for k, v in F.items():
            v = np.asarray(v, dtype=float)
            v = v[np.isfinite(v)]
            if len(v) < ef.MIN_FRAMES:
                continue
            r.update({f"{k}__mean": v.mean(), f"{k}__min": v.min(), f"{k}__max": v.max(), f"{k}__std": v.std(),
                      f"{k}__p10": np.quantile(v, .1), f"{k}__p90": np.quantile(v, .9)})
        rows.append(r)
    return pd.DataFrame(rows)


def youden(x, yviol):
    fpr, tpr, thr = roc_curve(yviol, x)
    i = int(np.argmax(tpr - fpr))
    return float(thr[i]), float(fpr[i]), float(tpr[i])


def main():
    doc = json.loads((HERE / "rules" / "rules_floor_v02_source.json").read_text(encoding="utf-8"))
    rules = doc["rules"]
    by_id = {r["id"]: r for r in rules}
    view_of = {r["exercise"]: r["view_best_front"] for r in rules}
    conds = pd.read_parquet(OUT / "conditions.parquet")
    lab = conds.pivot_table(index="clip_id", columns="condition", values="value", aggfunc="first")
    df = clip_features(view_of).join(lab, on="clip_id")
    L = ["# 바닥 규칙 v0.3 — 처분표 적용 결과 (spec §34)\n", f"- 표본: 채택 뷰 (클립) {len(df)} — " + ", ".join(f"{k} {v}" for k, v in df.groupby('exercise').size().items()), ""]

    def base_rule(old: dict, **kw) -> dict:
        r = dict(old)
        r.update(kw)
        return r

    # ---- 플랭크: hold ----
    p = df[df.exercise == "플랭크"]
    yp = p["몸통과 엉덩이의 정렬 유지"].astype(float)
    up = (p["hip_dev_ankle__p90"] - p["hip_dev_ankle__mean"])[yp == 1]
    dn = (p["hip_dev_ankle__mean"] - p["hip_dev_ankle__p10"])[yp == 1]
    spread = p["hip_dev_ankle__p90"] - p["hip_dev_ankle__p10"]
    auc_spread = roc_auc_score((yp == 0).astype(int), spread)
    tol_up = round(max(0.06, float(up.median())), 3)
    tol_dn = round(max(0.06, float(dn.median())), 3)
    old = by_id["floor|플랭크|몸통과 엉덩이의 정렬 유지"]
    by_id[old["id"]] = base_rule(
        old, kind="hold", feature="hip_dev_ankle", base_feature="hip_dev_ankle", stat="hold", op=">", threshold=tol_up,
        hold=dict(feature="hip_dev_ankle", tol_up=tol_up, tol_down=tol_dn, min_break_ms=HOLD_MIN_BREAK_MS, baseline_ms=HOLD_BASELINE_MS,
                  up_label="엉덩이 솟음", down_label="엉덩이 처짐"),
        cv_auc=round(float(max(auc_spread, 1 - auc_spread)), 4), cv_balacc=None, normal_median=None, normal_fpr=None,
        personal_baseline=None,
        reason="§34 재정의: 세트 mean(방향 없음, 연기 임계 133°)은 실기기 무너짐(2026-09-10, 골반 +0.13·25~30°)을 평균 158° 로 통과시켰다. "
               "홀드형 — 사용자 자신의 초반 안정 구간(5초) 중앙값을 기준으로 위/아래 허용폭을 2초 이상 벗어나면 무너짐 이벤트. "
               f"허용폭 = AIHub 정상 클립의 클립 내 위/아래 폭 중앙값(위 {tol_up}, 아래 {tol_dn} 몸통길이). 검증: 클립 내 산포 AUC {max(auc_spread, 1-auc_spread):.2f}",
        cautions=["허용폭·2초는 제안값 — 실기기 세트 로그로 확정(DEVICE_VALIDATION). 방향 문구는 CoachCues.directional 과 동일",
                  "AIHub 클립은 성긴 16프레임이라 '2초 지속' 자체는 검증 불가 — 유닛 테스트와 실기기로만"])
    L += ["## 플랭크 → hold", f"- 정상 클립 내 위쪽 폭 중앙값 {up.median():.3f} (p90 {up.quantile(.9):.3f}) · 아래쪽 {dn.median():.3f} (p90 {dn.quantile(.9):.3f}) → 허용폭 위 {tol_up} / 아래 {tol_dn}",
          f"- 클립 내 산포(p90−p10) 정상 {spread[yp==1].median():.3f} vs 위반 {spread[yp==0].median():.3f}, AUC {max(auc_spread, 1-auc_spread):.2f}", ""]

    # ---- 힙쓰러스트: 상단 rep, 고개 p10 ----
    h = df[df.exercise == "힙쓰러스트"]
    yt = h["수축시 무릎부터 어깨까지 일자"].astype(float)
    xm = h["hip_dev_ankle__max"]
    thr_top = round(float(xm[yt == 1].quantile(.1)), 3)
    ok_n, ok_v = float((xm >= thr_top)[yt == 1].mean()), float((xm >= thr_top)[yt == 0].mean())
    old = by_id["floor|힙쓰러스트|수축시 무릎부터 어깨까지 일자"]
    flagged_old = float(((h["hip_dev_ankle__p10"] > old["threshold"]) if old["op"] == ">" else (h["hip_dev_ankle__p10"] < old["threshold"]))[yt == 1].mean())
    by_id[old["id"]] = base_rule(
        old, kind="rep", feature="hip_dev_ankle", base_feature="hip_dev_ankle", stat="rep", op="<", threshold=thr_top,
        rep=dict(feature="hip_dev_ankle", direction="max", threshold=thr_top, max_invalid_frac=REP_MAX_INVALID_FRAC, min_reps=REP_MIN_REPS,
                 invalid_label="덜 올라옴"),
        cv_auc=round(float(max(roc_auc_score((yt == 0).astype(int), xm), 1 - roc_auc_score((yt == 0).astype(int), xm))), 4),
        cv_balacc=None, normal_median=round(float(xm[yt == 1].median()), 6), normal_fpr=round(1 - ok_n, 4), personal_baseline=None,
        reason=f"§34 재정의: v0.2 규칙(hip_dev_ankle__p10 > {old['threshold']})은 채택 뷰 정상 클립의 {flagged_old:.0%} 를 위반으로 판정했다(§28d 수정이 방향을 잘못 잡음). "
               f"렙형 — 렙 상단(cycleMax)이 정상 클립 max 의 p10({thr_top}) 이상이면 유효. 정상 {ok_n:.0%} / 위반('깔짝깔짝') {ok_v:.0%} 유효 = 판별력 {(1-ok_v)/(1-ok_n):.1f}×",
        cautions=["RepCounter.ROM 의 힙쓰러스트 방향도 min→max 로 같이 고침(같은 임계값)", "ROM 미달 렙이 max(2, 렙의 34%) 이상이면 위반"])
    yh = h["고개 들지 않기"].astype(float)
    xp = h["head_trunk_ang__p10"]
    t, f, tp = youden(-xp, (yh == 0).astype(int))
    thr_head = round(-t, 2)
    old = by_id["floor|힙쓰러스트|고개 들지 않기"]
    by_id[old["id"]] = base_rule(
        old, feature="head_trunk_ang__p10", stat="p10", op="<", threshold=thr_head,
        cv_auc=round(float(roc_auc_score((yh == 0).astype(int), -xp)), 4), cv_balacc=round(float((tp + 1 - f) / 2), 4),
        normal_median=round(float(xp[yh == 1].median()), 6), normal_fpr=round(f, 4),
        personal_baseline=dict(old.get("personal_baseline") or {}, threshold_rel=round(thr_head - float(xp[yh == 1].median()), 6)),
        reason=f"§34 재정의: std(흔들림)는 문구('고개 들려 있어요')와 근거가 달랐고 계속 든 고개를 놓친다(감사 C). p10 수준형 — 코-귀-골반 각의 하위 10% 가 {thr_head}° 미만이면 위반(고개 들림 = 각도 작음). AUC {roc_auc_score((yh == 0).astype(int), -xp):.2f}, 오탐률 {f:.2f}, 검출률 {tp:.2f}",
        cautions=[])
    L += ["## 힙쓰러스트", f"- 상단: v0.2 규칙의 정상 위반율 {flagged_old:.0%} → rep 규칙 cycleMax ≥ {thr_top}: 정상 유효 {ok_n:.0%}, 위반 유효 {ok_v:.0%}",
          f"- 고개: p10 < {thr_head}° (AUC {roc_auc_score((yh == 0).astype(int), -xp):.2f}, FPR {f:.2f}, TPR {tp:.2f}); 기존 std 규칙 AUC {old['cv_auc']}", ""]

    # ---- 시저크로스 다리 거리: 정상 p10 ----
    s = df[df.exercise == "시저크로스"]
    ys = s["다리와 지면 사이 적당한 거리"].astype(float)
    xs = s["hip_ang__mean"]
    thr_leg = round(float(xs[ys == 1].quantile(.1)), 2)
    old = by_id["floor|시저크로스|다리와 지면 사이 적당한 거리"]
    fpr_old, tpr_old = float((xs < old["threshold"])[ys == 1].mean()), float((xs < old["threshold"])[ys == 0].mean())
    fpr_new, tpr_new = float((xs < thr_leg)[ys == 1].mean()), float((xs < thr_leg)[ys == 0].mean())
    by_id[old["id"]] = base_rule(
        old, threshold=thr_leg, normal_fpr=round(fpr_new, 4), cv_balacc=round((tpr_new + 1 - fpr_new) / 2, 4),
        personal_baseline=dict(old.get("personal_baseline") or {}, threshold_rel=round(thr_leg - float(old["normal_median"]), 6)),
        reason=(old.get("reason") or "") + f" | §34: Youden 임계 {old['threshold']}(정상 오탐률 {fpr_old:.2f}) → 정상 p10 {thr_leg}(오탐률 {fpr_new:.2f}, 검출률 {tpr_old:.2f}→{tpr_new:.2f}). '다리 너무 높음' 방향만, 낮음은 미규정",
        cautions=list(old.get("cautions") or []) + ["임계값을 정상 분포 p10 으로 — 검출률을 잃고 오탐률을 얻은 선택(beta·재배치 전제)"])
    L += ["## 시저크로스 다리 거리", f"- {old['threshold']} → {thr_leg}: FPR {fpr_old:.2f}→{fpr_new:.2f}, TPR {tpr_old:.2f}→{tpr_new:.2f}", ""]

    # ---- 푸시업/니푸쉬업 깊이: rep ----
    for ex in ("푸시업", "니푸쉬업"):
        d = df[df.exercise == ex]
        y = d["가슴의 충분한 이동"].astype(float)
        direction, thr = ROM[ex]
        x = d["wrist_shoulder_d__min"]
        ok_n, ok_v = float((x <= thr)[y == 1].mean()), float((x <= thr)[y == 0].mean())
        old = by_id[f"floor|{ex}|가슴의 충분한 이동"]
        by_id[old["id"]] = base_rule(
            old, kind="rep", feature="wrist_shoulder_d", base_feature="wrist_shoulder_d", stat="rep", op=">", threshold=thr,
            rep=dict(feature="wrist_shoulder_d", direction=direction, threshold=thr, max_invalid_frac=REP_MAX_INVALID_FRAC, min_reps=REP_MIN_REPS,
                     invalid_label="깊이 미달"),
            normal_median=round(float(x[y == 1].median()), 6), normal_fpr=round(1 - ok_n, 4), personal_baseline=None,
            reason=f"§34 통계량 교체: 세트 min → 렙형. 렙 하단(cycleMin)이 ROM 임계 {thr}(REP_VALIDITY, 정상 렙 90% 통과 분위수, 판별력 검증) 이하면 유효. 채택 뷰 클립 단위 확인: 정상 {ok_n:.0%} / 위반('깔짝') {ok_v:.0%} 유효",
            cautions=[c for c in (old.get("cautions") or []) if "충실도" in c] + ["ROM 미달 렙이 max(2, 렙의 34%) 이상이면 위반. RepCounter 의 ROM 판정과 같은 임계값"])
        L += [f"## {ex} 깊이 → rep", f"- cycleMin ≤ {thr}: 정상 유효 {ok_n:.0%}, 위반 유효 {ok_v:.0%}", ""]

    # ---- 판정 중단 ----
    for rid, why in (("floor|크런치|견갑골이 지면으로부터 충분히 올라옴",
                      "§34 판정 중단: 조건은 견갑골인데 MP 는 어깨-지면 높이를 못 잰다(ρ≈0). 머리 프록시(ρ 0.38)는 '목만 당김' 42건 중 24건을 통과시켜 조건이 약속하는 것을 이행하지 못한다. 렙 ROM(머리 상승)은 횟수 표시로만 남긴다"),
                     ("floor|Y - Exercise|경추 중립 또는 후인(retraction) 유지",
                      "§34 판정 중단: 목을 잰다면서 골반(hip_dev_knee)을 잰다(감사 D, ρ 0.45). 측면 폰 1대로는 Y 레이즈의 규정 3개(팔 높이·엄지·경추) 모두 확정 불가 — 횟수와 팔 들림 근사만")):
        old = by_id[rid]
        by_id[rid] = base_rule(old, status="exclude", reason=why)
    L += ["## 판정 중단 (exclude)", "- 크런치 견갑골, Y 레이즈 경추 — PostureScope 가 '못 봄' 으로 밝힌다", ""]

    # ---- 기록 ----
    new_rules = [by_id[r["id"]] for r in rules]
    # 새 통계량은 사람 분리 교차검증을 하지 않았다. cv 지표로 배포하면 안 된다.
    for r in new_rules:
        changed = (r.get("kind", "window") != "window" or
                   (r["exercise"] == "힙쓰러스트" and "고개" in r["condition"]) or
                   (r["exercise"] == "시저크로스" and "거리" in r["condition"]))
        if changed:
            r["exploratory_clip_auc"] = r.get("cv_auc")
            r["cv_auc"] = None
            r["cv_balacc"] = None
            r["validation_scope"] = "GT 클립 내 탐색 통계. 새 시간/반복 판정 및 실기기 정확도는 미검증."
        if r.get("kind") == "hold":
            r["reason"] = r["reason"].replace("몸통길이", "어깨-발목 선 길이")
    doc["rules"] = new_rules
    doc["version"] = "floor_v0.3"
    doc["generated"] = "2026-09-10"
    doc["source"] = (doc.get("source") or "") + " | v0.3 floor_rules_v03.py: §34 처분표 적용(hold/rep 종류, 힙쓰러스트 상단 고장 수정, 판정 중단 2)"
    doc["kinds"] = {"window": "세트(앵커 이후) 집계 통계 vs 임계값 (종전)", "hold": "프레임 밴드 이탈이 min_break_ms 이상 지속되면 무너짐 이벤트 — 자신의 초반 구간이 기준",
                    "rep": "렙별 사이클 극값 vs 임계값 — 미달 렙이 max(min_reps, 렙×max_invalid_frac) 이상이면 위반"}
    counts = {s: sum(1 for r in new_rules if r["status"] == s) for s in ("ship", "beta", "exclude")}
    doc["counts"] = counts
    RULES.write_text(json.dumps(doc, ensure_ascii=False, indent=1), encoding="utf-8")
    ASSET.write_text(json.dumps(doc, ensure_ascii=False, indent=1), encoding="utf-8")
    L += ["## 결과", f"- {counts} · kind: " + ", ".join(f"{r['exercise']}/{r['condition'][:10]}={r.get('kind','window')}" for r in new_rules if r.get("kind")),
          "- 유지(변경 없음): 시저 시선 · 레그 레이즈 고개 · 푸시업/니푸쉬업 고개 · 손 위치 2",
          "- 미채택: 푸시업 몸통 일직선(채택 뷰 AUC 0.64), 레그 레이즈 무릎각(측면 투영 붕괴), 다리 하단 높이(AUC 0.61), 시저 무릎각(0.59)"]
    L += ["\n※ 위 수치는 GT 클립 내 탐색 결과다. 새 반복/시간 엔진의 교차검증·실기기 정확도가 아니다. 초기 안정 자세도 올바른 자세로 인증하지 않는다."]
    (HERE / "FLOOR_RULES_V03.md").write_text("\n".join(L), encoding="utf-8")
    print("\n".join(L))


if __name__ == "__main__":
    main()
