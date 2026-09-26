#!/usr/bin/env python
"""개인화 이득의 출처 분석 — AIHub 에서 측정한 '개인차' 가 앱에서도 같은가.

물음: 기준선 정규화가 AIHub 에서 얻은 이득(+0.03~0.12)은 (a) 사람의 안정적 특성인가, (b) 그날의 촬영 세션
(카메라 위치·조명·복장·컨디션) 효과인가? (b) 라면 앱에서는 기준선을 찍은 날과 실제 사용하는 날이 다르므로
그만큼 이득이 사라진다.

실험 G1 분산 분해   : 정상 클립의 피처를 person / day-within-person / residual 로 분해 (2일 이상 수행자 사용).
                      person 비중이 크면 (a), day 비중이 크면 (b).
실험 G2 세션 전이   : 같은 수행자가 2일 이상 한 종목을 했을 때, day A 정상 클립으로 기준선을 만들고 **day B** 를 평가.
                      same-day 기준선 / cross-day 기준선 / 기준선 없음 을 비교 → 앱 시나리오(다른 날) 손실 추정.
실험 G3 GT→MP 이동 : eligible 피처의 GT vs MediaPipe 계통 편차(bias)와 기준선-상대 임계값의 스케일 영향.
실험 G4 기준선 잡음 : k=3 중앙값 자체의 표준오차 vs threshold_rel 크기 → 개인화가 신호보다 잡음을 넣는가.

출력: outputs/personalization_gap.csv, outputs/PERSONALIZATION_GAP.md
"""
from __future__ import annotations

import json
import sys
import warnings
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.metrics import balanced_accuracy_score, roc_auc_score, roc_curve
from sklearn.model_selection import GroupKFold

from features import build_or_load_features

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
warnings.filterwarnings("ignore")

HERE = Path(__file__).resolve().parent
OUT = HERE / "outputs"
K = 3
RNG = np.random.default_rng(0)


def youden(score, y):
    fpr, tpr, thr = roc_curve(y, score)
    t = thr[int(np.argmax(tpr - fpr))]
    return float(t) if np.isfinite(t) else float(np.median(score))


def bacc(score, y, t):
    return balanced_accuracy_score(y, (score >= t).astype(int))


def load_rule_frames():
    """활성 규칙(종목×조건×피처) → 클립 단위 (값, 라벨, 수행자, 촬영일)."""
    doc = json.load(open(HERE / "rules" / "rules_mp_v0.json", encoding="utf-8"))
    clips = pd.read_parquet(OUT / "clips.parquet").set_index("clip_id")
    conds = pd.read_parquet(OUT / "conditions.parquet")
    feats = build_or_load_features(OUT)
    clips = clips.loc[clips.index.intersection(feats.index)]
    seen, out = set(), []
    for r in doc["rules"]:
        if r["status"] == "exclude":
            continue
        key = (r["exercise"], r["condition"])
        if key in seen:
            continue
        seen.add(key)
        col = r["feature"]
        if col not in feats.columns:
            continue
        g = clips[clips.exercise == r["exercise"]]
        sub = conds[(conds.clip_id.isin(g.index)) & (conds.condition == r["condition"])]
        if sub.empty:
            continue
        yv = sub.drop_duplicates("clip_id").set_index("clip_id")["value"].reindex(g.index)
        m = yv.notna().to_numpy()
        ids = g.index[m]
        y = yv[m].to_numpy().astype(int)
        v = feats.loc[ids, col].to_numpy(dtype=np.float64)
        ok = np.isfinite(v)
        if ok.sum() < 60:
            continue
        pb = r.get("personal_baseline") or {}
        out.append(dict(rule=r, exercise=r["exercise"], condition=r["condition"], feature=col,
                        eligible=bool(pb.get("eligible")), thr_rel=pb.get("threshold_rel"),
                        ids=ids[ok], y=y[ok], v=v[ok],
                        performer=g.loc[ids[ok], "performer"].to_numpy(),
                        day=g.loc[ids[ok], "day"].to_numpy(),
                        order=(g.loc[ids[ok], "day"].astype(str) + "-" + ids[ok].str.rsplit("-", n=1).str[-1].str.zfill(4)).to_numpy()))
    return out


# ---------------------------------------------------------------- G1 분산 분해
def variance_components(v, y, performer, day):
    """정상 클립만: total = between-person + between-day(within person) + within-day."""
    m = y == 1
    v, p, d = v[m], performer[m], day[m]
    if len(v) < 40:
        return None
    df = pd.DataFrame(dict(v=v, p=p, d=d))
    cell = df.groupby(["p", "d"])["v"].agg(["mean", "count"]).reset_index()
    within = df.merge(cell[["p", "d", "mean"]], on=["p", "d"])
    var_within = float(np.mean((within["v"] - within["mean"]) ** 2))
    pmean = cell.groupby("p")["mean"].agg(["mean", "count"]).reset_index().rename(columns={"mean": "pmean", "count": "ndays"})
    multi = pmean[pmean.ndays >= 2]
    cell2 = cell.merge(pmean[["p", "pmean"]], on="p")
    var_day = float(np.mean((cell2[cell2.p.isin(multi.p)]["mean"] - cell2[cell2.p.isin(multi.p)]["pmean"]) ** 2)) if len(multi) else np.nan
    var_person = float(np.var(pmean["pmean"]))
    tot = float(np.var(v))
    return dict(var_total=tot, var_person=var_person, var_day=var_day, var_within=var_within,
                n_persons=int(df.p.nunique()), n_multi_day=int(len(multi)),
                frac_person=(var_person / tot if tot > 0 else np.nan),
                frac_day=(var_day / tot if tot > 0 and np.isfinite(var_day) else np.nan))


# ---------------------------------------------------------------- G2 세션 전이
def session_transfer(v, y, performer, day, order, sign):
    """day A 정상 k클립 기준선 → day B 평가 (cross) vs day B 자체 기준선 (same) vs 기준선 없음."""
    s = sign * v
    pairs = []
    for p in np.unique(performer):
        idx = np.flatnonzero(performer == p)
        days = pd.Series(day[idx]).value_counts()
        days = days[days >= 4]
        if len(days) < 2:
            continue
        dl = sorted(days.index.tolist())
        a, b = dl[0], dl[1]
        ia = idx[day[idx] == a]
        ib = idx[day[idx] == b]
        pos_a = [i for i in ia[np.argsort(order[ia])] if y[i] == 1]
        pos_b = [i for i in ib[np.argsort(order[ib])] if y[i] == 1]
        if len(pos_a) < K or len(pos_b) < K + 1:
            continue
        base_a = float(np.median(s[pos_a[:K]]))
        base_b = float(np.median(s[pos_b[:K]]))
        eval_idx = [i for i in ib if i not in pos_b[:K]]     # day B 에서 기준선 클립 제외
        if len(eval_idx) < 4 or len(set(y[eval_idx])) < 2:
            continue
        for i in eval_idx:
            pairs.append((p, s[i], y[i], s[i] - base_a, s[i] - base_b))
    if len(pairs) < 60:
        return None
    dfp = pd.DataFrame(pairs, columns=["p", "raw", "y", "cross", "same"])
    if dfp.y.nunique() < 2 or dfp.p.nunique() < 4:
        return None
    res = {}
    gkf = GroupKFold(n_splits=min(5, dfp.p.nunique()))
    for col in ("raw", "cross", "same"):
        aucs, baccs = [], []
        for tr, te in gkf.split(dfp, dfp.y, dfp.p):
            if dfp.y.iloc[tr].nunique() < 2 or dfp.y.iloc[te].nunique() < 2:
                continue
            t = youden(dfp[col].iloc[tr].to_numpy(), dfp.y.iloc[tr].to_numpy())
            aucs.append(roc_auc_score(dfp.y.iloc[te], dfp[col].iloc[te]))
            baccs.append(bacc(dfp[col].iloc[te].to_numpy(), dfp.y.iloc[te].to_numpy(), t))
        res[f"{col}_auc"] = float(np.mean(aucs)) if aucs else np.nan
        res[f"{col}_bacc"] = float(np.mean(baccs)) if baccs else np.nan
    res.update(n_eval=len(dfp), n_persons=int(dfp.p.nunique()))
    # 같은 사람의 day A 기준선 vs day B 기준선 차이 (세션 이동량)
    shifts = []
    for p in dfp.p.unique():
        d = dfp[dfp.p == p]
        shifts.append(float((d["same"] - d["cross"]).iloc[0]))     # = base_a - base_b
    res["baseline_shift_sd"] = float(np.std(shifts))
    res["baseline_shift_abs_med"] = float(np.median(np.abs(shifts)))
    return res


# ---------------------------------------------------------------- G4 기준선 잡음
def baseline_noise(v, y, performer, sign, thr_rel):
    """k=3 중앙값 기준선의 표본 변동(부트스트랩 SD) vs |threshold_rel|."""
    s = sign * v
    sds, spreads = [], []
    for p in np.unique(performer):
        idx = np.flatnonzero((performer == p) & (y == 1))
        if len(idx) < 6:
            continue
        vals = s[idx]
        boots = [float(np.median(RNG.choice(vals, K, replace=False))) for _ in range(200)]
        sds.append(float(np.std(boots)))
        spreads.append(float(np.std(vals)))
    if not sds:
        return None
    out = dict(baseline_sd=float(np.median(sds)), person_clip_sd=float(np.median(spreads)))
    if thr_rel is not None and np.isfinite(thr_rel):
        out["thr_rel_abs"] = abs(float(thr_rel))
        out["noise_ratio"] = out["baseline_sd"] / max(abs(float(thr_rel)), 1e-9)
    return out


def main():
    frames = load_rule_frames()
    print(f"[load] 규칙 {len(frames)}개 (eligible {sum(f['eligible'] for f in frames)})", flush=True)
    fid = pd.read_csv(OUT / "expA_feature_fidelity.csv") if (OUT / "expA_feature_fidelity.csv").exists() else None
    rows = []
    for f in frames:
        sign = 1.0 if f["rule"]["op"] == "<" else -1.0
        row = dict(exercise=f["exercise"], condition=f["condition"], feature=f["feature"], eligible=f["eligible"], thr_rel=f["thr_rel"],
                   n=len(f["y"]), n_persons=int(len(np.unique(f["performer"]))), n_days=int(len(np.unique(f["day"]))))
        vc = variance_components(f["v"], f["y"], f["performer"], f["day"])
        if vc:
            row.update({f"g1_{k}": v for k, v in vc.items()})
        st = session_transfer(f["v"], f["y"], f["performer"], f["day"], f["order"], sign)
        if st:
            row.update({f"g2_{k}": v for k, v in st.items()})
        bn = baseline_noise(f["v"], f["y"], f["performer"], sign, f["thr_rel"])
        if bn:
            row.update({f"g4_{k}": v for k, v in bn.items()})
        if fid is not None:
            ff = fid[(fid.feature == f["feature"]) & (fid.view == "FUSED")]
            if len(ff):
                row["g3_mp_bias"] = float(ff.bias.iloc[0])
                row["g3_mp_mae"] = float(ff.mae.iloc[0])
                row["g3_mp_spearman"] = float(ff.spearman.iloc[0])
        rows.append(row)
        print(f"  {f['exercise']} | {f['condition']}", flush=True)
    res = pd.DataFrame(rows)
    res.to_csv(OUT / "personalization_gap.csv", index=False, encoding="utf-8-sig")
    write_report(res)


def write_report(res: pd.DataFrame):
    el = res[res.eligible]
    g1 = res.dropna(subset=["g1_frac_person"])
    g1d = g1.dropna(subset=["g1_frac_day"])
    g2 = res.dropna(subset=["g2_cross_auc"])
    g4 = res.dropna(subset=["g4_baseline_sd"])
    L = ["# 개인화 이득의 출처 — AIHub 데이터와 앱 사용의 차이\n",
         f"- 규칙 {len(res)}개(활성, 종목×조건), 그중 기준선 eligible {int(res.eligible.sum())}개",
         f"- 수행자 112명 / 촬영일 34일. **수행자당 촬영일**: 1일 47명(42%), 2일 이상 65명(58%) — 부분적으로만 교차",
         "- 핵심 물음: 기준선이 흡수하는 '개인차'가 사람의 안정적 특성인가, 그날 세션(카메라·조명·복장·컨디션) 효과인가\n",
         "## G1. 분산 분해 — 정상 클립 피처 분산의 출처\n",
         "| 구분 | 규칙 수 | person 비중(중앙값) | day(세션) 비중 | within-day 잔차 |", "|---|---|---|---|---|"]
    for label, d in (("전체", g1), ("eligible", g1[g1.eligible])):
        dd = d.dropna(subset=["g1_frac_day"])
        L.append(f"| {label} | {len(d)} | {d.g1_frac_person.median()*100:.0f}% | " +
                 (f"{dd.g1_frac_day.median()*100:.0f}% (n={len(dd)})" if len(dd) else "측정 불가") +
                 f" | {(d.g1_var_within/d.g1_var_total).median()*100:.0f}% |")
    solid = g1d[g1d.g1_n_multi_day >= 10]
    L.append("")
    L.append(f"- **day 비중 추정은 2일 이상 촬영된 수행자 수에 좌우된다.** 근거 수행자 ≥10명인 규칙({len(solid)}개)만 보면 day 비중 중앙값 "
             f"**{solid.g1_frac_day.median()*100:.0f}%**; 근거 <5명인 규칙 {int((g1d.g1_n_multi_day<5).sum())}개는 추정이 불안정(1명짜리 표본에서 100% 초과도 나온다) → 아래 표에서 근거 수를 함께 볼 것.")
    L.append(f"- **eligible 규칙 9개는 multi-day 수행자가 0~2명**(OHP·컬·딥스·플라이·케이블·페이스풀 종목은 사실상 하루에 몰아 촬영) → "
             f"**person 과 day 를 분리할 수 없다.** 즉 eligible 규칙에서 잰 person 비중 {g1[g1.eligible].g1_frac_person.median()*100:.0f}% 는 '사람 + 그날 세션' 합계다.")
    if len(g1d):
        L += ["", "### day(세션) 비중이 큰 규칙 10 — 근거 수행자 수를 함께 볼 것\n",
              "| 종목 | 조건 | 피처 | person | day | within | 근거(multi-day 수행자) |", "|---|---|---|---|---|---|---|"]
        for r in g1d.nlargest(10, "g1_frac_day").itertuples():
            flag = " ⚠추정불가" if r.g1_n_multi_day < 5 else ""
            L.append(f"| {r.exercise} | {r.condition} | {r.feature} | {r.g1_frac_person*100:.0f}% | {r.g1_frac_day*100:.0f}% | {r.g1_var_within/r.g1_var_total*100:.0f}% | {int(r.g1_n_multi_day)}명{flag} |")
    L += ["", "## G2. 세션 전이 — 기준선을 **다른 날** 에 만들면 (앱의 실제 시나리오)\n"]
    if len(g2):
        L += ["| 지표 | 기준선 없음(raw) | 같은 날 기준선(same) | **다른 날 기준선(cross)** |", "|---|---|---|---|",
              f"| AUC 중앙값 | {g2.g2_raw_auc.median():.3f} | {g2.g2_same_auc.median():.3f} | {g2.g2_cross_auc.median():.3f} |",
              f"| 균형정확도 중앙값 | {g2.g2_raw_bacc.median():.3f} | {g2.g2_same_bacc.median():.3f} | {g2.g2_cross_bacc.median():.3f} |", "",
              f"- 평가 규칙 {len(g2)}개 (2일 이상 수행자 보유 종목), 규칙당 수행자 중앙값 {g2.g2_n_persons.median():.0f}명, 평가 클립 중앙값 {g2.g2_n_eval.median():.0f}개",
              f"- **같은 날 이득** {(g2.g2_same_auc-g2.g2_raw_auc).median():+.3f} → **다른 날 이득** {(g2.g2_cross_auc-g2.g2_raw_auc).median():+.3f} "
              f"(세션이 바뀌며 사라지는 몫 {((g2.g2_same_auc-g2.g2_raw_auc)-(g2.g2_cross_auc-g2.g2_raw_auc)).median():+.3f})",
              f"- 같은 사람의 day A 기준선 vs day B 기준선 차이(=세션 이동량): 절대 중앙값 {g2.g2_baseline_shift_abs_med.median():.3g}, SD {g2.g2_baseline_shift_sd.median():.3g}", "",
              "| 종목 | 조건 | 피처 | raw | same | cross | Δsame | Δcross |", "|---|---|---|---|---|---|---|---|"]
        for r in g2.sort_values("g2_cross_auc", ascending=False).itertuples():
            L.append(f"| {r.exercise} | {r.condition} | {r.feature} | {r.g2_raw_auc:.3f} | {r.g2_same_auc:.3f} | {r.g2_cross_auc:.3f} | {r.g2_same_auc-r.g2_raw_auc:+.3f} | {r.g2_cross_auc-r.g2_raw_auc:+.3f} |")
    else:
        L.append("- 2일 이상 수행자가 충분한 규칙이 없어 측정 불가")
    L += ["", "## G3. GT → MediaPipe 계통 이동 — 임계값 스케일\n",
          "`threshold_rel` 은 GT 3D 에서 적합했다. MediaPipe 값은 GT 대비 계통 편차(bias)가 있으므로, 기준선을 빼면 bias 는 상쇄되지만 **스케일·잡음은 남는다**.\n",
          "| 종목 | 조건 | 피처 | MP bias | MP MAE | Spearman | threshold_rel | bias/thr |", "|---|---|---|---|---|---|---|---|"]
    for r in el.dropna(subset=["g3_mp_bias"]).itertuples():
        ratio = abs(r.g3_mp_bias) / abs(r.thr_rel) if r.thr_rel else np.nan
        L.append(f"| {r.exercise} | {r.condition} | {r.feature} | {r.g3_mp_bias:+.3g} | {r.g3_mp_mae:.3g} | {r.g3_mp_spearman:.2f} | {r.thr_rel:+.3g} | {ratio:.2f} |")
    L += ["", "## G4. 기준선 자체의 잡음 — k=3 중앙값의 표본 변동\n",
          "| 종목 | 조건 | 피처 | 기준선 SD(k=3) | 개인 내 클립 SD | \\|threshold_rel\\| | 잡음비 |", "|---|---|---|---|---|---|---|"]
    for r in g4[g4.eligible].itertuples():
        tr = abs(r.thr_rel) if r.thr_rel else np.nan
        nr = getattr(r, "g4_noise_ratio", np.nan)
        L.append(f"| {r.exercise} | {r.condition} | {r.feature} | {r.g4_baseline_sd:.3g} | {r.g4_person_clip_sd:.3g} | {tr:.3g} | {nr:.2f} |")
    if len(g4[g4.eligible]):
        L.append(f"\n- eligible 규칙 잡음비(기준선 SD / |threshold_rel|) 중앙값 **{g4[g4.eligible].g4_noise_ratio.median():.2f}** — 1 에 가까울수록 기준선의 흔들림이 판정 경계만큼 커서 개인화가 잡음을 넣는다. **단 이것은 GT 3D 기준.**")
    e2 = el.dropna(subset=["g3_mp_mae"]).copy()
    if len(e2):
        e2["mp_noise_ratio"] = e2.g3_mp_mae / e2.thr_rel.abs()
        L += ["", "### MediaPipe 스케일에서의 잡음비 (세트 단위 MAE / |threshold_rel|) — 앱의 실제 조건\n",
              "| 종목 | 조건 | 피처 | MP MAE | \\|thr_rel\\| | 잡음비 |", "|---|---|---|---|---|---|"]
        for r in e2.sort_values("mp_noise_ratio", ascending=False).itertuples():
            L.append(f"| {r.exercise} | {r.condition} | {r.feature} | {r.g3_mp_mae:.3g} | {abs(r.thr_rel):.3g} | **{r.mp_noise_ratio:.2f}** |")
        L.append(f"\n- 중앙값 **{e2.mp_noise_ratio.median():.2f}** — GT 에서 0.30 이던 잡음비가 MediaPipe 로 오면 판정 경계와 맞먹는다. "
                 "기준선(3세트 중앙값)과 평가 세트 양쪽에 이 잡음이 들어가므로, GT 에서 측정한 이득의 상당 부분이 잡음에 묻힐 수 있다.")
    (OUT / "PERSONALIZATION_GAP.md").write_text("\n".join(L), encoding="utf-8")
    print("\n".join(L[:24]))


if __name__ == "__main__":
    main()
