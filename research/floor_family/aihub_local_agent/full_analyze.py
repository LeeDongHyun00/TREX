# -*- coding: utf-8 -*-
"""전 클립 MP(C·E) → 프레임 피처(+앱 플랭크 기하, 앱 패리티 head_ground/hip_ang) → 클립 통계 → 조건별 분리 출력."""
import sys, glob
from pathlib import Path
import numpy as np, pandas as pd
import pyarrow.dataset as ds
from sklearn.metrics import roc_auc_score

REPO = Path(r"C:/Users/hp276/Desktop/trex/.claude/worktrees/exercise-form-correction-5fe80e/research/aihub_fitness")
sys.path.insert(0, str(REPO))
import export_floor_rules as ef  # noqa
from plank_reference_features import features as plank_feats  # noqa
from gt_features import features, OUT, EX, MAP, n_cycles

S = Path(__file__).resolve().parent


def build():
    clips = pd.read_parquet(OUT / "clips.parquet", columns=["clip_id", "exercise", "performer", "type_key"])
    clips = clips[clips.exercise.isin(EX)]
    k = ds.dataset(OUT / "kp2d.parquet").to_table(columns=["clip_id", "frame_idx", "view_letter", "img_key"],
                                                  filter=ds.field("clip_id").isin(list(clips.clip_id))).to_pandas()
    lm = pd.concat([pd.read_parquet(f) for f in glob.glob(str(S / "mp_full" / "landmarks_*.parquet"))], ignore_index=True)
    m = k.merge(lm, on="img_key").merge(clips, on="clip_id").sort_values(["clip_id", "view_letter", "frame_idx"]).reset_index(drop=True)
    df = pd.DataFrame({"clip_id": m.clip_id, "view_letter": m.view_letter, "frame_idx": m.frame_idx})
    for j in ["Nose", "LEar", "REar", "LShoulder", "RShoulder", "LElbow", "RElbow", "LWrist", "RWrist", "LHip", "RHip", "LKnee", "RKnee", "LAnkle", "RAnkle", "LFoot", "RFoot", "Waist", "Back", "Neck"]:
        for a in "xy":
            df[f"{j}_{a}"] = np.where(m.detected, m[f"l{MAP[j]}_{a}"], np.nan) if j in MAP else np.nan
    fr = pd.concat([m[["clip_id", "view_letter", "frame_idx", "exercise", "performer", "type_key", "detected"]], pd.DataFrame(features(df))], axis=1)
    P = []
    xs = m[[f"l{i}_x" for i in range(33)]].to_numpy(); ys = m[[f"l{i}_y" for i in range(33)]].to_numpy(); vs = m[[f"l{i}_v" for i in range(33)]].to_numpy()
    for i, r in enumerate(m[["detected", "exercise", "w", "h"]].itertuples(index=False)):
        o = {}
        if r.detected and r.exercise == "플랭크":
            xy = np.empty(66); xy[0::2] = xs[i] / r.w; xy[1::2] = ys[i] / r.h
            o = plank_feats({"xy": xy.tolist(), "visibility": vs[i].tolist(), "w": r.w, "h": r.h})
        P.append(o)
    P = pd.DataFrame(P)
    for c in ("plank_hip_offset", "plank_head_pitch", "plank_neck_pitch"):
        fr[c] = P[c].values if c in P else np.nan
    for c in ("app_head_ground", "app_hip_ang", "app_knee_ang"):
        fr[c] = np.nan
    for (cid, v), idx in fr.groupby(["clip_id", "view_letter"]).groups.items():
        idx = np.asarray(idx)
        ok = fr.loc[idx, "detected"].to_numpy()
        if ok.sum() < 8:
            continue
        d = df.loc[idx[ok]]
        frames = np.stack([d[[f"{j}_x", f"{j}_y"]].to_numpy(float) for j in ef.JOINTS], axis=1)
        Fs = ef.frame_features_stream(frames, ef.JOINTS)
        fr.loc[idx[ok], "app_head_ground"] = Fs["head_ground"]; fr.loc[idx[ok], "app_hip_ang"] = Fs["hip_ang"]; fr.loc[idx[ok], "app_knee_ang"] = Fs["knee_ang"]
    feats = ["cr_trunk_lift", "cr_head_lift", "cr_sh_ground", "cr_ear_ground", "cr_neck_flex", "lr_leg_elev", "lr_hip_ang", "lr_knee_ang",
             "lr_ankle_h", "lr_head_lift", "lr_head_trunk", "app_head_ground", "app_hip_ang", "app_knee_ang",
             "plank_hip_offset", "plank_head_pitch", "plank_neck_pitch", "pl_hip_off"]
    rows = []
    for (cid, v), d in fr.groupby(["clip_id", "view_letter"]):
        r = dict(clip_id=cid, view=v, exercise=d.exercise.iloc[0], performer=d.performer.iloc[0], type_key=d.type_key.iloc[0], n=len(d), det=d.detected.mean())
        for f in feats:
            x = d[f].to_numpy(float); x = x[np.isfinite(x)]
            r[f + "__n"] = len(x)
            if len(x) < (3 if f.startswith("plank") else 8):
                continue
            r[f + "__med"] = np.median(x); r[f + "__min"] = x.min(); r[f + "__max"] = x.max()
            r[f + "__p10"] = np.quantile(x, .1); r[f + "__p90"] = np.quantile(x, .9); r[f + "__rr"] = r[f + "__p90"] - r[f + "__p10"]; r[f + "__rng"] = x.max() - x.min()
        r["cyc_trunk"] = n_cycles(d.cr_trunk_lift); r["cyc_hg"] = n_cycles(d.app_head_ground); r["cyc_hip"] = n_cycles(-d.app_hip_ang)
        rows.append(r)
    lab = pd.read_parquet(OUT / "conditions.parquet").pivot_table(index="clip_id", columns="condition", values="value", aggfunc="first")
    c = pd.DataFrame(rows).join(lab, on="clip_id")
    fr = fr.join(lab, on="clip_id")
    fr.to_parquet(S / "mpfull_frames.parquet", index=False); c.to_parquet(S / "mpfull_clipview.parquet", index=False)
    return fr, c


if __name__ == "__main__":
    fr, c = build()
    print("이미지", len(fr), "검출률", fr.groupby(["exercise", "view_letter"]).detected.mean().round(3).to_dict())
    print(c.groupby(["exercise", "view"]).size().to_dict())
