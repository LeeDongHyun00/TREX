import numpy as np, pandas as pd
f=pd.read_parquet(r"../rel_designer/m1_frames.parquet")
L=f[(f.exercise=="라잉 레그 레이즈")&(f.view=="C")&(f.vis_sh_hp>=.5)&(f.vis_kn>=.5)]
rows=[]
for cid,s in L.groupby("clip_id"):
    if len(s)<8: continue
    top=s[s.lr_thigh>=np.quantile(s.lr_thigh,.8)]
    t2=top[top.vis_far_kn>=.35]
    rows.append(dict(seen=len(t2)>0, gap=t2.knee_gap_t.median() if len(t2) else np.nan, dth=(t2.lr_thigh-t2.far_thigh).abs().median() if len(t2) else np.nan, tk=s.type_key.iloc[0]))
g=pd.DataFrame(rows)
print("far knee seen clips", round(g.seen.mean(),3), "gap p50/p90/p99/max", np.round(np.nanquantile(g.gap,[.5,.9,.99,1]),3).tolist(), ">0.45", int((g.gap>.45).sum()), "of", int(g.gap.notna().sum()), "dthigh p99", round(float(np.nanquantile(g.dth,.99)),1))
print("vis_far_kn>=.35 frames C", round((f[(f.exercise=="라잉 레그 레이즈")&(f.view=="C")].vis_far_kn>=.35).mean(),3))
