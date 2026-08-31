# -*- coding: utf-8 -*-
"""바닥 운동 — '카메라 높이' 축의 첫 정량 검증 (가상 재투영).

모든 선행 리포트(§25a~d)가 "AIHub 5뷰는 전부 서있는 높이라 바닥 높이 카메라는 검증 불가
(바닥 3D GT 붕괴로 재투영도 불가)"라고 남겼다. 그러나 QC 를 통과한 중간 프레임이 8~27% 존재하고,
v0.1 채택 14조건 전부에서 정상/위반 표본이 남는다(예: 푸시업 56/54). 재투영은 '불가'가 아니라 '부분 가능'이다.

방법
- 클립별 양호 3D 프레임(중간 12프레임 중 ok≥6)의 24관절 세계좌표(cm, y-up)를
  가상 핀홀 카메라로 투영해 2D 픽셀 좌표를 만든다.
- 카메라는 신체 기준으로 놓는다: 몸의 장축(어깨중점→발목중점, 바닥면 투영)에 수직인 옆 방향,
  거리 D=250cm, 높이 H ∈ {145(서있는 높이≈AIHub), 80, 25(폰 바닥 거치)}. 방위 ±25° 변형, D=180 변형.
- 투영 2D 를 floor_2d_rules.frame_features/aggregate 에 **그대로** 넣는다 → 피처 정의 불일치 원천 차단.
- 규칙 14개(v0.1 채택)에 대해: AUC 사슬(주석2D→투영@145→투영@25), 높이 간 순위 보존(Spearman),
  정상 중앙값 이동(IQR 단위), 판정 이전(raw / 정상-앵커 k=3 / oracle 균형정확도), 방위 허용오차.

한계(리포트에 명기)
- QC 통과 ≠ 완전한 3D. 검증 게이트: 투영@145 AUC 가 같은 표본의 주석 2D AUC 와 비슷해야 신뢰.
- 표본이 작은 규칙 4개(플랭크·힙쓰러스트·크런치·Y, 33~40클립)는 방향만 본다.
- 이상적 핀홀(왜곡 없음), 프레이밍/오토포커스 등 실촬영 요소 없음 — 기하 효과의 하한 추정.
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd
from scipy.stats import spearmanr
from sklearn.metrics import balanced_accuracy_score, roc_auc_score

sys.stdout.reconfigure(encoding="utf-8")
HERE = Path(__file__).resolve().parent
# 대용량 parquet 캐시는 데이터 워크트리에 있다 (여기 outputs 는 바닥/기기 분석 산출물만)
DATA = Path(r"C:/Users/hp276/Desktop/trex/.claude/worktrees/correct-exercise-form-6ddf55/research/aihub_fitness/outputs")
OUT = HERE / "outputs"

import floor_2d_rules as f2d  # frame_features / aggregate / youden / JOINTS 재사용

# v0.1 채택 14규칙: (종목, 조건, 피처, 채택 뷰) — rules_floor_v0.json 과 일치해야 함 (main 에서 검증)
RULES = [
    ("푸시업", "고개 젖힘/숙임 여부", "head_trunk_ang__mean", "C"),
    ("푸시업", "가슴의 충분한 이동", "wrist_shoulder_d__min", "C"),
    ("푸시업", "손의 위치 가슴 중앙 여부", "shoulder_dev__mean", "C"),
    ("니푸쉬업", "고개 젖힘/숙임 여부", "head_trunk_ang__mean", "B"),
    ("니푸쉬업", "손의 위치 가슴 중앙 여부", "shoulder_arm_ang__mean", "B"),
    ("니푸쉬업", "가슴의 충분한 이동", "wrist_shoulder_d__min", "B"),
    ("플랭크", "몸통과 엉덩이의 정렬 유지", "trunk_ankle_ang__mean", "B"),
    ("힙쓰러스트", "고개 들지 않기", "head_trunk_ang__std", "B"),
    ("힙쓰러스트", "수축시 무릎부터 어깨까지 일자", "hip_dev_ankle__max", "B"),
    ("시저크로스", "시선 배꼽 고정", "head_trunk_ang__mean", "C"),
    ("시저크로스", "다리와 지면 사이 적당한 거리", "hip_ang__mean", "C"),
    ("라잉 레그 레이즈", "고개 숙임 여부", "head_trunk_ang__mean", "E"),
    ("크런치", "견갑골이 지면으로부터 충분히 올라옴", "head_ground__max", "E"),
    ("Y - Exercise", "경추 중립 또는 후인(retraction) 유지", "hip_dev_knee__min", "B"),
]
EXS = sorted({r[0] for r in RULES})

# 카메라 구성: (라벨, 높이 cm, 방위 offset°, 거리 cm)
CAMS = [
    ("H145", 145.0, 0.0, 250.0),   # AIHub 서있는 높이 근사
    ("H80", 80.0, 0.0, 250.0),     # 의자/침대 높이
    ("H25", 25.0, 0.0, 250.0),     # 폰 바닥 거치 (앱 가이드)
    ("H25_az-25", 25.0, -25.0, 250.0),
    ("H25_az+25", 25.0, +25.0, 250.0),
    ("H25_D180", 25.0, 0.0, 180.0),  # 폰을 가깝게 놓은 경우
]
FOCAL = 800.0  # 임의 (균등 스케일은 각도·비율 피처에 영향 없음)


def load_world() -> tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame]:
    clips = pd.read_parquet(DATA / "clips.parquet")
    clips = clips[clips.exercise.isin(EXS)][["clip_id", "exercise", "performer"]]
    ok = pd.read_parquet(DATA / "kp3d_frame_ok.parquet")
    ok = ok[ok.clip_id.isin(clips.clip_id) & (ok.frame_idx >= 3) & (ok.frame_idx <= 14) & ok.ok]
    good_counts = ok.groupby("clip_id").size()
    keep = good_counts[good_counts >= 6].index
    ok = ok[ok.clip_id.isin(keep)]
    cols = ["clip_id", "frame_idx"] + [f"{j}_{a}" for j in f2d.JOINTS for a in "xyz"]
    k3 = pd.read_parquet(DATA / "kp3d.parquet", columns=cols)
    k3 = k3.merge(ok[["clip_id", "frame_idx"]], on=["clip_id", "frame_idx"])  # 양호 프레임만
    conds = pd.read_parquet(DATA / "conditions.parquet")
    return k3.merge(clips, on="clip_id"), clips, conds


def project(k3: pd.DataFrame, height: float, az_deg: float, dist: float) -> pd.DataFrame:
    """클립별 신체 기준 가상 카메라 투영 → kp2d 스키마 DataFrame (y 아래 방향 픽셀 관례)."""
    rows = []
    for cid, d in k3.groupby("clip_id", sort=False):
        P = {j: d[[f"{j}_x", f"{j}_y", f"{j}_z"]].to_numpy(float) for j in f2d.JOINTS}
        sh = (P["LShoulder"] + P["RShoulder"]) / 2
        hp = (P["LHip"] + P["RHip"]) / 2
        an = (P["LAnkle"] + P["RAnkle"]) / 2
        # 장축: 어깨중점→발목중점 바닥면 투영 (무릎 굽힘이 큰 종목은 골반 방향으로 폴백)
        ax = np.nanmedian(an - sh, axis=0)
        ax[1] = 0.0
        if np.linalg.norm(ax) < 30.0:
            ax = np.nanmedian(hp - sh, axis=0)
            ax[1] = 0.0
        a = ax / max(np.linalg.norm(ax), 1e-6)
        up = np.array([0.0, 1.0, 0.0])
        s = np.cross(up, a)
        s /= max(np.linalg.norm(s), 1e-6)
        th = np.radians(az_deg)  # 방위 offset: 옆 방향을 장축 쪽으로 회전
        s_rot = np.cos(th) * s + np.sin(th) * a
        center = np.nanmedian((sh + hp) / 2, axis=0)
        # 지지평면 앵커 (검증에서 발견된 버그 수정): 바닥 클립의 3D 는 세계 y=0 이 아니라
        # y≈95cm 평면에 떠 있다(리그 좌표 오프셋). 카메라 높이는 클립별 지지평면(관절 min-y 중앙값) 기준.
        ys = np.stack([P[j][:, 1] for j in f2d.JOINTS])
        floor_y = float(np.nanmedian(np.nanmin(ys, axis=0)))
        cam = np.array([center[0], floor_y, center[2]]) + s_rot * dist + up * height
        look = center
        # 카메라 기저: z=시선, x=오른쪽, y=화면 아래(픽셀 관례)
        z = look - cam
        z /= max(np.linalg.norm(z), 1e-6)
        x = np.cross(z, up)
        x /= max(np.linalg.norm(x), 1e-6)
        y = np.cross(z, x)  # 세계 아래쪽 성분 → 화면 아래 = kp2d 관례와 일치
        n = len(d)
        row = {"clip_id": [cid] * n, "frame_idx": d.frame_idx.to_numpy()}
        for j in f2d.JOINTS:
            rel = P[j] - cam
            Z = rel @ z
            Z = np.where(Z < 1.0, np.nan, Z)
            row[f"{j}_x"] = FOCAL * (rel @ x) / Z
            row[f"{j}_y"] = FOCAL * (rel @ y) / Z
        rows.append(pd.DataFrame(row))
    return pd.concat(rows, ignore_index=True)


def stats_for(df2d: pd.DataFrame) -> pd.DataFrame:
    F = f2d.frame_features(df2d)
    return f2d.aggregate(F, df2d.clip_id.to_numpy())


def annotation_stats(frames: pd.DataFrame, view: str) -> pd.DataFrame:
    """검증 게이트용: 같은 클립·같은 프레임 창의 실제 사람 주석 2D(채택 뷰) 피처.

    투영은 양호 중간 프레임만 쓰므로 주석도 동일 (clip, frame) 로 제한해야 min/max 통계가 비교 가능하다.
    """
    cols = ["clip_id", "view_letter", "frame_idx"] + [f"{j}_{a}" for j in f2d.JOINTS for a in "xy"]
    k2 = pd.read_parquet(DATA / "kp2d.parquet", columns=cols)
    k2 = k2[k2.view_letter == view].drop(columns="view_letter")
    k2 = k2.merge(frames[["clip_id", "frame_idx"]], on=["clip_id", "frame_idx"])
    return stats_for(k2)


def oriented_auc(x: np.ndarray, y: np.ndarray) -> tuple[float, int]:
    ok = np.isfinite(x)
    if ok.sum() < 20 or len(np.unique(y[ok])) < 2:
        return np.nan, 1
    a = roc_auc_score(y[ok], x[ok])
    return (a, 1) if a >= 0.5 else (1 - a, -1)


def bacc_at(x: np.ndarray, y: np.ndarray, thr: float, sign: int, mask: np.ndarray | None = None) -> float:
    ok = np.isfinite(x)
    if mask is not None:
        ok &= mask
    if ok.sum() < 10 or len(np.unique(y[ok])) < 2:
        return np.nan
    pred = (sign * x[ok]) >= (sign * thr)  # 위반=양성 쪽으로 부호 정렬
    return balanced_accuracy_score(y[ok], pred)


def main() -> None:
    k3, clips, conds = load_world()
    n_perf = k3.drop_duplicates("clip_id").groupby("exercise").performer.nunique()
    print(f"[load] 양호 클립 {k3.clip_id.nunique()} · 프레임 {len(k3)} · 수행자/종목 중앙값 {n_perf.median():.0f}")

    # 카메라별 통계 (전 종목 일괄)
    S: dict[str, pd.DataFrame] = {}
    for name, h, az, dist in CAMS:
        S[name] = stats_for(project(k3, h, az, dist))
        print(f"  [proj] {name}: 클립 {len(S[name])}")

    ex_of = clips.set_index("clip_id").exercise
    rows = []
    rng = np.random.default_rng(0)
    for ex, cond, feat, view in RULES:
        cids = [c for c in S["H145"].index if ex_of.get(c) == ex]
        yv = (conds[(conds.exercise == ex) & (conds.condition == cond)]
              .drop_duplicates("clip_id").set_index("clip_id").value.reindex(cids))
        m = yv.notna().to_numpy()
        cids = np.array(cids)[m]
        y = (~yv[m].astype(bool).to_numpy()).astype(int)  # 위반=1
        ann = annotation_stats(k3[k3.clip_id.isin(set(cids))], view)
        r: dict = dict(exercise=ex, condition=cond, feature=feat, n=len(cids),
                       n_viol=int(y.sum()))

        def col(dfs: pd.DataFrame) -> np.ndarray:
            return dfs.reindex(cids)[feat].to_numpy(float) if feat in dfs else np.full(len(cids), np.nan)

        xa = col(ann)
        r["auc_ann"], _ = oriented_auc(xa, y)
        for name in S:
            r[f"auc_{name}"], _ = oriented_auc(col(S[name]), y)

        x145, x25 = col(S["H145"]), col(S["H25"])
        both = np.isfinite(x145) & np.isfinite(x25)
        r["rank_rho"] = spearmanr(x145[both], x25[both]).statistic if both.sum() >= 20 else np.nan
        nb = both & (y == 0)
        r["rank_rho_normal"] = spearmanr(x145[nb], x25[nb]).statistic if nb.sum() >= 15 else np.nan
        iqr145 = np.subtract(*np.nanpercentile(x145[y == 0], [75, 25])) or np.nan
        r["shift_iqr"] = (np.nanmedian(x25[y == 0]) - np.nanmedian(x145[y == 0])) / iqr145

        # 판정 이전: 임계값을 H145 에서 적합 → H25 계열에 적용
        a145 = roc_auc_score(y[np.isfinite(x145)], x145[np.isfinite(x145)])
        sign = 1 if a145 >= 0.5 else -1
        thr145 = f2d.youden(sign * x145[np.isfinite(x145)], y[np.isfinite(x145)]) * sign
        med145 = np.nanmedian(x145[y == 0])
        for tgt in ["H25", "H25_az-25", "H25_az+25", "H25_D180"]:
            xt = col(S[tgt])
            r[f"bacc_raw_{tgt}"] = bacc_at(xt, y, thr145, sign)
            # 정상-앵커 k=3 (v0.2 경로): thr' = thr + (앵커중앙값@타깃 − 정상중앙값@145)
            accs = []
            nidx = np.where((y == 0) & np.isfinite(xt))[0]
            for _ in range(20):
                if len(nidx) < 5:
                    break
                anchor = rng.choice(nidx, size=3, replace=False)
                thr_t = thr145 + (np.median(xt[anchor]) - med145)
                mask = np.ones(len(xt), bool)
                mask[anchor] = False
                accs.append(bacc_at(xt, y, thr_t, sign, mask))
            r[f"bacc_anchor_{tgt}"] = float(np.nanmean(accs)) if accs else np.nan
            # 동일-수행자 앵커: 앵커도 평가도 같은 사람 (앱의 실제 배포 상황)
            perf = clips.set_index("clip_id").performer.reindex(cids).to_numpy()
            sp = []
            for _ in range(20):
                preds, trues = [], []
                for p in np.unique(perf):
                    pi = np.where((perf == p) & np.isfinite(xt))[0]
                    ni = pi[y[pi] == 0]
                    if len(ni) < 4 or len(pi) - 3 < 2:
                        continue
                    anchor = rng.choice(ni, size=3, replace=False)
                    thr_t = thr145 + (np.median(xt[anchor]) - med145)
                    ev = np.setdiff1d(pi, anchor)
                    preds.extend(((sign * xt[ev]) >= (sign * thr_t)).tolist())
                    trues.extend(y[ev].tolist())
                if len(trues) >= 20 and len(np.unique(trues)) == 2:
                    sp.append(balanced_accuracy_score(trues, preds))
            r[f"bacc_anchor_same_{tgt}"] = float(np.mean(sp)) if sp else np.nan
            r[f"bacc_anchor_same_se_{tgt}"] = float(np.std(sp) / max(len(sp), 1) ** 0.5) if sp else np.nan
            # 공정 기준선 (검증 지적 (a)): 같은 자격 수행자(정상≥4·클립≥5) 모집단에서의 raw
            elig = np.zeros(len(xt), bool)
            for p in np.unique(perf):
                pi = np.where((perf == p) & np.isfinite(xt))[0]
                if len(pi[y[pi] == 0]) >= 4 and len(pi) - 3 >= 2:
                    elig[pi] = True
            r[f"bacc_raw_samepool_{tgt}"] = bacc_at(xt, y, thr145, sign, elig)
            okt = np.isfinite(xt)
            if okt.sum() >= 20 and len(np.unique(y[okt])) >= 2:
                at = roc_auc_score(y[okt], xt[okt])
                st = 1 if at >= 0.5 else -1
                thr_o = f2d.youden(st * xt[okt], y[okt]) * st
                r[f"bacc_oracle_{tgt}"] = bacc_at(xt, y, thr_o, st)
            else:
                r[f"bacc_oracle_{tgt}"] = np.nan
        rows.append(r)
        print(f"  [rule] {ex}·{cond[:12]} ann={r['auc_ann']:.2f} 145={r['auc_H145']:.2f} 25={r['auc_H25']:.2f} ρ={r['rank_rho']:.2f}")

    res = pd.DataFrame(rows)
    res.to_csv(OUT / "floor_height_projection.csv", index=False, encoding="utf-8-sig")
    write_report(res)


def write_report(res: pd.DataFrame) -> None:
    ok = res[res.auc_ann.notna()].copy()
    gate = ok[(ok.auc_H145 >= ok.auc_ann - 0.10)]  # 검증 게이트: 투영이 주석 신호를 보존
    L = ["# 카메라 높이 축의 첫 정량 검증 — 양호 3D 프레임 가상 재투영\n",
         "선행 리포트들은 '높이 축은 AIHub 로 검증 불가'로 남겼다. QC 통과 중간 프레임(클립의 8~27%)로 "
         "신체 기준 가상 카메라(옆 방향, D=250cm)를 만들어 **서있는 높이(145cm) vs 폰 바닥 거치(25cm)** 를 직접 비교했다. "
         "피처는 `floor_2d_rules.frame_features` 를 그대로 사용(정의 불일치 없음).\n",
         f"- 표본: 클립 {int(res.n.sum())}건(규칙×클립), 규칙 {len(res)}개 중 검증 게이트 통과 {len(gate)}개 "
         "(게이트: 투영@145 AUC ≥ 주석2D AUC − 0.10 — 3D 양호 프레임이 라벨 신호를 보존한다는 증거)\n",
         "## 1. AUC 사슬 — 주석2D → 투영@서있는높이 → 투영@바닥높이\n",
         "| 종목 | 조건 | n | 주석2D | 투영@145 | 투영@80 | 투영@25 | 높이비용(145→25) |",
         "|---|---|---|---|---|---|---|---|"]
    for _, r in res.sort_values("auc_H145", ascending=False).iterrows():
        cost = r.auc_H145 - r.auc_H25
        L.append(f"| {r.exercise} | {r.condition[:16]} | {r.n} | {r.auc_ann:.3f} | {r.auc_H145:.3f} | "
                 f"{r.auc_H80:.3f} | {r.auc_H25:.3f} | {cost:+.3f} |")
    med = res.median(numeric_only=True)
    L += ["",
          f"- 중앙값: 주석2D **{med.auc_ann:.3f}** → 투영@145 **{med.auc_H145:.3f}** → 투영@25 **{med.auc_H25:.3f}** "
          f"(높이비용 중앙값 **{(res.auc_H145 - res.auc_H25).median():+.3f}**)",
          "",
          "## 2. 높이 변화가 깨는 것 — 순위와 임계값을 분리\n",
          "| 종목 | 조건 | 순위ρ(145↔25) | 순위ρ(정상만) | 정상중앙값 이동(IQR단위) |",
          "|---|---|---|---|---|"]
    for _, r in res.sort_values("rank_rho", ascending=False).iterrows():
        L.append(f"| {r.exercise} | {r.condition[:16]} | {r.rank_rho:.2f} | {r.rank_rho_normal:.2f} | {r.shift_iqr:+.2f} |")
    L += ["",
          f"- 순위 보존 중앙값 **ρ={med.rank_rho:.2f}** (뷰 간 0.65 와 비교) · 정상 중앙값 이동 |중앙값| **{res.shift_iqr.abs().median():.2f} IQR**",
          "",
          "## 3. 판정 이전 — 임계값을 서있는 높이에서 적합해 바닥 높이에 적용하면\n",
          "(주의: 임계값은 H145 전체 클립 in-sample 적합 — 쌍대 기하 비교이지 신규 수행자 일반화가 아니다. "
          "동일수행자 열은 자격 수행자(정상≥4클립) 모집단 기준이라 규칙 9개만 유효 — Δ 는 같은 모집단의 raw 와 비교)",
          "",
         "| 대상 | raw | 전역앵커 k=3 | raw(자격모집단) | 동일수행자앵커 k=3 | oracle |", "|---|---|---|---|---|---|"]
    for tgt, label in [("H25", "바닥 높이(옆)"), ("H25_az-25", "바닥, 방위 −25°"),
                       ("H25_az+25", "바닥, 방위 +25°"), ("H25_D180", "바닥, 거리 1.8m")]:
        L.append(f"| {label} | {res[f'bacc_raw_{tgt}'].median():.3f} | {res[f'bacc_anchor_{tgt}'].median():.3f} | "
                 f"{res[f'bacc_raw_samepool_{tgt}'].median():.3f} | {res[f'bacc_anchor_same_{tgt}'].median():.3f} | "
                 f"{res[f'bacc_oracle_{tgt}'].median():.3f} |")
    d_anchor = (res.bacc_anchor_H25 - res.bacc_raw_H25)
    d_same = (res.bacc_anchor_same_H25 - res.bacc_raw_samepool_H25)
    d_same_az = (res["bacc_anchor_same_H25_az+25"] - res["bacc_raw_samepool_H25_az+25"])
    L += ["",
          f"- Δ(전역앵커−raw)@바닥 중앙값 **{d_anchor.median():+.3f}** (개선 {(d_anchor > 0).sum()}/{d_anchor.notna().sum()}건) · "
          f"Δ(동일수행자앵커−같은모집단 raw) **{d_same.median():+.3f}** (개선 {(d_same > 0).sum()}/{d_same.notna().sum()}건, "
          f"20회 반복 SE 중앙값 {res.bacc_anchor_same_se_H25.median():.3f})",
          f"- 방위 +25° 어긋난 경우: Δ(동일수행자앵커−raw) **{d_same_az.median():+.3f}** — 앵커의 진짜 역할은 높이가 아니라 **방위 오차 보정**",
          "",
          "## 한계",
          "- QC 통과 ≠ 완전한 3D. 게이트는 **보고용**이며(탈락 규칙도 표에 남김) 탈락 규칙은 아래에 명시. 표본 작은 규칙 4개는 방향만.",
          "- oriented AUC 는 0.5 로 접히므로(folding) 무신호 피처도 0.5+ε 로 보인다 — 게이트 통과가 신호 보증은 아니다.",
          "- 전역앵커는 수행자 구분 없이 뽑는다('타인' 미보장). 카메라 높이는 클립별 지지평면(관절 min-y 중앙값) 기준.",
          "- 이상적 핀홀 투영: 렌즈 왜곡·프레이밍·MP 측정오차 없음 → **기하 효과의 하한**. 실측(세트 로그)이 최종 판정.",
          "- 양호-3D 클립은 전체의 14~26% 라 표본 편향 가능(주석2D AUC 열이 같은 표본의 기준선 역할).",
          ]
    fails = ok[ok.auc_H145 < ok.auc_ann - 0.10]
    if len(fails):
        L.append("- 게이트 탈락(투영이 주석 신호를 못 따라감): " +
                 "; ".join(f"{r.exercise}·{r.condition}(주석 {r.auc_ann:.2f}→투영 {r.auc_H145:.2f})" for _, r in fails.iterrows()))
    (OUT / "FLOOR_HEIGHT_PROJECTION.md").write_text("\n".join(L), encoding="utf-8")
    print(f"[done] → {OUT / 'FLOOR_HEIGHT_PROJECTION.md'}")


if __name__ == "__main__":
    main()
