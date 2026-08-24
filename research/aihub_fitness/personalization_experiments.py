#!/usr/bin/env python
"""개인화 실험 4종 — "사용자별 임계값"을 앱에 넣기 전에 데이터로 결판.

기판: AIHub GT 3D 피처(QC 후, 3D 양호 종목), 규칙 = rule_engine_v1 이 고른 단일 피처(MediaPipe 가능·미러 불변 화이트리스트).
수행자 113명 × 수행자당 클립 15~40개(종목별) 라 개인 단위 분석이 가능하다.

실험 1 오라클 상한   : 수행자별 임계값을 본인 데이터로 적합(오라클=인샘플, 분할반=정직)해 본인에게 평가 vs 인구 임계값(GroupKFold).
                      오라클조차 이득이 작으면 '개인별 임계값 재적합(A)'은 논의 가치 없음.
실험 2 분산 분해     : 수행자별 임계값의 between-person 분산 vs 수행자 내 부트스트랩(추정 노이즈) 분산 → ICC. 개인차가 노이즈보다 큰가.
실험 3 체형 조건화   : 피처의 '정상 수준'을 체형 비율(대퇴/경골, 몸통/다리, 어깨/골반폭, 키 프록시)로 회귀해 빼는 조건화 vs raw vs
                      개인 기준선(정상 앞 3클립). 라벨·사용자 노력 0 으로 개인차를 흡수할 수 있는가.
실험 4 기준선 오염   : 기준선 3클립 중 1~2개가 실제로 위반일 때 성능 붕괴 정도, 인구 규칙으로 기준선 클립을 거르는 가드의 회복률.
출력: outputs/personalization_experiments.csv, outputs/personalization_summary.md
"""
from __future__ import annotations

import sys
import warnings
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.linear_model import Ridge
from sklearn.metrics import balanced_accuracy_score, roc_auc_score, roc_curve
from sklearn.model_selection import GroupKFold

from features import J, apply_qc_mask, build_or_load_features, load_kp3d

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
warnings.filterwarnings("ignore")

OUT = Path(__file__).resolve().parent / "outputs"
RNG = np.random.default_rng(0)
MIN_CLIPS_PERSON, MIN_CLASS_PERSON = 8, 3
BOOT = 100
BASE_K = 3


# ---------------------------------------------------------------- 공통
def youden(score, y):
    fpr, tpr, thr = roc_curve(y, score)
    t = thr[int(np.argmax(tpr - fpr))]
    return float(t) if np.isfinite(t) else float(np.median(score))


def bacc(score, y, t):
    return balanced_accuracy_score(y, (score >= t).astype(int))


def pop_thresholds(score, y, groups, n_splits=5):
    """GroupKFold: 각 클립에 '자기 수행자를 제외한' 인구 임계값을 배정."""
    t = np.full(len(y), np.nan)
    n_g = len(np.unique(groups))
    gkf = GroupKFold(n_splits=min(n_splits, n_g))
    for tr, te in gkf.split(score, y, groups):
        if len(np.unique(y[tr])) < 2:
            continue
        t[te] = youden(score[tr], y[tr])
    return t


def body_ratios() -> pd.DataFrame:
    """수행자별 체형 비율 (GT 3D, QC 마스킹 후 클립 중앙값 → 수행자 중앙값)."""
    clip_ids, arr = load_kp3d(OUT)
    clip_ids, arr, _ = apply_qc_mask(clip_ids, arr, OUT)
    g = lambda n: arr[:, :, J[n], :]
    def L(a, b):
        return np.nanmedian(np.linalg.norm(g(a) - g(b), axis=-1), axis=1)
    thigh = (L("LHip", "LKnee") + L("RHip", "RKnee")) / 2
    shin = (L("LKnee", "LAnkle") + L("RKnee", "RAnkle")) / 2
    leg = (L("LHip", "LAnkle") + L("RHip", "RAnkle")) / 2
    hip_mid = (g("LHip") + g("RHip")) / 2
    torso = np.nanmedian(np.linalg.norm(g("Neck") - hip_mid, axis=-1), axis=1)
    sh_w = L("LShoulder", "RShoulder")
    hip_w = L("LHip", "RHip")
    uarm = (L("LShoulder", "LElbow") + L("RShoulder", "RElbow")) / 2
    farm = (L("LElbow", "LWrist") + L("RElbow", "RWrist")) / 2
    df = pd.DataFrame(dict(clip_id=clip_ids, thigh=thigh, shin=shin, leg=leg, torso=torso, sh_w=sh_w, hip_w=hip_w, uarm=uarm, farm=farm))
    clips = pd.read_parquet(OUT / "clips.parquet").set_index("clip_id")
    df["performer"] = clips.loc[df.clip_id, "performer"].to_numpy()
    per = df.groupby("performer")[["thigh", "shin", "leg", "torso", "sh_w", "hip_w", "uarm", "farm"]].median()
    per["r_thigh_shin"] = per.thigh / per.shin
    per["r_torso_leg"] = per.torso / per.leg
    per["r_sh_hip"] = per.sh_w / per.hip_w
    per["height_proxy"] = per.torso + per.leg
    per["r_arm_torso"] = (per.uarm + per.farm) / per.torso
    return per


BODY_COLS = ["r_thigh_shin", "r_torso_leg", "r_sh_hip", "height_proxy", "r_arm_torso"]


# ---------------------------------------------------------------- 실험 1·2
def exp12(v, y, grp, sign):
    s = sign * v
    t_pop = pop_thresholds(s, y, grp)
    rows = []
    thetas, within_sd = [], []
    for p in np.unique(grp):
        idx = np.flatnonzero(grp == p)
        yp, sp = y[idx], s[idx]
        if len(idx) < MIN_CLIPS_PERSON or min(yp.sum(), len(yp) - yp.sum()) < MIN_CLASS_PERSON:
            continue
        # 인구 임계값으로 본인 평가
        tp = t_pop[idx]
        if np.isnan(tp).all():
            continue
        b_pop = bacc(sp, yp, np.nanmedian(tp))
        # 오라클(인샘플)
        t_or = youden(sp, yp)
        b_or = bacc(sp, yp, t_or)
        # 분할반(정직): 2회 교차
        perm = RNG.permutation(len(idx))
        h1, h2 = perm[: len(idx) // 2], perm[len(idx) // 2:]
        b_half = []
        for a, b in ((h1, h2), (h2, h1)):
            if len(np.unique(yp[a])) < 2 or len(np.unique(yp[b])) < 2:
                continue
            b_half.append(bacc(sp[b], yp[b], youden(sp[a], yp[a])))
        auc_within = roc_auc_score(yp, sp)
        # 부트스트랩 임계값 SD (추정 노이즈)
        boots = []
        for _ in range(BOOT):
            bi = RNG.integers(0, len(idx), len(idx))
            if len(np.unique(yp[bi])) < 2:
                continue
            boots.append(youden(sp[bi], yp[bi]))
        thetas.append(t_or)
        within_sd.append(np.std(boots) if len(boots) > 5 else np.nan)
        rows.append(dict(p=p, n=len(idx), bacc_pop=b_pop, bacc_oracle=b_or, bacc_half=(np.mean(b_half) if b_half else np.nan), auc_within=auc_within))
    if len(rows) < 5:
        return None
    d = pd.DataFrame(rows)
    thetas = np.array(thetas); within_sd = np.array(within_sd)
    var_b = float(np.var(thetas))
    var_w = float(np.nanmean(within_sd ** 2))
    icc = float(max(0.0, 1.0 - var_w / var_b)) if var_b > 0 else 0.0
    # 인구 pooled AUC (수행자 섞임) vs 수행자 내 AUC 평균
    return dict(n_persons=len(d), bacc_pop=d.bacc_pop.mean(), bacc_oracle=d.bacc_oracle.mean(), bacc_half=d.bacc_half.mean(),
                auc_pooled=roc_auc_score(y, s), auc_within=d.auc_within.mean(),
                theta_between_sd=float(np.sqrt(var_b)), theta_within_sd=float(np.sqrt(var_w)), icc=icc,
                theta_scale=float(np.std(s)))


# ---------------------------------------------------------------- 실험 3
def exp3(v, y, grp, sign, body: pd.DataFrame, order_key):
    s = sign * v
    persons = np.unique(grp)
    have = np.array([p in body.index for p in grp])
    if have.mean() < 0.8:
        return None
    B = body.reindex(grp)[BODY_COLS].to_numpy(dtype=np.float64)
    ok = np.isfinite(B).all(axis=1)
    s, y, grp, B, order_key = s[ok], y[ok], grp[ok], B[ok], order_key[ok]
    n_g = len(np.unique(grp))
    if n_g < 10:
        return None
    gkf = GroupKFold(n_splits=min(5, n_g))
    raw_b, body_b, base_b, raw_a, body_a, base_a, r2s = [], [], [], [], [], [], []
    for tr, te in gkf.split(s, y, grp):
        if len(np.unique(y[tr])) < 2 or len(np.unique(y[te])) < 2:
            continue
        # raw
        t = youden(s[tr], y[tr])
        raw_b.append(bacc(s[te], y[te], t)); raw_a.append(roc_auc_score(y[te], s[te]))
        # body-conditioned: 학습 수행자의 '정상' 클립에서 s ~ body 회귀 → 예측 정상 수준을 뺀다
        mu, sd = B[tr].mean(0), B[tr].std(0) + 1e-9
        Z = (B - mu) / sd
        neu = tr[y[tr] == 1]
        reg = Ridge(alpha=1.0).fit(Z[neu], s[neu])
        pred = reg.predict(Z)
        ss_res = np.sum((s[neu] - pred[neu]) ** 2); ss_tot = np.sum((s[neu] - s[neu].mean()) ** 2)
        r2s.append(1 - ss_res / ss_tot if ss_tot > 0 else 0.0)
        adj = s - pred
        t2 = youden(adj[tr], y[tr])
        body_b.append(bacc(adj[te], y[te], t2)); body_a.append(roc_auc_score(y[te], adj[te]))
        # personal baseline(k=3 정상 앞 클립) — 기준선 클립 제외 평가, 임계값은 학습 수행자 adjusted 로
        adj3 = np.full(len(s), np.nan); ev = np.zeros(len(s), bool)
        for p in np.unique(grp):
            idx = np.flatnonzero(grp == p); idx = idx[np.argsort(order_key[idx])]
            pos = [i for i in idx if y[i] == 1]
            if len(pos) < BASE_K:
                continue
            bidx = pos[:BASE_K]; b = np.median(s[bidx])
            for i in idx:
                if i not in bidx:
                    adj3[i] = s[i] - b; ev[i] = True
        trm, tem = ev[tr], ev[te]
        if trm.sum() > 20 and tem.sum() > 10 and len(np.unique(y[te][tem])) == 2 and len(np.unique(y[tr][trm])) == 2:
            t3 = youden(adj3[tr][trm], y[tr][trm])
            base_b.append(bacc(adj3[te][tem], y[te][tem], t3)); base_a.append(roc_auc_score(y[te][tem], adj3[te][tem]))
    if not raw_b:
        return None
    return dict(r2_body=float(np.mean(r2s)), bacc_raw=np.mean(raw_b), bacc_body=np.mean(body_b), bacc_base=(np.mean(base_b) if base_b else np.nan),
                auc_raw=np.mean(raw_a), auc_body=np.mean(body_a), auc_base=(np.mean(base_a) if base_a else np.nan))


# ---------------------------------------------------------------- 실험 4
def exp4(v, y, grp, sign, order_key):
    s = sign * v
    t_pop = pop_thresholds(s, y, grp)     # 가드용 인구 규칙 (자기 수행자 제외)
    out = {}
    for contam in (0, 1, 2):
        for guard in (False, True):
            if contam == 0 and guard:
                continue
            adj = np.full(len(s), np.nan); ev = np.zeros(len(s), bool); n_rej = 0; n_nobase = 0
            for p in np.unique(grp):
                idx = np.flatnonzero(grp == p); idx = idx[np.argsort(order_key[idx])]
                pos = [i for i in idx if y[i] == 1]; neg = [i for i in idx if y[i] == 0]
                if len(pos) < BASE_K or len(neg) < contam:
                    continue
                bidx = list(pos[:BASE_K])
                if contam:
                    swap = RNG.choice(neg, contam, replace=False)
                    bidx = bidx[: BASE_K - contam] + list(swap)
                if guard:
                    kept = [i for i in bidx if not np.isnan(t_pop[i]) and s[i] >= t_pop[i]]   # 인구 규칙상 '정상'만 채택
                    n_rej += len(bidx) - len(kept)
                    if len(kept) < 2:
                        n_nobase += 1
                        continue        # 기준선 불가 → 이 수행자는 평가 제외 (raw 로 폴백하는 대신 보수적으로)
                    b = np.median(s[kept])
                else:
                    b = np.median(s[bidx])
                for i in idx:
                    if i not in bidx:
                        adj[i] = s[i] - b; ev[i] = True
            if ev.sum() < 40 or len(np.unique(y[ev])) < 2:
                continue
            # 평가: AUC(임계값 무관) + GroupKFold bal-acc
            a = roc_auc_score(y[ev], adj[ev])
            tt = pop_thresholds(adj[ev], y[ev], grp[ev])
            m = ~np.isnan(tt)
            b_ = bacc(adj[ev][m], y[ev][m], tt[m]) if m.sum() > 10 else np.nan
            key = f"c{contam}" + ("_guard" if guard else "")
            out[key + "_auc"] = a; out[key + "_bacc"] = b_
            if guard:
                out[key + "_rej"] = n_rej; out[key + "_nobase"] = n_nobase
    return out or None


def main():
    clips = pd.read_parquet(OUT / "clips.parquet").set_index("clip_id")
    conds = pd.read_parquet(OUT / "conditions.parquet")
    feats = build_or_load_features(OUT)
    v1 = pd.read_csv(OUT / "rule_engine_v1.csv")
    v1["subtype"] = v1["subtype"].fillna("")
    v1 = v1[v1.single_feature.notna() & v1.single_auc.notna() & v1.subtype.isin(["", "all"])]
    spine = pd.read_parquet(OUT / "spine_subtype.parquet").set_index("clip_id") if (OUT / "spine_subtype.parquet").exists() else None
    clips = clips.loc[clips.index.intersection(feats.index)]
    clips["group"] = np.where(clips["performer"].astype(str) != "", clips["performer"].astype(str), clips["day"].astype(str))
    clips["order_key"] = clips["day"].astype(str) + "-" + clips.index.str.rsplit("-", n=1).str[-1].str.zfill(4)
    body = body_ratios()
    print(f"[load] 규칙 {len(v1)} | 수행자 체형표 {len(body)}명 | 체형 비율 범위: 대퇴/경골 {body.r_thigh_shin.quantile(.05):.2f}~{body.r_thigh_shin.quantile(.95):.2f}, 몸통/다리 {body.r_torso_leg.quantile(.05):.2f}~{body.r_torso_leg.quantile(.95):.2f}", flush=True)

    rows = []
    for r in v1.itertuples():
        g = clips[clips.exercise == r.exercise]
        ids_all = g.index
        sub = conds[(conds.clip_id.isin(ids_all)) & (conds.condition == r.condition)]
        if sub.empty:
            continue
        yv = sub.drop_duplicates("clip_id").set_index("clip_id")["value"].reindex(ids_all)
        mask = yv.notna().to_numpy()
        ids = ids_all[mask]; y = yv[mask].to_numpy().astype(int)
        v = feats.loc[ids, r.single_feature].to_numpy(dtype=np.float64)
        ok = np.isfinite(v)
        ids, y, v = ids[ok], y[ok], v[ok]
        if len(y) < 60:
            continue
        grp = g.loc[ids, "group"].to_numpy()
        okey = g.loc[ids, "order_key"].to_numpy()
        sign = 1.0 if roc_auc_score(y, v) >= 0.5 else -1.0
        row = dict(exercise=r.exercise, condition=r.condition, subtype=r.subtype, feature=r.single_feature, n=len(y), n_persons=len(np.unique(grp)),
                   family=r.single_feature.rsplit("__", 1)[0], stat=r.single_feature.rsplit("__", 1)[1] if "__" in r.single_feature else "")
        e12 = exp12(v, y, grp, sign)
        if e12: row.update({f"e1_{k}": val for k, val in e12.items()})
        e3 = exp3(v, y, grp, sign, body, okey)
        if e3: row.update({f"e3_{k}": val for k, val in e3.items()})
        e4 = exp4(v, y, grp, sign, okey)
        if e4: row.update({f"e4_{k}": val for k, val in e4.items()})
        rows.append(row)
        print(f"  {r.exercise} | {r.condition} done", flush=True)
    res = pd.DataFrame(rows)
    res.to_csv(OUT / "personalization_experiments.csv", index=False, encoding="utf-8-sig")
    write_summary(res, body)


def write_summary(res: pd.DataFrame, body: pd.DataFrame):
    L = ["# 개인화 실험 4종 — 결과 (GT 3D 피처 · 3D 양호 종목 · 단일 규칙 피처, 수행자 홀드아웃)\n",
         f"- 규칙 {len(res)}개, 규칙당 수행자 평균 {res.e1_n_persons.mean():.0f}명(≥8클립·양 클래스≥3), 체형표 {len(body)}명\n"]
    e1 = res.dropna(subset=["e1_bacc_pop"])
    L += ["## 실험 1 — 개인별 임계값의 오라클 상한 vs 인구 임계값 (균형정확도, 수행자 평균)\n",
          "| | 인구 임계값(타인으로 학습) | 개인 분할반(정직) | 개인 오라클(인샘플 상한) |", "|---|---|---|---|",
          f"| 중앙값 | {e1.e1_bacc_pop.median():.3f} | {e1.e1_bacc_half.median():.3f} | {e1.e1_bacc_oracle.median():.3f} |",
          f"| 평균 | {e1.e1_bacc_pop.mean():.3f} | {e1.e1_bacc_half.mean():.3f} | {e1.e1_bacc_oracle.mean():.3f} |",
          "",
          f"- Δ(분할반 − 인구) 중앙값 {(e1.e1_bacc_half-e1.e1_bacc_pop).median():+.3f} (개선 ≥+0.03: {((e1.e1_bacc_half-e1.e1_bacc_pop)>=0.03).sum()}개, 악화 ≤−0.03: {((e1.e1_bacc_half-e1.e1_bacc_pop)<=-0.03).sum()}개)",
          f"- Δ(오라클 − 인구) 중앙값 {(e1.e1_bacc_oracle-e1.e1_bacc_pop).median():+.3f} — 인샘플이라 낙관적 상한",
          f"- AUC: 인구 pooled {e1.e1_auc_pooled.median():.3f} vs 수행자 내 평균 {e1.e1_auc_within.median():.3f} (Δ {(e1.e1_auc_within-e1.e1_auc_pooled).median():+.3f}) — 양수면 '사람 간 섞임'이 판별을 흐린다는 뜻\n"]
    L += ["### 분할반 이득이 큰 규칙 10 / 손해 큰 규칙 5\n", "| 종목 | 조건 | 피처 | 인구 | 분할반 | 오라클 | Δ(분할반) | ICC |", "|---|---|---|---|---|---|---|---|"]
    e1s = e1.assign(d=e1.e1_bacc_half - e1.e1_bacc_pop)
    for r in pd.concat([e1s.nlargest(10, "d"), e1s.nsmallest(5, "d")]).itertuples():
        L.append(f"| {r.exercise} | {r.condition} | {r.feature} | {r.e1_bacc_pop:.3f} | {r.e1_bacc_half:.3f} | {r.e1_bacc_oracle:.3f} | {r.d:+.3f} | {r.e1_icc:.2f} |")
    L += ["", "## 실험 2 — 임계값 분산 분해: 개인차 vs 추정 노이즈\n",
          f"- ICC(개인 간 진짜 분산 비율 = 1 − 노이즈분산/개인간분산) 중앙값 {e1.e1_icc.median():.2f}, ≥0.5 규칙 {(e1.e1_icc>=0.5).sum()}/{len(e1)}, <0.2 규칙 {(e1.e1_icc<0.2).sum()}",
          f"- 개인 임계값 between SD / 피처 SD 중앙값 {(e1.e1_theta_between_sd/e1.e1_theta_scale).median():.2f}, 부트스트랩 within SD / 피처 SD 중앙값 {(e1.e1_theta_within_sd/e1.e1_theta_scale).median():.2f}\n",
          "### 피처 패밀리·통계별 ICC 중앙값 (n≥2)\n", "| 패밀리 | 통계 | n | ICC | Δ분할반 |", "|---|---|---|---|---|"]
    fam = e1s.assign(fam=e1s.family.str.replace(r"_(L|R|mean|minside|maxside|asym)$", "", regex=True)).groupby(["fam", "stat"]).agg(n=("exercise", "size"), icc=("e1_icc", "median"), d=("d", "median")).reset_index()
    for r in fam[fam.n >= 2].sort_values("icc", ascending=False).itertuples():
        L.append(f"| {r.fam} | {r.stat} | {r.n} | {r.icc:.2f} | {r.d:+.3f} |")
    e3 = res.dropna(subset=["e3_bacc_raw"])
    L += ["", "## 실험 3 — 체형 조건화 (라벨·사용자 노력 0) vs raw vs 개인 기준선(정상 3클립)\n",
          f"- 체형 비율 분포(수행자): 대퇴/경골 {body.r_thigh_shin.median():.2f} [{body.r_thigh_shin.quantile(.05):.2f}–{body.r_thigh_shin.quantile(.95):.2f}], 몸통/다리 {body.r_torso_leg.median():.2f} [{body.r_torso_leg.quantile(.05):.2f}–{body.r_torso_leg.quantile(.95):.2f}], 어깨/골반폭 {body.r_sh_hip.median():.2f}, 키 프록시 {body.height_proxy.median():.0f}cm [{body.height_proxy.quantile(.05):.0f}–{body.height_proxy.quantile(.95):.0f}]",
          f"- 정상 클립에서 피처~체형 R² 중앙값 {e3.e3_r2_body.median():.2f} (≥0.2: {(e3.e3_r2_body>=0.2).sum()}/{len(e3)})",
          "", "| 지표 | raw | 체형 조건화 | 개인 기준선(3클립) |", "|---|---|---|---|",
          f"| 균형정확도 중앙값 | {e3.e3_bacc_raw.median():.3f} | {e3.e3_bacc_body.median():.3f} | {e3.e3_bacc_base.median():.3f} |",
          f"| AUC 중앙값 | {e3.e3_auc_raw.median():.3f} | {e3.e3_auc_body.median():.3f} | {e3.e3_auc_base.median():.3f} |",
          "",
          f"- Δ(체형−raw) 균형정확도 중앙값 {(e3.e3_bacc_body-e3.e3_bacc_raw).median():+.3f} (개선 ≥+0.02: {((e3.e3_bacc_body-e3.e3_bacc_raw)>=0.02).sum()}, 악화 ≤−0.02: {((e3.e3_bacc_body-e3.e3_bacc_raw)<=-0.02).sum()})",
          f"- Δ(기준선−raw) 균형정확도 중앙값 {(e3.e3_bacc_base-e3.e3_bacc_raw).median():+.3f} (개선 ≥+0.02: {((e3.e3_bacc_base-e3.e3_bacc_raw)>=0.02).sum()}, 악화 ≤−0.02: {((e3.e3_bacc_base-e3.e3_bacc_raw)<=-0.02).sum()})\n",
          "### 체형 조건화 이득 큰 규칙 10\n", "| 종목 | 조건 | 피처 | R² | raw | 체형 | 기준선 |", "|---|---|---|---|---|---|---|"]
    for r in e3.assign(d=e3.e3_bacc_body - e3.e3_bacc_raw).nlargest(10, "d").itertuples():
        L.append(f"| {r.exercise} | {r.condition} | {r.feature} | {r.e3_r2_body:.2f} | {r.e3_bacc_raw:.3f} | {r.e3_bacc_body:.3f} | {r.e3_bacc_base:.3f} |")
    e4 = res.dropna(subset=["e4_c0_auc"])
    L += ["", "## 실험 4 — 기준선 오염 시뮬레이션 (기준선 3클립 중 1·2개가 위반일 때) + 인구 규칙 가드\n",
          "| 기준선 상태 | AUC 중앙값 | 균형정확도 중앙값 | Δ AUC vs clean |", "|---|---|---|---|"]
    for key, label in (("c0", "clean(정상 3)"), ("c1", "1개 오염"), ("c1_guard", "1개 오염 + 가드"), ("c2", "2개 오염"), ("c2_guard", "2개 오염 + 가드")):
        a, b = f"e4_{key}_auc", f"e4_{key}_bacc"
        if a in e4:
            L.append(f"| {label} | {e4[a].median():.3f} | {e4[b].median():.3f} | {(e4[a]-e4.e4_c0_auc).median():+.3f} |")
    if "e4_c1_guard_rej" in e4:
        L.append(f"\n- 가드 거부율: 1개 오염 시 기준선 클립 거부 평균 {e4.e4_c1_guard_rej.mean():.1f}개/규칙, 기준선 불가 수행자 평균 {e4.e4_c1_guard_nobase.mean():.1f}명; 2개 오염 시 거부 {e4.e4_c2_guard_rej.mean():.1f}개, 불가 {e4.e4_c2_guard_nobase.mean():.1f}명")
    L += ["", "## 결론(자동 요약)\n"]
    d_half = (e1.e1_bacc_half - e1.e1_bacc_pop).median()
    d_or = (e1.e1_bacc_oracle - e1.e1_bacc_pop).median()
    d_body = (e3.e3_bacc_body - e3.e3_bacc_raw).median()
    d_base = (e3.e3_bacc_base - e3.e3_bacc_raw).median()
    n_gain = int(((e1.e1_bacc_half - e1.e1_bacc_pop) >= 0.03).sum()); n_loss = int(((e1.e1_bacc_half - e1.e1_bacc_pop) <= -0.03).sum())
    L.append(f"1. 개인별 임계값 재적합(A): 정직한 분할반 이득 중앙값 {d_half:+.3f} (개선 {n_gain} vs 악화 {n_loss}), 오라클 상한 {d_or:+.3f} 은 인샘플 과적합. "
             f"ICC 중앙값 {e1.e1_icc.median():.2f} 로 개인차 자체는 실재하지만, 사용자 1명이 모을 수 있는 라벨(클래스당 5~10세트)로는 그 차이를 추정 노이즈보다 정확히 뽑지 못한다 → "
             + ("(A) 는 기본 경로에서 제외. 이득이 난 규칙(귀-어깨 간격·몸통 기울기·머리 피치 등 level 피처)은 라벨 없는 기준선 정규화(B)가 같은 정보를 잡는다" if d_half < 0.02 else "일부 규칙에서 (A) 가 유효 — 라벨 확보 가능한 경우에만"))
    L.append(f"2. 체형 조건화(라벨 0): Δ {d_body:+.3f} (R² 중앙값 {e3.e3_r2_body.median():.2f}) — 이 인구(대퇴/경골 {body.r_thigh_shin.quantile(.05):.2f}~{body.r_thigh_shin.quantile(.95):.2f})에서는 체형 비율이 피처의 개인 수준을 설명하지 못함 → **기각**. "
             f"개인 기준선(정상 3클립, 전 규칙 적용): Δ {d_base:+.3f} → 전역 적용은 중립, level 피처 규칙(personal_baseline.eligible)에만 선택 적용")
    if "e4_c1_auc" in e4:
        L.append(f"3. 기준선 오염: 1개 오염 AUC Δ {(e4.e4_c1_auc-e4.e4_c0_auc).median():+.3f} (중앙값 기준선은 1개 이상치에 강건), 2개 오염 {(e4.e4_c2_auc-e4.e4_c0_auc).median():+.3f} / 균형정확도 {(e4.e4_c2_bacc-e4.e4_c0_bacc).median():+.3f}. "
                 f"인구 규칙 가드는 거부가 과다(규칙당 {e4.e4_c1_guard_rej.mean():.0f}~{e4.e4_c2_guard_rej.mean():.0f}클립, 기준선 불가 {e4.e4_c1_guard_nobase.mean():.0f}~{e4.e4_c2_guard_nobase.mean():.0f}명)하고 생존 편향이 섞여 그대로 쓰기 어려움 → "
                 "기준선은 5세트 중앙값(2개 오염까지 허용)으로 늘리고, 가드는 '심한 이탈만 거부'하는 느슨한 형태로")
    (OUT / "personalization_summary.md").write_text("\n".join(L), encoding="utf-8")
    print("\n".join(L[:14]))


if __name__ == "__main__":
    if "--summary-only" in sys.argv and (OUT / "personalization_experiments.csv").exists():
        write_summary(pd.read_csv(OUT / "personalization_experiments.csv"), body_ratios())
    else:
        main()
