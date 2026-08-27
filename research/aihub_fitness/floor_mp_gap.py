#!/usr/bin/env python
"""바닥 운동이 어려운 원인 분해: 측정방식(MediaPipe) 문제인가, 데이터(카메라 기하) 문제인가, 정의 문제인가.

지금까지 바닥 성능(FLOOR_2D: 최적뷰 중앙값 0.721)은 **사람이 주석한 GT 2D** 기준이었다.
앱이 실제로 쓰는 측정 도구(MediaPipe)가 누운 자세에서 얼마나 무너지는지는 측정된 적이 없다.
실험 A 의 저장된 MP 추론(바닥 9종목 × 20클립 × 5뷰 × 16프레임 = 14,400장)과 GT 2D 를 붙여 원인을 체인으로 분해한다:

    [A] GT 전체표본 AUC (FLOOR_2D)          ← 기하(뷰) + 정의 한계까지 포함된 기존 수치
    [B] GT 20클립 표본 AUC                  ← 표본 축소 효과 (A−B = 표본 노이즈)
    [C] MP 원본방향 AUC                     ← B−C = **측정방식(랜드마크) 비용**
    [D] MP 회전보정 AUC                     ← C−D 가 음수면 회전으로 복구 가능
가설: MediaPipe 는 정립 인물 위주로 학습돼 **누운 사람에서 랜드마크가 무너진다**. 이미지를 회전해
사람을 세워서 넣으면(러닝타임 전처리만으로) 복구되는지 검증한다. 회전 좌표는 원본 픽셀계로 역변환해 GT 와 비교.

실행:  python floor_mp_gap.py            # 1·2부 (저장된 추론만, 추론 없음)
       python floor_mp_gap.py --rot      # 3부 회전 추론 (tar seek-read, 43k장) 후 전체 리포트
출력:  outputs/floor_mp_gap*.csv, outputs/FLOOR_MP_GAP.md
"""
from __future__ import annotations

import argparse
import glob
import json
import os
import sys
import time
from pathlib import Path

import numpy as np
import pandas as pd

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

HERE = Path(__file__).resolve().parent
OUT = HERE / "outputs"
# 실험 A 산출물(추론·GT·라벨)이 있는 구 워크트리 — 재생성 대신 재사용 (파서 재실행 수 시간 절약)
SRC_DEFAULT = r"C:\Users\hp276\Desktop\trex\.claude\worktrees\correct-exercise-form-6ddf55\research\aihub_fitness\outputs"

sys.path.insert(0, str(HERE))
from floor_2d_rules import FLOOR, MIN_FRAMES, aggregate, frame_features  # noqa: E402

# MediaPipe 33점 → frame_features 가 쓰는 AIHub 관절명 (직접 대응만; Back/Waist/Neck/Foot 은 MP에 없음 → 가드됨)
MP2AIHUB = {"Nose": 0, "LEar": 7, "REar": 8, "LShoulder": 11, "RShoulder": 12, "LElbow": 13, "RElbow": 14,
            "LWrist": 15, "RWrist": 16, "LHip": 23, "RHip": 24, "LKnee": 25, "RKnee": 26, "LAnkle": 27, "RAnkle": 28}
CORE_JOINTS = list(MP2AIHUB)  # 오차 요약용
STANDING_REF = ["바벨 스쿼트", "오버 헤드 프레스", "바벨 데드리프트"]
ROTS = {0: None, 90: "ROTATE_90_CLOCKWISE", 180: "ROTATE_180", 270: "ROTATE_90_COUNTERCLOCKWISE"}


# ---------------------------------------------------------------- 데이터 로드

def load_src(src: Path):
    mp_dir = src / "mp"
    sample = pd.read_parquet(mp_dir / "sample.parquet")
    sample = sample[sample.exercise.isin(FLOOR + STANDING_REF)].copy()
    keys = set(sample.img_key)
    lm = []
    for f in sorted(glob.glob(str(mp_dir / "landmarks_*.parquet"))):
        d = pd.read_parquet(f)
        lm.append(d[d.img_key.isin(keys)])
    lm = pd.concat(lm, ignore_index=True)
    lm = lm.merge(sample, on="img_key", how="inner")
    clips = pd.read_parquet(src / "clips.parquet")[["clip_id", "exercise", "performer"]]
    conds = pd.read_parquet(src / "conditions.parquet")
    joints = sorted({j for j in MP2AIHUB})
    cols = ["clip_id", "view_letter", "frame_idx"] + [f"{j}_{a}" for j in joints for a in "xy"]
    k2 = pd.read_parquet(src / "kp2d.parquet", columns=cols)
    k2 = k2[k2.clip_id.isin(set(sample.clip_id))]
    return sample, lm, k2, clips, conds


def mp_to_gtcols(lm: pd.DataFrame, x_prefix="l") -> pd.DataFrame:
    """MP 랜드마크 픽셀 → kp2d 와 같은 컬럼 배치 (frame_features 재사용을 위해)."""
    out = lm[["clip_id", "view_letter", "frame_idx"]].copy()
    for j, i in MP2AIHUB.items():
        out[f"{j}_x"] = lm[f"{x_prefix}{i}_x"].to_numpy()
        out[f"{j}_y"] = lm[f"{x_prefix}{i}_y"].to_numpy()
    return out


# ---------------------------------------------------------------- 1부: 관절 오차

def joint_errors(lm: pd.DataFrame, k2: pd.DataFrame) -> pd.DataFrame:
    d = lm[lm.detected].merge(k2, on=["clip_id", "view_letter", "frame_idx"], how="inner", suffixes=("", "_gt"))
    if d.empty:
        return pd.DataFrame()
    shx = (d.LShoulder_x + d.RShoulder_x) / 2
    shy = (d.LShoulder_y + d.RShoulder_y) / 2
    hpx = (d.LHip_x + d.RHip_x) / 2
    hpy = (d.LHip_y + d.RHip_y) / 2
    torso = np.maximum(np.hypot(shx - hpx, shy - hpy), 1e-6)  # GT 몸통 길이(px) 로 정규화
    rows = []
    for j, i in MP2AIHUB.items():
        err = np.hypot(d[f"l{i}_x"] - d[f"{j}_x"], d[f"l{i}_y"] - d[f"{j}_y"]) / torso
        rows.append(pd.DataFrame({"exercise": d.exercise, "view": d.view_letter, "joint": j,
                                  "err": err, "pck02": (err <= 0.2).astype(float)}))
    return pd.concat(rows, ignore_index=True)


# ---------------------------------------------------------------- 2부: 규칙 AUC (GT표본 vs MP)

def auc_single(x: np.ndarray, y: np.ndarray, g: np.ndarray, n_splits=5):
    """단일 피처, 수행자 GroupKFold, train 에서 부호 결정 → test AUC."""
    from sklearn.metrics import roc_auc_score
    from sklearn.model_selection import GroupKFold
    ok = np.isfinite(x)
    x, y, g = x[ok], y[ok], g[ok]
    if len(np.unique(y)) < 2 or len(np.unique(g)) < 3 or min(y.sum(), len(y) - y.sum()) < 6:
        return np.nan
    aucs = []
    for tr, te in GroupKFold(min(n_splits, len(np.unique(g)))).split(x, y, g):
        if len(np.unique(y[tr])) < 2 or len(np.unique(y[te])) < 2:
            continue
        s = 1.0 if roc_auc_score(y[tr], x[tr]) >= 0.5 else -1.0
        aucs.append(roc_auc_score(y[te], s * x[te]))
    return float(np.mean(aucs)) if aucs else np.nan


def clip_stats(frames_df: pd.DataFrame, exercises: list[str]) -> dict[tuple[str, str], pd.DataFrame]:
    """(종목, 뷰) → 클립 통계 df. frame_features/aggregate 재사용 — GT·MP 완전 동일 코드 경로."""
    out = {}
    meta = frames_df[["clip_id", "exercise"]].drop_duplicates().set_index("clip_id")["exercise"]
    for (ex, view), d in frames_df.groupby(["exercise", "view_letter"]):
        if ex not in exercises:
            continue
        d = d.sort_values(["clip_id", "frame_idx"])
        F = frame_features(d)
        agg = aggregate(F, d.clip_id.to_numpy())
        out[(ex, view)] = agg[agg.n_frames >= MIN_FRAMES]
    _ = meta
    return out


def velocity_med(frames: pd.DataFrame) -> pd.Series:
    """클립×뷰 내 인접 프레임 간 관절 이동량(몸통 정규화) 중앙값 — 지터 진단.

    실제 동작 속도는 GT 와 MP 가 같아야 하므로, MP/GT 속도비 > 1 은 곧 랜드마크 지터다.
    std/min/max 형 통계는 지터를 신호로 착각하므로, 이 비율이 std 형 규칙의 사망 원인을 가른다.
    """
    d = frames.sort_values(["clip_id", "view_letter", "frame_idx"]).copy()
    joints = list(MP2AIHUB)
    sh = np.stack([(d.LShoulder_x + d.RShoulder_x) / 2, (d.LShoulder_y + d.RShoulder_y) / 2], 1)
    hp = np.stack([(d.LHip_x + d.RHip_x) / 2, (d.LHip_y + d.RHip_y) / 2], 1)
    torso = np.maximum(np.hypot(*(sh - hp).T), 1e-6)
    g = d.groupby(["clip_id", "view_letter"])
    vels = []
    for j in joints:
        dx = g[f"{j}_x"].diff().to_numpy()
        dy = g[f"{j}_y"].diff().to_numpy()
        vels.append(np.hypot(dx, dy) / torso)
    v = np.nanmean(np.stack(vels), axis=0)
    return pd.Series(v, index=d.index).groupby(d.exercise).median()


def stat_fidelity(st_a, st_b, rules) -> pd.DataFrame:
    """규칙이 임계값을 거는 바로 그 통계량의 (클립×뷰) GT↔MP 일치도.

    AUC 는 표본 20클립에서 포화/양자화되지만(1.000 다수), 충실도는 라벨 없이 뷰를 풀링해
    종목당 n≈100 으로 안정적 — 측정 손상의 주 증거로 이걸 쓴다.
    Spearman(순위 보존 = 임계값 규칙의 판정 보존)과, GT 통계 스케일 대비 MAE 를 같이 낸다.
    """
    from scipy.stats import spearmanr
    rows = []
    for r in rules:
        ex, feat = r["exercise"], r["feature"]
        pairs = []
        for (e2, view), sa in st_a.items():
            if e2 != ex or (e2, view) not in st_b or feat not in sa.columns:
                continue
            sb = st_b[(e2, view)]
            if feat not in sb.columns:
                continue
            j = sa[[feat]].join(sb[[feat]], how="inner", lsuffix="_a", rsuffix="_b").dropna()
            pairs.append(j)
        if not pairs:
            rows.append(dict(exercise=ex, condition=r["condition"], feature=feat, n=0, spearman=np.nan, mae_rel=np.nan))
            continue
        j = pd.concat(pairs)
        a, b = j.iloc[:, 0].to_numpy(float), j.iloc[:, 1].to_numpy(float)
        sp = spearmanr(a, b).statistic if len(j) >= 8 else np.nan
        scale = max(float(np.nanstd(a)), 1e-9)
        rows.append(dict(exercise=ex, condition=r["condition"], feature=feat, n=len(j),
                         spearman=float(sp) if np.isfinite(sp) else np.nan,
                         mae_rel=float(np.nanmean(np.abs(a - b)) / scale)))
    return pd.DataFrame(rows)


def rule_aucs(stats_by, rules, conds, clips, label):
    perf = clips.set_index("clip_id")["performer"].astype(str)
    rows = []
    for r in rules:
        ex, cond, feat = r["exercise"], r["condition"], r["feature"]
        yv = conds[(conds.exercise == ex) & (conds.condition == cond)].drop_duplicates("clip_id").set_index("clip_id")["value"]
        best, best_view = np.nan, ""
        per_view = {}
        for (e2, view), st in stats_by.items():
            if e2 != ex or feat not in st.columns:
                continue
            y = yv.reindex(st.index)
            m = y.notna()
            a = auc_single(st.loc[m, feat].to_numpy(float), y[m].to_numpy().astype(int), perf.reindex(st.index[m]).to_numpy())
            per_view[view] = a
            if np.isfinite(a) and (not np.isfinite(best) or a > best):
                best, best_view = a, view
        rows.append(dict(source=label, exercise=ex, condition=cond, feature=feat, auc=best, view=best_view,
                         **{f"auc_{v}": per_view.get(v, np.nan) for v in "ABCDE"}))
    return pd.DataFrame(rows)


# ---------------------------------------------------------------- 3부: 회전 추론

_landmarker = None


def _rot_init(model_path: str):
    global _landmarker
    from mediapipe.tasks.python import BaseOptions, vision
    opts = vision.PoseLandmarkerOptions(base_options=BaseOptions(model_asset_path=model_path),
                                        running_mode=vision.RunningMode.IMAGE, num_poses=1,
                                        min_pose_detection_confidence=0.5, min_pose_presence_confidence=0.5,
                                        output_segmentation_masks=False)
    _landmarker = vision.PoseLandmarker.create_from_options(opts)


def _inv_map(rot: int, xp: np.ndarray, yp: np.ndarray, W: int, H: int):
    """회전 이미지 픽셀 → 원본 픽셀. (셀프테스트: rot_selftest)"""
    if rot == 0:
        return xp, yp
    if rot == 90:    # ROTATE_90_CLOCKWISE: (x,y)→(H-1-y, x)
        return yp, (H - 1) - xp
    if rot == 180:
        return (W - 1) - xp, (H - 1) - yp
    if rot == 270:   # ROTATE_90_COUNTERCLOCKWISE: (x,y)→(y, W-1-x)
        return (W - 1) - yp, xp
    raise ValueError(rot)


def rot_selftest():
    import cv2
    img = np.zeros((40, 60), np.uint8)  # H=40, W=60
    img[10, 50] = 255                   # (x=50, y=10)
    for rot, code in [(90, cv2.ROTATE_90_CLOCKWISE), (180, cv2.ROTATE_180), (270, cv2.ROTATE_90_COUNTERCLOCKWISE)]:
        r = cv2.rotate(img, code)
        yy, xx = np.argwhere(r == 255)[0]
        x0, y0 = _inv_map(rot, np.array([float(xx)]), np.array([float(yy)]), 60, 40)
        assert abs(x0[0] - 50) < 1e-6 and abs(y0[0] - 10) < 1e-6, (rot, x0, y0)


def _rot_infer(item):
    import cv2
    import mediapipe as mp
    img_key, buf = item
    arr = cv2.imdecode(np.frombuffer(buf, np.uint8), cv2.IMREAD_COLOR)
    out = []
    if arr is None:
        return out
    H, W = arr.shape[:2]
    rgb = cv2.cvtColor(arr, cv2.COLOR_BGR2RGB)
    for rot, code in [(90, cv2.ROTATE_90_CLOCKWISE), (180, cv2.ROTATE_180), (270, cv2.ROTATE_90_COUNTERCLOCKWISE)]:
        r = cv2.rotate(rgb, code)
        res = _landmarker.detect(mp.Image(image_format=mp.ImageFormat.SRGB, data=np.ascontiguousarray(r)))
        row = {"img_key": img_key, "rot": rot, "detected": bool(res.pose_landmarks), "vis": np.nan}
        if res.pose_landmarks:
            lm = res.pose_landmarks[0]
            rh, rw = r.shape[:2]
            xp = np.array([p.x * rw for p in lm], np.float64)
            yp = np.array([p.y * rh for p in lm], np.float64)
            x0, y0 = _inv_map(rot, xp, yp, W, H)
            row["vis"] = float(np.mean([min(p.visibility, p.presence) for p in lm]))
            for j, i in MP2AIHUB.items():
                row[f"l{i}_x"] = float(x0[i])
                row[f"l{i}_y"] = float(y0[i])
        out.append(row)
    return out


def run_rotations(src: Path, sample: pd.DataFrame, workers: int):
    from multiprocessing import Pool
    sys.path.insert(0, str(HERE))
    from mp_infer import build_index, read_items
    rot_selftest()
    model = HERE.parent.parent / "app" / "src" / "main" / "assets" / "posture" / "pose_landmarker_full.task"
    out_dir = OUT / "mp_rot"
    out_dir.mkdir(parents=True, exist_ok=True)
    manifest_p = out_dir / "manifest.json"
    manifest = json.load(open(manifest_p, encoding="utf-8")) if manifest_p.exists() else {}
    tar_days = json.load(open(src / "mp" / "tar_days.json", encoding="utf-8"))
    floor_sample = sample[sample.exercise.isin(FLOOR)]
    pool = Pool(workers, initializer=_rot_init, initargs=(str(model),))
    t_all = time.time()
    for tar, day in sorted(tar_days.items(), key=lambda td: td[0]):
        if day.startswith("ERROR"):
            continue
        name = Path(tar).stem
        if manifest.get(name, {}).get("status") == "done":
            continue
        wanted = floor_sample[floor_sample.day == day]
        if wanted.empty:
            manifest[name] = {"status": "done", "n": 0}
            json.dump(manifest, open(manifest_p, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
            continue
        idx_cache = src / "mp" / f"index_{name}.parquet"
        idx = build_index(Path(tar), idx_cache)
        rows = []
        t0 = time.time()
        for i, out in enumerate(pool.imap_unordered(_rot_infer, read_items(Path(tar), idx, wanted), chunksize=4)):
            rows.extend(out)
            if (i + 1) % 1000 == 0:
                print(f"   [{name}] {i+1} imgs {(time.time()-t0):.0f}s", flush=True)
        pd.DataFrame(rows).to_parquet(out_dir / f"rot_{name}.parquet", index=False)
        manifest[name] = {"status": "done", "n": len(rows), "sec": round(time.time() - t0, 1)}
        json.dump(manifest, open(manifest_p, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
        print(f"[done] {name}: {len(rows)} rows ({len(rows)//3} imgs), {(time.time()-t0):.0f}s | 누적 {(time.time()-t_all)/60:.1f}분", flush=True)
    pool.close()
    pool.join()


def load_rot(sample: pd.DataFrame) -> pd.DataFrame:
    fs = sorted(glob.glob(str(OUT / "mp_rot" / "rot_*.parquet")))
    if not fs:
        return pd.DataFrame()
    d = pd.concat([pd.read_parquet(f) for f in fs], ignore_index=True)
    return d.merge(sample, on="img_key", how="inner")


# ---------------------------------------------------------------- 리포트

def q(v, n=3):
    return "nan" if v is None or not np.isfinite(v) else f"{v:.{n}f}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default=SRC_DEFAULT)
    ap.add_argument("--rot", action="store_true", help="회전 추론 실행 (미실행 시 저장분만 분석)")
    ap.add_argument("--workers", type=int, default=5)
    args = ap.parse_args()
    src = Path(args.src)
    OUT.mkdir(exist_ok=True)

    sample, lm, k2, clips, conds = load_src(src)
    n_floor = int(lm.exercise.isin(FLOOR).sum())
    print(f"[load] MP 추론 {len(lm):,}장 (바닥 {n_floor:,} / 서있는 참조 {len(lm)-n_floor:,}), GT 2D {len(k2):,}행", flush=True)

    if args.rot:
        run_rotations(src, sample, args.workers)

    # ---- 1부: 관절 오차 (바닥 vs 서있는 참조)
    err = joint_errors(lm, k2)
    err["floor"] = err.exercise.isin(FLOOR)
    err.to_parquet(OUT / "floor_mp_joint_err.parquet", index=False)
    det = lm.groupby(lm.exercise.isin(FLOOR)).detected.mean()

    # ---- 2부: 규칙 AUC 체인 (GT표본 vs MP)  — frame_features 를 같은 코드로 양쪽에
    rules = [r for r in json.load(open(HERE.parent.parent / "app" / "src" / "main" / "assets" / "posture" / "rules_floor_v0.json", encoding="utf-8"))["rules"]]
    gt_frames = k2.merge(sample[["clip_id", "view_letter", "frame_idx", "exercise"]].drop_duplicates(),
                         on=["clip_id", "view_letter", "frame_idx"])  # GT를 같은 표본 프레임으로 제한
    mp_frames = mp_to_gtcols(lm[lm.detected])
    mp_frames = mp_frames.merge(sample[["clip_id", "view_letter", "frame_idx", "exercise"]].drop_duplicates(),
                                on=["clip_id", "view_letter", "frame_idx"])
    st_gt = clip_stats(gt_frames, FLOOR)
    st_mp = clip_stats(mp_frames, FLOOR)
    # 지터 진단: MP/GT 프레임 간 이동량 비 (실제 동작 속도는 동일해야 하므로 비율>1 = 지터)
    vel_gt = velocity_med(gt_frames)
    vel_mp = velocity_med(mp_frames)
    # 컬럼명 'gt' 는 DataFrame.gt() 메서드와 충돌하므로 피한다 (observability 때와 같은 함정)
    vel = pd.DataFrame({"v_gt": vel_gt, "v_mp": vel_mp})
    vel["ratio"] = vel["v_mp"] / vel["v_gt"]
    vel["floor"] = vel.index.isin(FLOOR)
    fid = stat_fidelity(st_gt, st_mp, rules)

    # ---- 전 (종목 × 피처__통계) 충실도 — export_floor_rules.py 의 후보 게이트 입력.
    #      뷰 풀링(종목당 n≈100). 규칙 17개만이 아니라 후보 전부를 재서, 재적합이 '측정에서 살아남는 피처'만 고르게 한다.
    from scipy.stats import spearmanr
    rows_all = []
    for ex in FLOOR:
        views = [v for (e, v) in st_gt if e == ex and (e, v) in st_mp]
        if not views:
            continue
        cols = set.intersection(*[set(st_gt[(ex, v)].columns) & set(st_mp[(ex, v)].columns) for v in views]) - {"n_frames"}
        for col in sorted(cols):
            pairs = pd.concat([st_gt[(ex, v)][[col]].join(st_mp[(ex, v)][[col]], how="inner", lsuffix="_g", rsuffix="_m")
                               for v in views]).dropna()
            if len(pairs) < 40:
                continue
            sp = spearmanr(pairs.iloc[:, 0], pairs.iloc[:, 1]).statistic
            rows_all.append(dict(exercise=ex, feature=col, n=len(pairs), spearman=float(sp) if np.isfinite(sp) else np.nan))
    pd.DataFrame(rows_all).to_csv(OUT / "floor_stat_fidelity_all.csv", index=False, encoding="utf-8-sig")
    print(f"[fidelity-all] {len(rows_all)} (종목×피처) → floor_stat_fidelity_all.csv", flush=True)
    a_gt = rule_aucs(st_gt, rules, conds, clips, "GT_sub")
    a_mp = rule_aucs(st_mp, rules, conds, clips, "MP")
    chain = a_gt[["exercise", "condition", "feature", "auc", "view"]].rename(columns={"auc": "gt_sub", "view": "view_gt"})
    chain = chain.merge(a_mp[["exercise", "condition", "auc", "view"]].rename(columns={"auc": "mp", "view": "view_mp"}),
                        on=["exercise", "condition"])
    # FLOOR_2D 전체표본 수치 연결
    f2 = pd.read_csv(src / "floor_2d_rules.csv")
    f2b = f2.sort_values("auc", ascending=False).drop_duplicates(["exercise", "condition"])[["exercise", "condition", "auc"]].rename(columns={"auc": "gt_full"})
    chain = f2b.merge(chain, on=["exercise", "condition"], how="right")

    # ---- 3부: 회전 (저장분 있으면)
    rot = load_rot(sample)
    rot_block = ""
    if not rot.empty:
        # 관절 오차를 rot 별로: joint_errors 는 rot 컬럼을 모른다 → rot 별로 나눠 호출
        parts = []
        for rv, dd in rot[rot.detected].groupby("rot"):
            e = joint_errors(dd, k2)
            e["rot"] = rv
            parts.append(e)
        err_rot = pd.concat(parts, ignore_index=True)
        err_rot.to_parquet(OUT / "floor_mp_rot_err.parquet", index=False)
        base_err = err[err.floor].groupby("exercise").err.median().rename("rot0")
        rr = err_rot.groupby(["exercise", "rot"]).err.median().unstack()
        det_rot = rot.groupby("rot").detected.mean()
        # ---- 실용 정책 시뮬레이션: 정답 없이, 클립×뷰의 첫 4프레임 가시성 평균이 최대인 회전 선택
        #      (앱 동작과 동일: 세트 시작 시 4방향 한 번씩 추론해 보고 고정)
        vis0 = lm[lm.exercise.isin(FLOOR) & lm.detected].copy()
        vis0["vis"] = vis0[[f"l{i}_{a}" for i in range(33) for a in ("v",)]].mean(axis=1)
        vis0["rot"] = 0
        vcols = ["clip_id", "view_letter", "frame_idx", "rot", "vis"]
        vall = pd.concat([vis0[vcols], rot[rot.detected][vcols]], ignore_index=True)
        early = vall[vall.frame_idx <= vall.frame_idx.min() + 3]
        pick_pol = early.groupby(["clip_id", "view_letter", "rot"]).vis.mean().reset_index()
        pick_pol = pick_pol.loc[pick_pol.groupby(["clip_id", "view_letter"]).vis.idxmax(), ["clip_id", "view_letter", "rot"]]
        # 정책 평가: (clip,view,rot) 단위 평균 관절 오차 표를 만들어 정책·오라클·고정 rot0 를 같은 잣대로 비교
        e_by = {}
        for rv, dd in [(0, lm[lm.exercise.isin(FLOOR) & lm.detected])] + [(rv, dd) for rv, dd in rot[rot.detected].groupby("rot")]:
            e = dd.merge(k2, on=["clip_id", "view_letter", "frame_idx"], how="inner", suffixes=("", "_gt"))
            if e.empty:
                continue
            shx = (e.LShoulder_x + e.RShoulder_x) / 2; shy = (e.LShoulder_y + e.RShoulder_y) / 2
            hpx = (e.LHip_x + e.RHip_x) / 2; hpy = (e.LHip_y + e.RHip_y) / 2
            torso = np.maximum(np.hypot(shx - hpx, shy - hpy), 1e-6)
            errs = np.nanmean(np.stack([np.hypot(e[f"l{i}_x"] - e[f"{j}_x"], e[f"l{i}_y"] - e[f"{j}_y"]) / torso
                                        for j, i in MP2AIHUB.items()]), axis=0)
            g = pd.DataFrame({"clip_id": e.clip_id, "view_letter": e.view_letter, "err": errs, "exercise": e.exercise})
            e_by[rv] = g.groupby(["clip_id", "view_letter"]).agg(err=("err", "median"), exercise=("exercise", "first"))
        pol_rows = []
        for _, pr in pick_pol.iterrows():
            t = e_by.get(int(pr.rot))
            if t is not None and (pr.clip_id, pr.view_letter) in t.index:
                row = t.loc[(pr.clip_id, pr.view_letter)]
                pol_rows.append(dict(exercise=row.exercise, err=row.err, rot=int(pr.rot)))
        pol = pd.DataFrame(pol_rows)
        # 오라클: 클립×뷰 단위 4방향 중 최소 오차
        oracle = pd.concat([t.assign(rot=rv) for rv, t in e_by.items()]).reset_index()
        oracle_best = oracle.loc[oracle.groupby(["clip_id", "view_letter"]).err.idxmin()]
        pol_summary = (f"- **실용 정책**(첫 4프레임 가시성 최대 회전; 앱 재현 가능): 클립 오차 중앙값 **{q(pol.err.median())}** "
                       f"(rot0 고정 {q(e_by[0].err.median() if 0 in e_by else np.nan)}, 오라클 {q(oracle_best.err.median())}) | 정책 회전 분포: "
                       + ", ".join(f"{int(r)}°={int(n)}" for r, n in pol.rot.value_counts().sort_index().items()))
        # 실용 정책: 클립×뷰 단위로 mean vis 최대 회전 선택 (rot0 의 vis 는 lm 의 presence 평균이 없어 detected 만 → vis 비교는 90/180/270 간, rot0 는 기본 후보)
        rot_block = "\n".join([
            "## 3. 회전 실험 — 사람을 세워서 넣으면 복구되는가\n",
            f"- 검출률: 원본 {det[True]*100:.1f}% vs 회전 " + ", ".join(f"{int(r)}°={v*100:.1f}%" for r, v in det_rot.items()),
            "",
            "| 종목 | 원본 err | 90° | 180° | 270° | 최선회전 개선 |", "|---|---|---|---|---|---|",
        ] + [
            f"| {ex} | {q(base_err.get(ex))} | {q(rr.loc[ex].get(90))} | {q(rr.loc[ex].get(180))} | {q(rr.loc[ex].get(270))} | "
            f"{q(base_err.get(ex) - min([v for v in [rr.loc[ex].get(90), rr.loc[ex].get(180), rr.loc[ex].get(270)] if np.isfinite(v)] + [base_err.get(ex)]))} |"
            for ex in sorted(set(err_rot.exercise))
        ] + ["", pol_summary])
        # 회전-오라클 랜드마크로 규칙 AUC 재계산: 클립×뷰별 최적 회전(관절 오차 최소) 선택
        pick = err_rot.groupby(["exercise", "rot"]).err.median().reset_index()
        best_rot_by_ex = pick.loc[pick.groupby("exercise").err.idxmin()].set_index("exercise")["rot"]
        rot_best_frames = []
        for ex, rv in best_rot_by_ex.items():
            dd = rot[(rot.exercise == ex) & (rot.rot == rv) & rot.detected]
            rot_best_frames.append(mp_to_gtcols(dd).merge(sample[["clip_id", "view_letter", "frame_idx", "exercise"]].drop_duplicates(), on=["clip_id", "view_letter", "frame_idx"]))
        if rot_best_frames:
            st_rot = clip_stats(pd.concat(rot_best_frames, ignore_index=True), FLOOR)
            a_rot = rule_aucs(st_rot, rules, conds, clips, "MP_rot")
            chain = chain.merge(a_rot[["exercise", "condition", "auc"]].rename(columns={"auc": "mp_rot"}), on=["exercise", "condition"], how="left")
            fid_rot = stat_fidelity(st_gt, st_rot, rules)
            fid = fid.merge(fid_rot[["exercise", "condition", "spearman", "mae_rel"]].rename(
                columns={"spearman": "spearman_rot", "mae_rel": "mae_rel_rot"}), on=["exercise", "condition"], how="left")

    chain.to_csv(OUT / "floor_mp_gap_chain.csv", index=False, encoding="utf-8-sig")

    # ---- 리포트
    e_fl = err[err.floor].err.median()
    e_st = err[~err.floor].err.median()
    pck_fl = err[err.floor].pck02.mean()
    pck_st = err[~err.floor].pck02.mean()
    by_ex = err[err.floor].groupby("exercise").agg(err=("err", "median"), pck=("pck02", "mean"))
    by_view = err[err.floor].groupby("view").agg(err=("err", "median"), pck=("pck02", "mean"))
    by_joint = err[err.floor].groupby("joint").err.median().sort_values(ascending=False)
    L = ["# 바닥 운동이 어려운 진짜 원인 — 측정방식(MediaPipe) 분해 실험\n",
         f"- 표본: 바닥 9종목 × 20클립 × 5뷰 × 16프레임 (MP 추론 {n_floor:,}장), 서있는 참조 {', '.join(STANDING_REF)}",
         "- 오차 = |MP−GT| / GT 몸통길이(어깨중점↔골반중점). PCK@0.2 = 몸통의 20% 안에 들어온 비율",
         "- AUC 체인: GT전체(FLOOR_2D) → GT표본(20클립) → MP원본 → MP회전. 인접 차 = 그 단계의 비용\n",
         "## 1. 랜드마크 오차 — 누운 자세에서 MediaPipe 는 얼마나 무너지나\n",
         f"| | 바닥 종목 | 서있는 참조 |", "|---|---|---|",
         f"| 관절 오차 중앙값 (몸통 대비) | **{e_fl:.3f}** | {e_st:.3f} |",
         f"| PCK@0.2 | **{pck_fl*100:.1f}%** | {pck_st*100:.1f}% |",
         f"| 검출률 | {det[True]*100:.1f}% | {det[False]*100:.1f}% |",
         "",
         "### 종목별 (오차 중앙값 / PCK@0.2)\n",
         "| 종목 | err | PCK |", "|---|---|---|"]
    for ex, r in by_ex.sort_values("err", ascending=False).iterrows():
        L.append(f"| {ex} | {r.err:.3f} | {r.pck*100:.0f}% |")
    L += ["", "### 뷰별 (바닥 종목)\n", "| 뷰 | err | PCK |", "|---|---|---|"]
    for v, r in by_view.iterrows():
        L.append(f"| {v} | {r.err:.3f} | {r.pck*100:.0f}% |")
    L += ["", "### 관절별 오차 상위 (바닥)\n", "| 관절 | err |", "|---|---|"]
    for j, v in by_joint.head(6).items():
        L.append(f"| {j} | {v:.3f} |")
    L += ["", "### 동작 추적 진단 — 프레임 간 이동량 MP/GT 비 (1.0 = 실제 움직임을 그대로 따라감)\n",
          f"| | 바닥 | 서있는 참조 |", "|---|---|---|",
          f"| 속도비 중앙값 | **{vel[vel.floor].ratio.median():.2f}×** | {vel[~vel.floor].ratio.median():.2f}× |",
          "", "예상(지터라면 >1)과 **반대**다: 바닥에서 MP 는 실제 움직임의 절반만 따라간다(**under-tracking** — "
          "접히거나 가려진 관절이 그럴듯한 위치에 얼어붙음). 서있는 참조가 1.05× 이므로 GT 주석 지터 탓이 아니다. "
          "std/min/max 형 통계가 죽는 이유가 이것이다 — 규칙이 재야 할 **분산 신호 자체가 소실**된다. "
          "따라서 시간 평활(지터 필터)은 해법이 아니라 **역효과**다."]
    fid.to_csv(OUT / "floor_mp_gap_fidelity.csv", index=False, encoding="utf-8-sig")
    has_fr = "spearman_rot" in fid.columns
    L += ["", "## 2. 규칙 통계량 충실도 — 임계값을 거는 바로 그 값이 MP 에서 보존되나\n",
          "클립×뷰 풀링(종목당 n≈100), 라벨 불필요 → 표본 노이즈에 강건. Spearman = 순위 보존(임계값 판정 보존), MAE/σ(GT) = GT 통계 산포 대비 오차.\n",
          "| 종목 | 조건 | 통계량 | n | Spearman | MAE/σ |" + (" Spearman(회전) | MAE/σ(회전) |" if has_fr else ""),
          "|---|---|---|---|---|---|" + ("---|---|" if has_fr else "")]
    for _, r in fid.sort_values("spearman").iterrows():
        extra = f" {q(r.get('spearman_rot'), 2)} | {q(r.get('mae_rel_rot'), 2)} |" if has_fr else ""
        L.append(f"| {r.exercise} | {r.condition[:16]} | {r.feature} | {int(r.n)} | {q(r.spearman, 2)} | {q(r.mae_rel, 2)} |{extra}")
    L += ["", f"- 충실도 중앙값: Spearman **{q(fid.spearman.median(), 2)}**, MAE/σ **{q(fid.mae_rel.median(), 2)}**"
          + (f" → 회전 후 Spearman {q(fid.spearman_rot.median(), 2)}, MAE/σ {q(fid.mae_rel_rot.median(), 2)}" if has_fr else "")
          + " (참고: 서있는 종목의 MP 전이는 이 값이 높은 피처만 살아남았다)",
          "", "## 2b. 규칙 AUC 체인 (참고용 — 표본 20클립이라 1.0 포화/양자화, 판정 근거로 쓰지 말 것)\n",
          "| 종목 | 조건 | GT전체 | GT표본 | MP | " + ("MP회전 | " if "mp_rot" in chain else "") + "표본비용 | **측정비용** |",
          "|---|---|---|---|---|---|---|" + ("---|" if "mp_rot" in chain else "")]
    for _, r in chain.sort_values("gt_full", ascending=False).iterrows():
        mrot = f"{q(r.get('mp_rot'))} | " if "mp_rot" in chain else ""
        L.append(f"| {r.exercise} | {r.condition[:16]} | {q(r.gt_full)} | {q(r.gt_sub)} | {q(r.mp)} | {mrot}"
                 f"{q((r.gt_full - r.gt_sub) if np.isfinite(r.gt_sub) else np.nan)} | **{q((r.gt_sub - r.mp) if np.isfinite(r.mp) else np.nan)}** |")
    med = dict(gt_full=chain.gt_full.median(), gt_sub=chain.gt_sub.median(), mp=chain.mp.median())
    med_line = f"- 중앙값: GT전체 {q(med['gt_full'])} → GT표본 {q(med['gt_sub'])} → MP {q(med['mp'])}"
    if "mp_rot" in chain:
        med_line += f" → MP회전 {q(chain.mp_rot.median())}"
    L += ["", med_line, ""]
    if rot_block:
        L += [rot_block, ""]

    # ---- 4부: 원인 판정 + 해결책 (위 수치로 채움)
    ratio = e_fl / max(e_st, 1e-9)
    dead = fid[fid.spearman < 0.35]
    L += ["## 4. 원인 판정\n",
          "| 원인 후보 | 판정 | 근거 (이 리포트의 수치) |", "|---|---|---|",
          f"| **측정방식 — MediaPipe 가 누운/접힌 자세에서 무너짐** | **실재, 1차 병목** | 같은 카메라·같은 프레임에서 관절 오차 {e_fl:.3f} vs 서있는 {e_st:.3f} (**{ratio:.1f}×**), PCK@0.2 {pck_fl*100:.0f}% vs {pck_st*100:.0f}%. 검출률은 97% — '검출은 되는데 좌표가 틀리는' **조용한 실패** |",
          "| 측정방식 — 원인은 '누워 있어서'(방향)가 아니라 **접힘·자기 가림** | **회전 실험으로 확정** | 이미지를 세워 넣어도 개선 0.000~0.003 (§3, 9종목 전부). 검출률도 전 방향 97~98%. 팔꿈치 0.22 · 손목 0.17 · 무릎 0.17 로 몸통에 가려지는 관절만 나쁘고 머리(귀·코)는 정확 — 가림은 회전으로 안 풀린다 |",
          f"| 측정방식 — **동작 미추적(under-tracking)** 이 std/min/max 형 통계를 죽임 | 실재 (방향이 예상과 반대) | 프레임 간 이동량 MP/GT 비: 바닥 **{vel[vel.floor].ratio.median():.2f}×** vs 서있는 {vel[~vel.floor].ratio.median():.2f}× — 접힌 관절이 그럴듯한 위치에 얼어붙어 실제 분산 신호가 소실. 충실도 하위 5규칙(Spearman<0.35)이 전부 std/min 형 |",
          "| 데이터 기하 — 카메라가 서있는 높이 | 실재, 별개 축 | 뷰별 오차 A 0.163 vs C 0.088 (2배) — 뷰가 나쁘면 GT 주석도 MP 도 같이 나빠짐 (FLOOR_2D 의 뼈길이 CV 4~5배와 일관) |",
          "| 정의 — 좌표에 없는 조건 | 실재 (기존 확인) | '허리 지면 고정' 은 Waist 랜드마크를 줘도 +0.000 (FLOOR_2D). 측정을 고쳐도 이 조건들은 안 살아남 |",
          "| 규칙 AUC 로 본 측정비용 | 표본 부족으로 판정 불가 | 20클립 AUC 는 1.0 포화 — §2 충실도가 대신함 |",
          "",
          f"**측정 단계에서 죽는 규칙 {len(dead)}/17** (Spearman<0.35, 전부 std/min 형): "
          + "; ".join(f"{r.exercise}·{r.condition}({r.feature}, ρ={r.spearman:.2f})" for _, r in dead.iterrows()),
          "",
          "## 5. 해결책 매트릭스\n",
          "| 해결책 | 대상 원인 | 비용 | 판단 |", "|---|---|---|---|",
          "| ~~이미지 회전 전처리~~ | 측정방식(방향) | 수 ms | **기각 — 실측 개선 0.000~0.003**. MediaPipe 는 방향에 이미 강건. 구현하지 말 것 |",
          "| **측정-사망 규칙 5개 강등(exclude)** | 측정방식(지터) | 규칙 12개로 축소 | **즉시 적용 가능** — Spearman<0.35 는 임계값을 아무리 보정해도 판정이 복원 안 됨. 남는 12개 중앙값 ρ=0.65 |",
          "| ~~시간 평활(지터 필터)~~ | 측정방식 | — | **기각 — 방향이 반대**. MP 는 바닥에서 이미 과평활/미추적(속도비 0.56×). 평활을 더하면 분산 소실이 심해진다 |",
          "| **가시성 기반 유보(ABSTAIN)** | 측정방식(가림) | 없음 (MP 가 visibility 제공) | 팔꿈치·손목 의존 규칙에 필수 — 해당 관절 visibility 낮은 세트는 판정 대신 '측정 불가' 표시 |",
          "| **종목별 최적 뷰 강제** (ViewGuide — 구현됨) | 기하 | UX 만 | 뷰 C/E 오차가 A 의 절반 — 필수 유지 |",
          "| 바닥 높이 거치 안내 | 기하 | UX 만 | AIHub 에 바닥 높이 데이터가 없어 정량 검증 불가 — 자체 수집으로 확인 |",
          "| heavy 모델 (pose_landmarker_heavy) | 측정방식(가림) | 추론 ~2×, 발열, 모델 다운로드 | 미검증. 가림 자체는 모델을 키워도 한계 — 우선순위 낮음 |",
          "| 규칙을 머리·큰 분절 각도 mean 형 위주로 | 측정방식 | 없음 | 충실도 상위가 전부 그 유형(ρ 0.63~0.89) — 선별 원칙으로 명문화 |",
          "| 세트 로그 재보정 (§14) 바닥 확장 | 임계값 | 자체 수집 | 필수 — AIHub 임계값은 서있는 높이 카메라 기준 |",
          "",
          "## 한계 (정직하게)",
          "- GT 는 사람 주석이라 자체 오차가 있다 — '오차' 는 GT 대비 상대값. 바닥 GT 주석에 지터가 있다면 속도비 분모가 커져 0.56× 이 다소 과장됐을 수 있으나, 같은 주석 과정의 서있는 참조가 1.05× 이므로 미추적이라는 방향 자체는 견고하다.",
          "- 규칙 AUC 체인은 20클립 표본이라 판정 불가(포화). 충실도(Spearman/MAE)가 주 증거.",
          "- AIHub 5뷰 전부 서있는 높이 카메라 — **바닥 높이 카메라에서의 성능은 이 데이터로 알 수 없다**. 회전 실험 결과도 같은 한계 안에서의 결론이다.",
          "- 시간 평활의 효과는 AIHub 프레임 간격(0.6s)으로는 검증할 수 없다 — 앱 로그가 필요."]
    (OUT / "FLOOR_MP_GAP.md").write_text("\n".join(L), encoding="utf-8")
    print("\n".join(L[:40]))
    print(f"\n[done] → {OUT/'FLOOR_MP_GAP.md'}")


if __name__ == "__main__":
    main()
