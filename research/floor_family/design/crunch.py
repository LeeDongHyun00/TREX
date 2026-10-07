# -*- coding: utf-8 -*-
import numpy as np, pandas as pd
from sklearn.metrics import roc_auc_score
F = pd.read_parquet("near_frames.parquet")
c = F[F.exercise == "크런치"].copy()
c["scap"] = c["견갑골이 지면으로부터 충분히 올라옴"].fillna(c["견갑골이 지면으로부터 충분히올라옴"])
print("가시성(가까운 귀/코/손목) C:", c[c.view=="C"][["v_ear","v_nose","v_wr","v_el"]].median().round(2).to_dict(),
      "| 귀 vis≥.5 비율", (c[c.view=="C"].v_ear>=.5).mean().round(3), "코", (c[c.view=="C"].v_nose>=.5).mean().round(3))
print("손목–귀 거리(몸통) C 중앙:", c[c.view=="C"].cr_wr_ear.median().round(2))
rows=[]
for (cid,v),d in c.groupby(["clip_id","view"]):
    if len(d)<8: continue
    r=dict(clip_id=cid,view=v,type_key=d.type_key.iloc[0],performer=d.performer.iloc[0],scap=d.scap.iloc[0],
           waist=d["허리 지면 고정"].iloc[0],bounce=d["어깨반동 없음"].iloc[0],tense=d["이완시 긴장 유지"].iloc[0])
    for f in ["cr_trunk","cr_head_trunk","cr_nose_trunk","cr_ear_h","cr_sh_h","cr_knee","cr_hip_lift"]:
        x=d[f].to_numpy(float); x=x[np.isfinite(x)]
        r[f+"_lo"]=np.quantile(x,.1); r[f+"_hi"]=np.quantile(x,.9); r[f+"_max"]=x.max(); r[f+"_min"]=x.min(); r[f+"_amp"]=x.max()-x.min(); r[f+"_med"]=np.median(x)
    rows.append(r)
R=pd.DataFrame(rows); R.to_parquet("crunch_clip.parquet",index=False)
for v in "CE":
    q=R[R.view==v]; y=(~q.scap.astype(bool)).astype(int)
    print(f"\n== 뷰 {v}: 충족 {int((y==0).sum())} 위반 {int(y.sum())}")
    for f in ["cr_trunk_amp","cr_trunk_max","cr_trunk_hi","cr_sh_h_amp","cr_ear_h_amp","cr_head_trunk_amp","cr_nose_trunk_amp","cr_head_trunk_max"]:
        a=roc_auc_score(y,-q[f]); ok=q[y==0][f]; bad=q[y==1][f]
        print(f"{f:20s} AUC(위반=작음) {a:.3f} | 충족 p2/p10/p50 {ok.quantile(.02):.2f}/{ok.quantile(.1):.2f}/{ok.median():.2f} | 위반 p50/p90 {bad.median():.2f}/{bad.quantile(.9):.2f}")
    # 몸통 진폭 대비 머리 진폭: 목만 당김 판별
    q=q.assign(ratio=q.cr_head_trunk_amp/np.maximum(q.cr_trunk_amp,1))
    print("목/몸통 진폭비 중앙 충족 %.2f 위반 %.2f AUC(위반=큼) %.3f"%(q[y==0].ratio.median(),q[y==1].ratio.median(),roc_auc_score(y,q.ratio)))
    # 판별 띠 후보: 충족 p2 / 전조건정상 489 기각률 / 위반 검출
    for thr in [3,4,5,6,8]:
        ok=q[y==0]; bad=q[y==1]; n489=q[q.type_key.astype(str).str.startswith("489")]
        print(f"  몸통 현 진폭 < {thr}°: 충족 기각 {(ok.cr_trunk_amp<thr).mean():.3f} · 전조건정상 기각 {(n489.cr_trunk_amp<thr).mean():.3f} · 위반 검출 {(bad.cr_trunk_amp<thr).mean():.3f}")
    print("  윗몸 배제: 크런치 몸통 현 최대 p99/max = %.1f / %.1f°"%(q.cr_trunk_max.quantile(.99),q.cr_trunk_max.max()))
    print("  무릎각 p2/p50/p98 = %.0f/%.0f/%.0f"%(q.cr_knee_med.quantile(.02),q.cr_knee_med.median(),q.cr_knee_med.quantile(.98)))
    print("  휴식(p10) 몸통 현 중앙 %.1f°, 충족 휴식 p98 %.1f"%(q.cr_trunk_lo.median(),q[y==0].cr_trunk_lo.quantile(.98)))
