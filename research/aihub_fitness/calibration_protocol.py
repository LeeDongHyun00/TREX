#!/usr/bin/env python
"""재보정·개인화 촬영 프로토콜 산출 — "어떤 운동을 몇 세트" 를 데이터에서 결정.

실험 5 표본 크기 곡선: 라벨 세트 n 개로 임계값(Youden)을 적합했을 때, 홀드아웃 수행자에서의 균형정확도.
                      n 을 늘리며 전체 데이터 임계값(상한) 대비 몇 % 를 회복하는지 → 재보정에 필요한 최소/권장 세트 수.
실험 6 기준선 k 곡선 : 개인 기준선을 '정상 앞 k 세트 중앙값'으로 잡을 때 k=1,2,3,5,8 의 성능 → 기준선 세트 수.
                      (임계값은 다른 수행자에게서 학습, 기준선 세트는 평가에서 제외)
그리고 종목별 조건 수 × 필요한 클래스당 표본 → 촬영 프로토콜 표(정자세/오류 세트 수, 예상 소요시간) 생성.

출력: outputs/calibration_protocol.csv, outputs/CALIBRATION_PROTOCOL.md
"""
from __future__ import annotations

import json
import sys
import warnings
from collections import defaultdict
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.metrics import balanced_accuracy_score, roc_curve

from features import build_or_load_features

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
warnings.filterwarnings("ignore")

OUT = Path(__file__).resolve().parent / "outputs"
RNG = np.random.default_rng(0)
N_GRID = [8, 12, 16, 20, 30, 40, 60, 80, 120, 200]
K_GRID = [1, 2, 3, 5, 8]
REPEATS = 40
TEST_FRAC = 0.3
SEC_PER_SET = 45          # 기록 15~20초 + 리셋·라벨 메모


def youden(score, y):
    fpr, tpr, thr = roc_curve(y, score)
    t = thr[int(np.argmax(tpr - fpr))]
    return float(t) if np.isfinite(t) else float(np.median(score))


def bacc(score, y, t):
    return balanced_accuracy_score(y, (score >= t).astype(int))


def sample_curve(s, y, grp):
    """n 개 라벨 세트로 임계값 적합 → 홀드아웃 수행자 균형정확도. 전체 데이터 임계값이 상한."""
    persons = np.unique(grp)
    if len(persons) < 6:
        return None
    res = defaultdict(list)
    ceil = []
    for _ in range(REPEATS):
        te_p = RNG.choice(persons, max(2, int(len(persons) * TEST_FRAC)), replace=False)
        te = np.isin(grp, te_p)
        tr = ~te
        if len(np.unique(y[te])) < 2 or len(np.unique(y[tr])) < 2:
            continue
        ceil.append(bacc(s[te], y[te], youden(s[tr], y[tr])))     # 학습 전체 사용 = 상한
        tr_idx = np.flatnonzero(tr)
        for n in N_GRID:
            if n > len(tr_idx):
                continue
            pick = RNG.choice(tr_idx, n, replace=False)
            if len(np.unique(y[pick])) < 2:
                continue
            res[n].append(bacc(s[te], y[te], youden(s[pick], y[pick])))
    if not ceil or not res:
        return None
    out = dict(ceiling=float(np.mean(ceil)), n_train_avail=int((~np.isin(grp, [])).sum()))
    for n, v in res.items():
        out[f"n{n}"] = float(np.mean(v))
        out[f"n{n}_sd"] = float(np.std(v))
    return out


def baseline_curve(s, y, grp, order_key):
    """기준선 k 세트(정상 앞 k) 중앙값 차감. 임계값은 다른 수행자에서, 기준선 세트는 평가 제외."""
    persons = np.unique(grp)
    if len(persons) < 6:
        return None
    out = {}
    for k in K_GRID:
        adj = np.full(len(s), np.nan)
        ev = np.zeros(len(s), bool)
        for p in persons:
            idx = np.flatnonzero(grp == p)
            idx = idx[np.argsort(order_key[idx])]
            pos = [i for i in idx if y[i] == 1]
            if len(pos) < k + 1:
                continue
            b = float(np.median(s[pos[:k]]))
            for i in idx:
                if i not in pos[:k]:
                    adj[i] = s[i] - b
                    ev[i] = True
        if ev.sum() < 40 or len(np.unique(y[ev])) < 2:
            continue
        accs = []
        for _ in range(REPEATS // 2):
            te_p = RNG.choice(persons, max(2, int(len(persons) * TEST_FRAC)), replace=False)
            te = ev & np.isin(grp, te_p)
            tr = ev & ~np.isin(grp, te_p)
            if te.sum() < 10 or tr.sum() < 20 or len(np.unique(y[te])) < 2 or len(np.unique(y[tr])) < 2:
                continue
            accs.append(bacc(adj[te], y[te], youden(adj[tr], y[tr])))
        if accs:
            out[f"k{k}"] = float(np.mean(accs))
    # k=0(기준선 없음) 대조: 같은 평가 집합이 아니라 전체에서
    accs0 = []
    for _ in range(REPEATS // 2):
        te_p = RNG.choice(persons, max(2, int(len(persons) * TEST_FRAC)), replace=False)
        te = np.isin(grp, te_p)
        tr = ~te
        if len(np.unique(y[te])) < 2 or len(np.unique(y[tr])) < 2:
            continue
        accs0.append(bacc(s[te], y[te], youden(s[tr], y[tr])))
    if accs0:
        out["k0"] = float(np.mean(accs0))
    return out or None


def main():
    clips = pd.read_parquet(OUT / "clips.parquet").set_index("clip_id")
    conds = pd.read_parquet(OUT / "conditions.parquet")
    feats = build_or_load_features(OUT)
    doc = json.load(open(Path(__file__).resolve().parent / "rules" / "rules_mp_v0.json", encoding="utf-8"))
    rules = [r for r in doc["rules"] if r["status"] != "exclude"]
    # subtype 중복 제거 (같은 종목·조건은 한 번만)
    seen, uniq = set(), []
    for r in rules:
        key = (r["exercise"], r["condition"])
        if key in seen:
            continue
        seen.add(key)
        uniq.append(r)
    clips = clips.loc[clips.index.intersection(feats.index)]
    clips["group"] = np.where(clips["performer"].astype(str) != "", clips["performer"].astype(str), clips["day"].astype(str))
    clips["order_key"] = clips["day"].astype(str) + "-" + clips.index.str.rsplit("-", n=1).str[-1].str.zfill(4)
    print(f"[load] 활성 규칙 {len(rules)} → 종목×조건 {len(uniq)}개", flush=True)

    rows = []
    for r in uniq:
        ex, cond = r["exercise"], r["condition"]
        g = clips[clips.exercise == ex]
        sub = conds[(conds.clip_id.isin(g.index)) & (conds.condition == cond)]
        if sub.empty:
            continue
        yv = sub.drop_duplicates("clip_id").set_index("clip_id")["value"].reindex(g.index)
        mask = yv.notna().to_numpy()
        ids = g.index[mask]
        y = yv[mask].to_numpy().astype(int)
        col = f"{r['base_feature']}__{r['stat']}"
        if col not in feats.columns:
            continue
        v = feats.loc[ids, col].to_numpy(dtype=np.float64)
        ok = np.isfinite(v)
        ids, y, v = ids[ok], y[ok], v[ok]
        if len(y) < 60 or min(int(y.sum()), len(y) - int(y.sum())) < 20:
            continue
        grp = g.loc[ids, "group"].to_numpy()
        okey = g.loc[ids, "order_key"].to_numpy()
        s = (1.0 if r["op"] == "<" else -1.0) * v
        row = dict(exercise=ex, condition=cond, status=r["status"], feature=col, n=len(y), n_persons=len(np.unique(grp)),
                   pb_eligible=bool((r.get("personal_baseline") or {}).get("eligible")))
        sc = sample_curve(s, y, grp)
        if sc:
            row.update(sc)
        bc = baseline_curve(s, y, grp, okey)
        if bc:
            row.update({f"bl_{k}": val for k, val in bc.items()})
        rows.append(row)
        print(f"  {ex} | {cond}", flush=True)
    res = pd.DataFrame(rows)
    res.to_csv(OUT / "calibration_protocol.csv", index=False, encoding="utf-8-sig")
    write_protocol(res, uniq)


def write_protocol(res: pd.DataFrame, uniq: list):
    have = res.dropna(subset=["ceiling"])
    # 표본 크기 곡선: 상한 대비 회복률
    curve = []
    for n in N_GRID:
        c = f"n{n}"
        if c not in have:
            continue
        d = have.dropna(subset=[c])
        if len(d) < 5:
            continue
        curve.append(dict(n=n, bacc=d[c].median(), recover=(d[c] / d["ceiling"]).median(), gap=(d["ceiling"] - d[c]).median(),
                          sd=d[f"{c}_sd"].median(), n_rules=len(d)))
    cv = pd.DataFrame(curve)
    # 권장 n: 상한의 98% 를 처음 넘는 지점 / 최소 n: 95% / 실용 n: 다음 구간 이득이 +0.005 미만이 되는 첫 지점
    def first_n(th):
        s = cv[cv.recover >= th]
        return int(s.n.iloc[0]) if len(s) else None
    n_min, n_rec = first_n(0.95), first_n(0.98)
    n_prac = None
    for i in range(len(cv) - 1):
        if cv.bacc.iloc[i + 1] - cv.bacc.iloc[i] < 0.005 and cv.recover.iloc[i] >= 0.97:
            n_prac = int(cv.n.iloc[i])
            break
    n_prac = n_prac or n_rec or 30
    # 기준선 곡선
    bl_cols = [f"bl_k{k}" for k in K_GRID if f"bl_k{k}" in have]
    el = have[have.pb_eligible]
    L = ["# 재보정·개인화 촬영 프로토콜 (데이터 산출)\n",
         f"- 근거: AIHub GT 3D 피처 + 활성 규칙 {len(uniq)}개(종목×조건) 중 표본 충분 {len(have)}개, 수행자 홀드아웃 {REPEATS}회 반복",
         f"- 세트 1개 = 3~4렙 기록(≥8프레임). 소요 추정 {SEC_PER_SET}초/세트(기록+리셋+라벨 메모)\n",
         "## 1. 임계값 재보정에 필요한 라벨 세트 수 (실험 5)\n",
         "| 라벨 세트 n | 균형정확도 중앙값 | 전체 데이터 대비 회복률 | 상한과의 격차 | 반복 간 SD |", "|---|---|---|---|---|"]
    for r in cv.itertuples():
        L.append(f"| {r.n} | {r.bacc:.3f} | {r.recover*100:.1f}% | {r.gap:+.3f} | {r.sd:.3f} |")
    L += ["",
          f"- **최소 {n_min}세트**(상한의 95% 회복) · **실용 {n_prac}세트**({cv[cv.n==n_prac].recover.iloc[0]*100:.1f}%, 다음 구간 이득 +0.005 미만) · **권장 {n_rec}세트**(98%). 그 이상은 수익 체감.",
          f"- **n = 그 종목의 총 세트 수**다(한 세트가 그 종목의 모든 조건에 동시에 라벨을 주므로). 조건 수가 몇 개든 총 세트 수는 같다.",
          "- 클래스 균형이 핵심: 각 조건에서 정상/위반이 대략 반반이어야 한다(한쪽이 20% 미만이면 임계값이 치우친다).",
          f"- 12세트→{n_prac}세트의 이득은 균형정확도 {cv[cv.n==12].bacc.iloc[0]:.3f}→{cv[cv.n==n_prac].bacc.iloc[0]:.3f}({cv[cv.n==n_prac].bacc.iloc[0]-cv[cv.n==12].bacc.iloc[0]:+.3f}), "
          f"반복 간 SD 는 {cv[cv.n==12].sd.iloc[0]:.3f}→{cv[cv.n==n_prac].sd.iloc[0]:.3f} 로 줄어든다 — 세트를 늘리는 주된 효과는 평균 향상보다 **재보정 결과의 안정성**이다.\n",
          "## 2. 개인 기준선 세트 수 (실험 6, `personal_baseline.eligible` 규칙)\n",
          "| 기준선 세트 k | 균형정확도 중앙값(eligible 규칙) | 전체 규칙 |", "|---|---|---|"]
    for k in [0] + K_GRID:
        c = f"bl_k{k}"
        if c not in have:
            continue
        L.append(f"| {'없음' if k == 0 else k} | {el[c].median():.3f} | {have[c].median():.3f} |")
    best_k = 3
    if bl_cols:
        vals = {k: el[f"bl_k{k}"].median() for k in K_GRID if f"bl_k{k}" in el}
        mx = max(vals.values())
        best_k = min(k for k, v in vals.items() if v >= mx - 0.01)      # 최고값과 0.01 이내면 가장 작은 k
        L.append(f"\n- eligible 규칙(9개)에서 k=1 만으로 이미 {vals[1]-el['bl_k0'].median():+.3f}, **k={best_k} 에서 포화**(최고값과 0.01 이내인 최소 k). "
                 f"k 별 차이({min(vals.values()):.3f}~{mx:.3f})는 규칙 9개 기준이라 순위가 흔들린다 — k=3~5 사이면 실질 차이 없음.")
        L.append(f"- 기준선을 전 규칙에 적용하면 이득이 사라진다(전체 열: 없음 {have['bl_k0'].median():.3f} vs k={best_k} {have[f'bl_k{best_k}'].median():.3f}) — **eligible 규칙에만** 적용할 것.")
    # 종목별 프로토콜
    by_ex = defaultdict(list)
    for r in uniq:
        by_ex[r["exercise"]].append(r)
    have_key = {(r.exercise, r.condition) for r in have.itertuples()}
    clips_all = pd.read_parquet(OUT / "clips.parquet")
    cat_of = clips_all.groupby("exercise")["category"].agg(lambda s: s.mode().iat[0]).to_dict()
    # 세션당 가능한 세트 수 (운동 부하 고려): 세트당 3~4렙이므로 총 렙 수가 제약
    SETS_PER_SESSION = {"바벨/덤벨": 20, "기구": 25, "맨몸 운동": 40}
    L += ["", "## 3. 종목별 촬영 프로토콜\n",
          "설계: **각 세트마다 조건별로 '정상/위반'을 무작위 절반씩** 배정해 수행한다(여러 조건을 한 세트에서 동시에 틀려도 된다 — AIHub 완전요인설계와 같은 방식).",
          "조건이 몇 개든 각 조건이 독립적으로 절반씩 위반되므로, **총 세트 수는 조건 수와 무관하게 같다**. 한 번에 한 조건만 틀리는 설계는 조건 수만큼 세트가 곱해져 비효율적이다.",
          f"세트당 3~4렙 기준이라 {n_prac}세트 = 90~120렙 — 중량 종목은 **여러 세션에 나눠** 찍어야 한다(피로로 자세가 무너지면 라벨이 오염된다).\n",
          "| 종목 | 활성 규칙 | 조건 | 실용 세트 | 최소 | 세션 분할 | 기준선 eligible | 권장 뷰 | 비고 |",
          "|---|---|---|---|---|---|---|---|---|"]
    tot_prac = 0
    for ex, rs in sorted(by_ex.items(), key=lambda kv: (-sum(1 for r in kv[1] if r["status"] == "ship"), kv[0])):
        conds_n = len({r["condition"] for r in rs})
        covered = sum(1 for r in rs if (r["exercise"], r["condition"]) in have_key)
        el_n = sum(1 for r in rs if (r.get("personal_baseline") or {}).get("eligible"))
        cat = cat_of.get(ex, "맨몸 운동")
        per_sess = SETS_PER_SESSION.get(cat, 25)
        sess = int(np.ceil(n_prac / per_sess))
        per = int(np.ceil(n_prac / sess))
        views = ",".join(sorted({r["view_best_front"] for r in rs}))
        note = []
        if el_n:
            note.append(f"+기준선 {best_k}세트(정자세)")
        if covered < len(rs):
            note.append("일부 조건 표본 부족")
        tot_prac += n_prac
        L.append(f"| {ex} | {len(rs)} | {conds_n} | **{n_prac}** | {n_min} | {sess}회 × {per}세트 | {el_n} | {views} | {', '.join(note)} |")
    L += ["", f"- 28종목 전부: {tot_prac}세트 — **비현실적**. 아래 단계별 권장을 따를 것.\n",
          "## 4. 단계별 권장 (현실적 순서)\n",
          "| 단계 | 대상 | 총 세트 | 촬영 시간 | 세션 | 얻는 것 |", "|---|---|---|---|---|---|"]
    tier0 = ["바벨 스쿼트"]
    tier1 = ["바벨 스쿼트", "오버 헤드 프레스", "바벨 데드리프트"]
    tier2 = tier1 + ["바벨 컬", "덤벨 컬", "딥스", "스탠딩 사이드 크런치", "바벨 런지"]
    def block(exs, n_each):
        n = len(exs) * n_each
        sess = sum(int(np.ceil(n_each / SETS_PER_SESSION.get(cat_of.get(e, "맨몸 운동"), 25))) for e in exs)
        return n, n * SEC_PER_SET // 60, sess
    for label, exs, n_each, why in (
        ("0. 파이프라인 점검", tier0, n_min, "본인 1명. 로그·집계·재보정 도구가 도는지 확인 (정확도 주장 불가)"),
        ("1. 최소 유효", tier1, n_prac, "핵심 3종목 임계값 재보정 — ship 규칙 11개"),
        ("2. 실사용", tier2, n_prac, "상위 8종목 — ship 규칙 다수 + 기준선 eligible 종목 포함"),
    ):
        n, mins, sess = block(exs, n_each)
        L.append(f"| {label} | {', '.join(exs)} | {n} | {mins//60}시간 {mins%60}분 | {sess}회 | {why} |")
    L += ["",
          "**인원이 세트보다 중요하다**: 임계값 재보정은 **여러 사람**이어야 의미가 있다(수행자 1명이면 GroupKFold 불가 → 리포트에 누수 경고, 그 임계값은 그 사람 전용).",
          f"실험 1·2 결과상 개인 임계값의 정직한 이득은 +0.002 이므로, 같은 총량이면 **1명 × {n_prac*3}세트보다 6명 × {n_prac//2}세트**가 낫다. 최소 3명, 권장 6명 이상.",
          "",
          f"**기준선(개인화)**: 아래 6종목만 해당. 사용자가 앱에서 처음 쓸 때 **정자세로 {best_k}세트**를 찍으면 된다(오류 세트 불필요, 라벨 불필요, 30초×{best_k}).",
          "나머지 22종목은 기준선을 받아도 이득이 없으므로 요구하지 말 것.",
          "",
          "| 종목 | eligible 규칙 | 피처 | GT 이득 |", "|---|---|---|---|"]
    for r in uniq:
        pb = r.get("personal_baseline") or {}
        if pb.get("eligible"):
            L.append(f"| {r['exercise']} | {r['condition']} | {r['feature']} | {pb['gain']:+.3f} |")
    L += ["", "## 5. 라벨 기록 방법\n",
          "세트마다 어떤 조건을 의도적으로 위반했는지 적어 두고, 나중에 `labels.csv` 로 옮긴다. 앱의 `set_id` 는 세트 종료 시 로그에 찍히므로 순서만 맞으면 된다.",
          "```csv", "set_id,condition,value,subtype,subject_id",
          "20260822T101530-a1b2c3d4,척추의 중립,0,flexion,user01",
          "20260822T101530-a1b2c3d4,발과 무릎의 방향 일치,1,,user01", "```",
          "- `value` 1=정상(조건 충족), 0=위반. **그 세트의 모든 조건에 대해 한 줄씩** 적는다(위반하지 않은 조건도 1로 기록해야 클래스 균형이 생긴다).",
          "- 척추는 위반 시 `subtype` 필수: flexion(말림)/lateral(측굴)/extension(과신전)/lumbar_swing(반동)/forward_lean(앞 숙임).",
          "- `subject_id` 는 사람마다 고유하게 — 재보정의 GroupKFold 그룹이다.",
          f"- 촬영은 권장 뷰(정면 C 또는 전방 사선 B/D)에서, 전신이 프레임에 들어오게, 폰 수직 거치. 뷰가 섞이면 재보정이 흐려진다."]
    (OUT / "CALIBRATION_PROTOCOL.md").write_text("\n".join(L), encoding="utf-8")
    print("\n".join(L[:20]))


if __name__ == "__main__":
    main()
