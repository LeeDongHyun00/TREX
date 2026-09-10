#!/usr/bin/env python
"""촬영 뷰 추정기 — MP 월드 랜드마크만으로 "카메라가 몸의 어느 쪽에 있나"를 맞힌다 (spec §33).

왜: 규칙 JSON 의 view_best_front(B/C/D)·mirror_safe·"valgus 는 정면(C) 뷰에서만" caution 은 전부 **사용자가 안내대로 폰을 놓았다는 가정** 위에 있다.
    앱에는 그 가정을 확인하는 코드가 없어서, 폰이 뒤·옆·반대편에 있어도 규칙이 그대로 점수에 들어간다(§32 감사).
어떻게: MediaPipe 월드 좌표는 카메라 정렬 좌표계다(x 오른쪽, y 위, z 카메라 쪽 — 앱과 연구가 같은 뒤집기). 그래서
    어깨선·골반선의 x–z 평면 방향이 곧 몸의 요(yaw)다.
      s = RShoulder − LShoulder, h = RHip − LHip  (x, z 성분)
      u = unit(−s.x, s.z) + unit(−h.x, h.z)       (한쪽이 없으면 나머지만)
      yaw = atan2(u.z, u.x)  → 정면 0°, 후방 ±180°, 부호가 좌/우
    프레임마다 cos/sin 을 내고 창 평균의 atan2 로 세트 요를 잡는다(원형 평균). 결과 벡터 길이 R 이 일관성이다.
정답: AIHub 카메라 코드(C 정면, B/D 전방사선 ±40°, A/E 후방사선)는 **수행자가 명목 정면을 본다는 가정**이다. 실제로는 케이블 종목 3개가
    100% 기구를 향해 카메라 C 를 등지고, 로잉머신 50%·덤벨 풀 오버 41% 도 어긋난다(GT 2D 어깨 순서로 실측). 그래서 정답은
    카메라 코드가 아니라 **GT 2D 어깨 순서로 보정한 실제 방향**이다: 명목과 어긋난 클립은 C→R(순수 후방), B→E, D→A, A→D, E→B.
    순수 측면 뷰는 데이터에 없다 → SIDE 등급은 **미검증**.

출력: VIEW_ESTIMATOR.md, outputs/view_estimator.csv (clip,view 별 yaw·R·예측 등급·보정 정답), outputs/view_thresholds.json,
      app/src/test/resources/view_fixture.txt (Kotlin ViewEstimator 파리티: 프레임 관절 + 기대 yaw/cos/sin/등급)
"""
from __future__ import annotations

import json
import sys
import warnings
from pathlib import Path

import numpy as np
import pandas as pd

from experiment_a import build_mp_arrays, load_landmarks
from features import J

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
warnings.filterwarnings("ignore")

HERE = Path(__file__).resolve().parent
OUT = HERE / "outputs"
MP = OUT / "mp"
FIXTURE = HERE.parent.parent / "app" / "src" / "test" / "resources" / "view_fixture.txt"
FLOOR = {"푸시업", "니푸쉬업", "플랭크", "크런치", "라잉 레그 레이즈", "힙쓰러스트", "시저크로스", "Y - Exercise", "바이시클 크런치"}
# 벤치에 눕거나 기대는 종목 — 서서 하는 종목과 기하가 다르므로 임계값 유도에서 뺀다 (앱 노출 종목 없음)
BENCH = {"라잉 트라이셉스 익스텐션", "덤벨 체스트 플라이", "덤벨 인클라인 체스트 플라이", "덤벨 풀 오버"}
SEATED = {"로잉머신"}
CLASSES = ["C", "B", "D", "SIDE_B", "SIDE_D", "A", "E", "R", "UNKNOWN"]
CLASS_CODE = {c: i for i, c in enumerate(CLASSES)}
MIN_R = 0.7        # 원형 평균 결과 벡터 길이가 이보다 작으면 UNKNOWN (프레임 간 방향이 일관되지 않음)
FLIP = {"C": "R", "B": "E", "D": "A", "A": "D", "E": "B"}   # 수행자가 명목 정면을 등졌을 때 카메라 코드의 실제 뜻
SEED = 0


def frame_yaw(P: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """P: (N, T, 24, 3) → (yaw_deg (N,T), n_lines (N,T)). Kotlin ViewEstimator.frameYaw 와 같은 정의."""
    def line(l: str, r: str):
        d = P[:, :, J[r], :] - P[:, :, J[l], :]
        x, z = -d[..., 0], d[..., 2]
        n = np.hypot(x, z)
        with np.errstate(invalid="ignore", divide="ignore"):
            ux, uz = x / n, z / n
        ok = np.isfinite(ux) & np.isfinite(uz) & (n > 1e-3)
        return np.where(ok, ux, 0.0), np.where(ok, uz, 0.0), ok
    sx, sz, so = line("LShoulder", "RShoulder")
    hx, hz, ho = line("LHip", "RHip")
    ux, uz = sx + hx, sz + hz
    n_lines = so.astype(int) + ho.astype(int)
    yaw = np.degrees(np.arctan2(uz, ux))
    yaw[n_lines == 0] = np.nan
    return yaw, n_lines


def circ_mean(yaw_deg: np.ndarray) -> tuple[float, float]:
    """(평균 각도, 결과 벡터 길이 R)."""
    v = yaw_deg[np.isfinite(yaw_deg)]
    if len(v) == 0:
        return np.nan, 0.0
    r = np.radians(v)
    c, s = np.cos(r).mean(), np.sin(r).mean()
    return float(np.degrees(np.arctan2(s, c))), float(np.hypot(c, s))


def classify(yaw: float, thr: dict, r: float = 1.0) -> str:
    """등급: C(정면) / B·D(전방 사선) / SIDE_B·SIDE_D(측면, 미검증) / A·E(후방 사선) / R(순수 후방). Kotlin ViewEstimator.classify 와 동일."""
    if not np.isfinite(yaw) or r < thr["min_r"]:
        return "UNKNOWN"
    a = abs(yaw)
    b_side = (yaw > 0) == (thr["b_sign"] > 0)   # yaw 부호가 B 쪽이면 B / SIDE_B / rear_left
    if a <= thr["front_max"]:
        return "C"
    if a <= thr["oblique_max"]:
        return "B" if b_side else "D"
    if a < thr["rear_min"]:
        return "SIDE_B" if b_side else "SIDE_D"
    if a < thr["rear_pure_min"]:
        return thr["rear_left"] if b_side else thr["rear_right"]
    return "R"


def load_gt_facing(clip_ids: set) -> pd.Series:
    """GT 2D 어깨 순서로 (clip, view) 별 '카메라를 마주보는가'. 오른어깨가 화면 왼쪽(x 작음)이면 마주봄."""
    cols = json.load(open(OUT / "joints.json", encoding="utf-8"))["kp2d_cols"]
    lsx = [c for c in cols if c.startswith("LShoulder") and c.endswith("x")][0]
    rsx = [c for c in cols if c.startswith("RShoulder") and c.endswith("x")][0]
    k2 = pd.read_parquet(OUT / "kp2d.parquet", columns=["clip_id", "view_letter", lsx, rsx])
    k2 = k2[k2.clip_id.isin(clip_ids)]
    dx = (k2[rsx] - k2[lsx]).rename("dx")
    return pd.concat([k2[["clip_id", "view_letter"]], dx], axis=1).groupby(["clip_id", "view_letter"])["dx"].median() < 0


def main():
    tag = sys.argv[sys.argv.index("--tag") + 1] if "--tag" in sys.argv else "direct"
    lm = load_landmarks()
    sample = pd.read_parquet(MP / "sample.parquet")
    keys, arr, flipped = build_mp_arrays(lm, sample, tag)
    clips = pd.read_parquet(OUT / "clips.parquet").set_index("clip_id")
    keys["exercise"] = clips.reindex(keys["clip_id"])["exercise"].to_numpy()
    keys["performer"] = clips.reindex(keys["clip_id"])["performer"].astype(str).to_numpy()
    keys["group"] = np.select([keys.exercise.isin(FLOOR), keys.exercise.isin(BENCH), keys.exercise.isin(SEATED)], ["floor", "bench", "seated"], "stand")
    print(f"[3D] (클립,뷰) {len(keys):,}개, y반전={flipped}, 매핑={tag}", flush=True)

    yaw, n_lines = frame_yaw(arr)                       # (K, T)
    rows = []
    for i in range(len(keys)):
        m, r = circ_mean(yaw[i])
        rows.append(dict(clip_id=keys.clip_id[i], view=keys.view_letter[i], exercise=keys.exercise[i], group=keys.group[i],
                         performer=keys.performer[i], yaw=m, R=r, n_frames=int(np.isfinite(yaw[i]).sum()),
                         both_lines=float(np.nanmean(n_lines[i] == 2)) if np.isfinite(yaw[i]).any() else np.nan))
    df = pd.DataFrame(rows)
    facing = load_gt_facing(set(df.clip_id))
    df["gt_facing"] = facing.reindex(pd.MultiIndex.from_arrays([df.clip_id, df.view])).to_numpy()
    df["nominal_front"] = df.view.isin(["B", "C", "D"])
    df["consistent"] = df.gt_facing == df.nominal_front
    df["gt_letter"] = np.where(df.consistent, df.view, df.view.map(FLIP))
    df.loc[df.gt_facing.isna(), "gt_letter"] = None

    # ---- 임계값: 서서 하는 종목 중 카메라 코드와 실제 방향이 일치하는 클립의 뷰별 분포에서 ----
    st = df[(df.group == "stand") & df.yaw.notna() & (df.consistent == True)]
    med = st.groupby("view")["yaw"].median()
    b_sign = int(np.sign(med["B"]))
    obl = float((abs(med["B"]) + abs(med["D"])) / 2)
    rear = float((abs(med["A"]) + abs(med["E"])) / 2)
    ab = st[st.view.isin(["B", "D"])]["yaw"].abs()
    ae = st[st.view.isin(["A", "E"])]["yaw"].abs()
    front_max = round(float(obl / 2), 1)
    oblique_max = round(float(min(ab.quantile(0.975), (obl + rear) / 2)), 1)
    rear_min = round(float(max(ae.quantile(0.025), (obl + rear) / 2)), 1)
    if oblique_max >= rear_min:
        oblique_max = rear_min = round((obl + rear) / 2, 1)
    thr = dict(
        b_sign=b_sign, front_max=front_max, oblique_max=oblique_max, rear_min=rear_min,
        rear_pure_min=round(180.0 - front_max, 1),
        rear_left=("A" if int(np.sign(med["A"])) == b_sign else "E"),
        rear_right=("E" if int(np.sign(med["A"])) == b_sign else "A"),
        min_r=MIN_R,
        centers=dict(C=round(float(med["C"]), 2), B=round(float(med["B"]), 2), D=round(float(med["D"]), 2), A=round(float(med["A"]), 2), E=round(float(med["E"]), 2)),
    )
    df["pred"] = [classify(y, thr, r) for y, r in zip(df.yaw, df.R)]
    df.to_csv(OUT / "view_estimator.csv", index=False, encoding="utf-8-sig")
    (OUT / "view_thresholds.json").write_text(json.dumps(thr, ensure_ascii=False, indent=1), encoding="utf-8")

    write_report(df, st, thr, yaw, keys, flipped, tag)
    write_fixture(keys, arr, yaw, thr)


FAMILY = {"C": "C", "B": "B", "D": "D", "A": "REAR", "E": "REAR", "R": "REAR", "SIDE_B": "SIDE", "SIDE_D": "SIDE", "UNKNOWN": "UNKNOWN"}
HEMI = {"C": "F", "B": "F", "D": "F", "A": "B", "E": "B", "R": "B", "SIDE_B": "S", "SIDE_D": "S", "UNKNOWN": "U"}


def confusion(d: pd.DataFrame) -> pd.DataFrame:
    ct = pd.crosstab(d["gt_letter"], d["pred"]).reindex(index=["C", "B", "D", "A", "E", "R"], columns=CLASSES, fill_value=0)
    return ct.loc[(ct != 0).any(axis=1), (ct != 0).any(axis=0)]


def acc_block(d: pd.DataFrame) -> str:
    d = d[d.gt_letter.notna()]
    if d.empty:
        return "(표본 없음)"
    known = d[d.pred != "UNKNOWN"]
    a5 = float((known.pred == known.gt_letter).mean())
    fam = float((known.pred.map(FAMILY) == known.gt_letter.map(FAMILY)).mean())
    hemi = float((known.pred.map(HEMI) == known.gt_letter.map(HEMI)).mean())
    return f"클립 {len(d)} · UNKNOWN {int((d.pred=='UNKNOWN').sum())} · (UNKNOWN 제외) 등급 정확도 **{a5:.3f}** · 계열(C/B/D/후방) **{fam:.3f}** · 전/후 반구 **{hemi:.3f}**"


def write_report(df, st, thr, yaw, keys, flipped, tag):
    L = ["# 촬영 뷰 추정기 — MP 월드 랜드마크의 어깨선·골반선 요(yaw) (spec §33)\n",
         f"- 표본: (클립,뷰) {len(df):,}개 — 서서 {int((df.group=='stand').sum()):,} · 벤치 {int((df.group=='bench').sum()):,} · 앉음 {int((df.group=='seated').sum()):,} · 바닥 {int((df.group=='floor').sum()):,}. L/R 매핑 {tag}, y반전 {flipped}",
         "- 정의: `yaw = atan2(u.z, u.x)`, `u = unit(−(RSh−LSh).x, (RSh−LSh).z) + unit(−(RHip−LHip).x, (RHip−LHip).z)`. 정면 0°, 후방 ±180°. 클립 값은 프레임 cos/sin 평균의 atan2(원형 평균), R = 결과 벡터 길이.",
         "- **정답은 카메라 코드가 아니다.** 서서 종목 클립의 13.7% 에서 수행자가 명목 정면을 등지고 있었다(GT 2D 어깨 순서 실측). 어긋난 클립은 C→R, B→E, D→A, A→D, E→B 로 보정한 것이 정답. 순수 측면 뷰는 데이터에 없어 SIDE 등급은 **미검증**.\n",
         "## 1. 서서 하는 종목(명목 방향과 일치하는 클립) — 뷰별 클립 요 분포\n", "| 뷰 | n | 중앙값 | p5 | p25 | p75 | p95 | R 중앙값 |", "|---|---|---|---|---|---|---|---|"]
    for v in "CBDAE":
        g = st[st.view == v]
        L.append(f"| {v} | {len(g)} | {g.yaw.median():.1f}° | {g.yaw.quantile(.05):.1f} | {g.yaw.quantile(.25):.1f} | {g.yaw.quantile(.75):.1f} | {g.yaw.quantile(.95):.1f} | {g.R.median():.3f} |")
    L += ["", "## 2. 임계값 (위 분포에서 유도)\n",
          f"- B 쪽 부호 = {'+' if thr['b_sign'] > 0 else '−'} (yaw 가 {'양' if thr['b_sign']>0 else '음'}수면 B · SIDE_B · {thr['rear_left']})",
          f"- |yaw| ≤ {thr['front_max']}° → **C** · ≤ {thr['oblique_max']}° → **B/D** · < {thr['rear_min']}° → SIDE(미검증) · < {thr['rear_pure_min']}° → **{thr['rear_left']}/{thr['rear_right']}** · 그 이상 → **R**(순수 후방)",
          f"- R < {thr['min_r']} 이면 UNKNOWN (프레임 간 방향 불일치). MP 는 ±40° 를 약 ±{abs(thr['centers']['B']):.0f}° 로 압축해 읽는다(깊이 축 과소추정).", ""]
    for title, d in (("## 3. 서서 종목 — 보정 정답 vs 예측 (클립, 원형 평균)", df[df.group == "stand"]),
                     ("## 4. 벤치 종목 (라잉 트라이셉스·체스트 플라이 2종·덤벨 풀 오버) — 참고, 임계값 유도에서 제외", df[df.group == "bench"]),
                     ("## 5. 앉은 종목 (로잉머신) — 참고", df[df.group == "seated"]),
                     ("## 6. 바닥 종목 — 참고 (누운 자세라 뷰 코드의 뜻이 다름, 앱은 바닥 경로 별도)", df[df.group == "floor"])):
        ct = confusion(d)
        L += [title + "\n", acc_block(d) + "\n"]
        if len(ct):
            L += ["| 정답 \\ 예측 | " + " | ".join(ct.columns) + " |", "|---|" + "---|" * len(ct.columns)]
            for v, row in ct.iterrows():
                L.append(f"| {v} | " + " | ".join(str(int(x)) for x in row) + " |")
        L.append("")
    stand_idx = np.flatnonzero((keys.group == "stand").to_numpy())
    gl = df["gt_letter"].to_numpy()
    fr_true, fr_pred = [], []
    for i in stand_idx:
        if gl[i] is None or (isinstance(gl[i], float) and np.isnan(gl[i])):
            continue
        for v in yaw[i]:
            if np.isfinite(v):
                fr_true.append(gl[i]); fr_pred.append(classify(v, thr))
    fr = pd.DataFrame(dict(gt_letter=fr_true, pred=fr_pred))
    L += ["## 7. 프레임 단위 (서서, 평균 없이 한 프레임만 봤을 때)\n", acc_block(fr) + " — 창 평균이 필요한 이유\n"]
    L += ["## 8. 종목별 — 명목 방향을 등진 비율과 정확도 (서서·벤치·앉음)\n", "| 종목 | 그룹 | n | 등진 비율 | 등급 정확도 | 반구 정확도 |", "|---|---|---|---|---|---|"]
    for ex, g in df[df.group.isin(["stand", "bench", "seated"]) & df.gt_letter.notna()].groupby("exercise"):
        k = g[g.pred != "UNKNOWN"]
        L.append(f"| {ex} | {g.group.iloc[0]} | {len(g)} | {float((~g.consistent).mean()):.2f} | {float((k.pred==k.gt_letter).mean()):.2f} | {float((k.pred.map(HEMI)==k.gt_letter.map(HEMI)).mean()):.2f} |")
    L += ["", "## 9. Kotlin 상수 (PostureView.kt)\n", "```", json.dumps({k: v for k, v in thr.items() if k != "centers"}, ensure_ascii=False), "```",
          "- 파리티 픽스처: `app/src/test/resources/view_fixture.txt` (프레임 관절 → yaw/cos/sin/등급 코드). 등급 코드: " + json.dumps(CLASS_CODE)]
    (HERE / "VIEW_ESTIMATOR.md").write_text("\n".join(L), encoding="utf-8")
    print("\n".join(L[:34]))


def write_fixture(keys, arr, yaw, thr):
    rng = np.random.default_rng(SEED)
    lines = ["# view estimator parity fixture — 자동 생성(view_estimator.py), 수정 금지",
             "# CASE <clip> <view> <frame> / J <joint> x y z / F view_yaw_deg|view_cos|view_sin|view_class value / END",
             f"# class codes: {json.dumps(CLASS_CODE)} ; thresholds: {json.dumps({k: v for k, v in thr.items() if k != 'centers'}, ensure_ascii=False)}"]
    names = list(J.keys())
    n_case = 0
    for v in "ABCDE":
        idx = np.flatnonzero(((keys.view_letter == v) & (keys.group == "stand")).to_numpy())
        rng.shuffle(idx)
        picked = 0
        for i in idx:
            frames = np.flatnonzero(np.isfinite(yaw[i]))
            if len(frames) == 0:
                continue
            t = int(rng.choice(frames))
            P = arr[i, t]
            if not np.isfinite(P[[J["LShoulder"], J["RShoulder"]]]).all():
                continue
            lines.append(f"CASE {keys.clip_id[i]} {v} {t}")
            for j, name in enumerate(names):
                if np.isfinite(P[j]).all():
                    lines.append(f"J {name} {P[j,0]:.5f} {P[j,1]:.5f} {P[j,2]:.5f}")
            y = float(yaw[i, t])
            lines += [f"F view_yaw_deg {y:.4f}", f"F view_cos {np.cos(np.radians(y)):.6f}", f"F view_sin {np.sin(np.radians(y)):.6f}",
                      f"F view_class {CLASS_CODE[classify(y, thr)]}", "END"]
            n_case += 1
            picked += 1
            if picked >= 24:
                break
    FIXTURE.write_text("\n".join(lines), encoding="utf-8")
    print(f"[fixture] {n_case} cases → {FIXTURE}")


if __name__ == "__main__":
    main()
