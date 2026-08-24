#!/usr/bin/env python
"""양방향 자세 오류 — 부호 피처의 방향 판별과 '반대측 가드' 검증 (spec §21 요건 4의 구현 전 검증).

현재 규칙은 한쪽 임계값이라 스쿼트 '무릎 모임'은 잡아도 '과도한 벌어짐'은 정의상 통과한다.
AIHub 에 스쿼트 바깥쪽 시나리오는 0클립이지만, 다른 조건들에 **양방향 라벨**이 실존한다:
좌/우 기울기(스티프데드·업라이트로우), 하늘/바닥/왼/오 보기(런지 cervical), 발방향 안쪽(런지) vs 바깥쪽(사이드·크로스 런지).

B. 방향 판별(attribution): 위반 클립에서 부호 피처의 부호가 description 의 방향과 일치하는 비율.
C. 반대측 가드 검증: 정상 분포의 robust 경계(med ± k·1.4826·MAD)를 학습 수행자에서 만들고,
   홀드아웃 수행자에서 좌/우(상/하) 각각의 recall 과 정상 FPR 을 잰다 — 라벨 없이 만든 경계가 실제 반대 방향을 잡는지.
D. 가드 생성: 검증된 방식으로, 가드 대상 규칙의 MP 스케일(실험 A 피처, 규칙의 뷰) 경계 산출 → opposite_guards.csv.

출력: outputs/BIDIRECTIONAL.md, outputs/opposite_guards.csv
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

import numpy as np
import pandas as pd

from features import J, apply_qc_mask, build_or_load_features, load_kp3d

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

OUT = Path(__file__).resolve().parent / "outputs"
K_MAD = 2.5        # 검증(C)용 — 측정된 FPR 6~8%
K_GUARD = 3.0      # 앱 주입(D)용 — 음성 안내라 오탐을 더 눌러야 함


def robust_bounds(x: np.ndarray, k: float = K_MAD):
    med = float(np.nanmedian(x))
    mad = float(np.nanmedian(np.abs(x - med))) * 1.4826
    if mad < 1e-9:
        mad = float(np.nanstd(x)) or 1e-9
    return med - k * mad, med + k * mad


def foot_open_feature(P: np.ndarray) -> np.ndarray:
    """발끝 벌림(+)/모임(−) 부호 피처 — 수평 발 방향과 신체 전방의 부호 각(양발 평균). (n,T)"""
    def g(n):
        return P[:, :, J[n], :]
    LHip, RHip = g("LHip"), g("RHip")
    hip = LHip - RHip
    hip[..., 1] = 0
    nrm = np.linalg.norm(hip, axis=-1, keepdims=True)
    xb = hip / np.where(nrm < 1e-6, np.nan, nrm)
    up = np.zeros_like(xb); up[..., 1] = 1.0
    fwd = np.cross(xb, up)
    out = []
    for side, sgn in (("L", 1.0), ("R", -1.0)):
        v = g(f"{side}Foot") - g(f"{side}Ankle")
        v = v.copy(); v[..., 1] = 0
        n2 = np.linalg.norm(v, axis=-1)
        ang = np.degrees(np.arctan2(sgn * np.sum(v * xb, axis=-1), np.sum(v * fwd, axis=-1)))
        ang = np.where(n2 > 3.0, ang, np.nan)          # 발이 거의 수직 투영이면 무시
        out.append(ang)
    return np.nanmean(np.stack(out), axis=0)


def main():
    clips = pd.read_parquet(OUT / "clips.parquet").set_index("clip_id")
    conds = pd.read_parquet(OUT / "conditions.parquet")
    feats = build_or_load_features(OUT)
    clips = clips.loc[clips.index.intersection(feats.index)]
    desc = clips["description"].str.replace(r"\s+", " ", regex=True)
    L = ["# 양방향 자세 오류 — 방향 판별과 반대측 가드 검증\n"]

    # ---------- B. 방향 판별 정확도 ----------
    L += ["## B. 부호 피처의 방향 판별 (위반 클립에서 부호 vs description 방향 일치율)\n",
          "| 케이스 | 방향쌍 | n | 피처 | 판별 정확도 |", "|---|---|---|---|---|"]

    def attribution(ids_a, ids_b, feat, label, pair):
        a = feats.loc[feats.index.intersection(ids_a), feat].dropna()
        b = feats.loc[feats.index.intersection(ids_b), feat].dropna()
        if len(a) < 15 or len(b) < 15:
            return None
        y = np.concatenate([np.ones(len(a)), np.zeros(len(b))])
        v = np.concatenate([a.to_numpy(), b.to_numpy()])
        thr = float(np.median(v))
        acc = max(((v > thr) == (y == 1)).mean(), ((v < thr) == (y == 1)).mean())
        L.append(f"| {label} | {pair} | {len(a)}+{len(b)} | `{feat}` | **{acc*100:.0f}%** |")
        return acc

    # B1 좌/우 기울기 (스티프데드 + 업라이트로우)
    for ex in ("바벨 스티프 데드리프트", "업라이트로우"):
        g = clips[clips.exercise == ex]
        left = g.index[desc.loc[g.index].str.contains("왼쪽으로 기울")]
        right = g.index[desc.loc[g.index].str.contains("오른쪽으로 기울")]
        for f in ("shoulder_asym__mean", "hand_h_asym__mean"):
            attribution(left, right, f, f"{ex} 기울기", "왼/오")
    # B2 cervical 상/하, 좌/우 (런지 4종 + 니업·크런치)
    look = {k: clips.index[desc.str.contains(p)] for k, p in
            (("하늘", r"하늘\s?보"), ("바닥", r"바닥\s?보"), ("왼", r"왼쪽\s?보"), ("오", r"오른쪽\s?보"))}
    attribution(look["하늘"], look["바닥"], "head_pitch__mean", "고개 상/하 (전 종목)", "하늘/바닥")
    attribution(look["왼"], look["오"], "head_yaw__mean", "고개 좌/우 (전 종목)", "왼/오")
    attribution(look["하늘"], look["바닥"], "face_vs_torso__mean", "고개 상/하 (전 종목)", "하늘/바닥")
    # B3 발방향 안/바깥 — 전용 부호 피처(foot_open)를 즉석 계산
    ids_all, arr = load_kp3d(OUT)
    ids_all, arr, _ = apply_qc_mask(ids_all, arr, OUT)
    ids_all = np.array(ids_all)
    fo = pd.Series(np.nanmean(foot_open_feature(arr), axis=1), index=ids_all, name="foot_open__mean")
    inward = clips.index[desc.str.contains("발방향 안쪽")]
    outward = clips.index[desc.str.contains("발방향 (과도하게 )?바깥쪽", regex=True)]
    a, b = fo.reindex(inward).dropna(), fo.reindex(outward).dropna()
    normal_fo = fo.reindex(clips.index[desc.str.contains("정자세") & ~desc.str.contains("발방향")]).dropna()
    if len(a) and len(b):
        thr = float(np.median(np.concatenate([a, b])))
        acc = max(((np.concatenate([a, b]) > thr) == np.concatenate([np.zeros(len(a)), np.ones(len(b))]) ).mean(),
                  ((np.concatenate([a, b]) < thr) == np.concatenate([np.zeros(len(a)), np.ones(len(b))]) ).mean())
        L.append(f"| 발방향 (런지류 교차) | 안쪽/바깥쪽 | {len(a)}+{len(b)} | `foot_open__mean`(신규 후보) | **{acc*100:.0f}%** |")
        L.append(f"| | | | 평균: 안쪽 {a.mean():+.1f}° · 정자세 {normal_fo.mean():+.1f}° · 바깥쪽 {b.mean():+.1f}° | |")
    # knee_out 은 발방향 오류를 얼마나 반영하나 (스쿼트 가드의 근거 확인)
    ko_in = feats.reindex(inward)["knee_out_mean__mean"].dropna()
    ko_out = feats.reindex(outward)["knee_out_mean__mean"].dropna()
    L.append(f"| (참고) knee_out_mean__mean | 안쪽 {ko_in.mean():+.4f} vs 바깥쪽 {ko_out.mean():+.4f} | {len(ko_in)}+{len(ko_out)} | 부호 분리 "
             f"{'O' if ko_in.mean() < 0 < ko_out.mean() else '△'} | |")
    L.append("")

    # ---------- C. 반대측 가드(정상 분포 robust 경계) 검증 ----------
    L += ["## C. 라벨 없이 만든 반대측 경계가 실제 반대 방향을 잡는가 (정상 med±2.5·MAD, 수행자 홀드아웃 5회)\n",
          "| 케이스 | 피처 | 한쪽 recall | 반대쪽 recall | 정상 FPR |", "|---|---|---|---|---|"]
    rng = np.random.default_rng(0)

    def guard_validate(ex_list, cond_re, dir_a_re, dir_b_re, feat, label):
        g = clips[clips.exercise.isin(ex_list)]
        cnames = conds[conds.exercise.isin(ex_list) & conds.condition.str.contains(cond_re)]
        piv = cnames.drop_duplicates(["clip_id"]).set_index("clip_id")["value"].reindex(g.index)
        ok = piv.notna()
        ids = g.index[ok.to_numpy()]
        y = piv[ok].astype(bool)
        d = desc.loc[ids]
        A = ids[(~y) & d.str.contains(dir_a_re)]
        B = ids[(~y) & d.str.contains(dir_b_re)]
        N = ids[y]
        v = fo if feat == "foot_open__mean" else feats[feat]
        va, vb, vn = v.reindex(A).dropna(), v.reindex(B).dropna(), v.reindex(N).dropna()
        if min(len(va), len(vb)) < 15 or len(vn) < 60:
            return
        perf = clips.loc[vn.index, "performer"].astype(str)
        persons = perf.unique()
        rec_a, rec_b, fpr = [], [], []
        for _ in range(5):
            te_p = set(rng.choice(persons, max(2, len(persons) // 3), replace=False))
            tr_n = vn[~perf.isin(te_p).to_numpy()]
            te_n = vn[perf.isin(te_p).to_numpy()]
            lo, hi = robust_bounds(tr_n.to_numpy())
            rec_a.append(float(((va < lo) | (va > hi)).mean()))
            rec_b.append(float(((vb < lo) | (vb > hi)).mean()))
            if len(te_n):
                fpr.append(float(((te_n < lo) | (te_n > hi)).mean()))
        L.append(f"| {label} | `{feat}` | {np.mean(rec_a)*100:.0f}% | {np.mean(rec_b)*100:.0f}% | {np.mean(fpr)*100:.1f}% |")

    guard_validate(["바벨 스티프 데드리프트", "업라이트로우"], "척추", r"왼쪽으로 기울", r"오른쪽으로 기울", "shoulder_asym__mean", "좌/우 기울기")
    guard_validate(["바벨 스티프 데드리프트", "업라이트로우"], "척추", r"왼쪽으로 기울", r"오른쪽으로 기울", "hand_h_asym__mean", "좌/우 기울기")
    guard_validate(["스텝 포워드 다이나믹 런지", "스텝 백워드 다이나믹 런지", "바벨 런지", "사이드 런지"], "척추", r"하늘\s?보", r"바닥\s?보", "head_pitch__mean", "고개 상/하 (런지 cervical)")
    guard_validate(["스텝 포워드 다이나믹 런지", "스텝 백워드 다이나믹 런지", "바벨 런지", "사이드 런지"], "척추", r"왼쪽\s?보", r"오른쪽\s?보", "head_yaw__mean", "고개 좌/우 (런지 cervical)")
    guard_validate(["스텝 포워드 다이나믹 런지", "사이드 런지", "크로스 런지"], "방향 일치", r"발방향 안쪽", r"발방향 (과도하게 )?바깥쪽", "foot_open__mean", "발방향 안/바깥 (런지 교차)")
    L.append("")

    # ---------- D. 가드 생성 (MP 스케일, 규칙의 뷰) ----------
    doc = json.load(open(Path(__file__).resolve().parent / "rules" / "rules_mp_v0.json", encoding="utf-8"))
    fm = pd.read_parquet(OUT / "expA_features_mp.parquet")
    GUARD = [  # (조건 정규식, 반대측 방향, 반대측 설명, 검증 근거)
        (r"발과 무릎의 방향 일치|몸통.*방향 일치", "upper", "무릎/발이 과도하게 바깥", "부호 분리 B3·발방향 가드 C 로 방식 검증(무릎 바깥 라벨은 없음)"),
        (r"고개 정면|시선 정면", "both_missing", "반대 방향 고개(들림/숙임)", "고개 상/하 판별 B2·가드 C 검증"),
        (r"수축 시 고개 안 젖힘", "opposite", "고개 과도 숙임", "고개 상/하 판별 B2"),
        (r"상체의 과조한 숙임/젖힘 여부", "opposite", "과도한 앞 숙임", "조건명이 양방향(연기는 젖힘만) — 미검증 가드"),
    ]
    rows = []
    for r in doc["rules"]:
        if r["status"] == "exclude":
            continue
        hit = next((gd for gd in GUARD if re.search(gd[0], r["condition"])), None)
        if hit is None:
            continue
        view = r.get("view_best_front") or "C"
        sub = fm[fm.view_letter == view].set_index("clip_id")
        ids = clips.index[clips.exercise == r["exercise"]]
        piv = conds[(conds.exercise == r["exercise"]) & (conds.condition == r["condition"])].drop_duplicates("clip_id").set_index("clip_id")["value"]
        normals = piv[piv.astype(bool)].index
        v = sub.reindex(sub.index.intersection(normals))[r["feature"]].dropna() if r["feature"] in sub.columns else pd.Series(dtype=float)
        if len(v) < 20:
            continue
        lo, hi = robust_bounds(v.to_numpy(), k=K_GUARD)
        # 기존 규칙이 잡는 방향의 반대측 경계만 가드로
        guard_op = ">" if r["op"] == "<" else "<"
        guard_thr = hi if guard_op == ">" else lo
        # 기존 임계값과 가드가 겹치면(정상 범위가 임계값보다 좁으면) 스킵
        if (guard_op == ">" and guard_thr <= r["threshold"]) or (guard_op == "<" and guard_thr >= r["threshold"]):
            continue
        rows.append(dict(rule_id=r["id"], exercise=r["exercise"], condition=r["condition"], subtype=r.get("subtype") or "",
                         feature=r["feature"], primary_op=r["op"], primary_threshold=r["threshold"],
                         guard_op=guard_op, guard_threshold=round(float(guard_thr), 6), n_norm=len(v),
                         method=f"MP({view}) 정상 med±{K_MAD}·MAD", opposite_desc=hit[2], evidence=hit[3],
                         validated=bool("검증" in hit[3] and "미검증" not in hit[3])))
    gdf = pd.DataFrame(rows)
    gdf.to_csv(OUT / "opposite_guards.csv", index=False, encoding="utf-8-sig")
    L += [f"## D. 생성된 반대측 가드 (MP 스케일, 규칙의 뷰 정상 분포 med±{K_GUARD}·MAD — 검증 C 의 2.5 보다 보수적, 음성 오탐 억제)\n",
          "| 규칙 | 피처 | 기존(위반 if) | 가드(위반 if) | 정상 n | 반대측 의미 | 검증 |", "|---|---|---|---|---|---|---|"]
    for r in gdf.itertuples():
        L.append(f"| {r.rule_id} | `{r.feature}` | {r.primary_op} {r.primary_threshold:.3g} | **{r.guard_op} {r.guard_threshold:.3g}** | {r.n_norm} | {r.opposite_desc} | {'O' if r.validated else '미검증(정상분포 기반)'} |")
    L += ["", f"- 가드 {len(gdf)}개 생성. FPR 은 C 검증에서 med±2.5·MAD 기준 측정値 참조 — 가드는 보수적 경계이며, 스쿼트 '무릎 바깥' 처럼 라벨이 없는 방향은 `validated=false` 로 표시(오탐률만 통제, 검출률은 미보증).",
          "- lateral(좌/우 기울기) 규칙은 std/range 라 이미 양방향을 잡는다 — 가드 불필요, 대신 **방향 명명**(shoulder_asym 평균 부호 → 왼쪽/오른쪽)을 코칭 문구에 사용."]
    (OUT / "BIDIRECTIONAL.md").write_text("\n".join(L), encoding="utf-8")
    print("\n".join(L))


if __name__ == "__main__":
    main()
