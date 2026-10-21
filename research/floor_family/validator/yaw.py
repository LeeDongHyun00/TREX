import numpy as np, pandas as pd
f=pd.read_parquet(r"../rel_designer/m1_frames.parquet")
f["yaw"]=np.maximum(f.side_sh_along_t, f.side_hp_along_t)
for ex in ["플랭크","크런치","라잉 레그 레이즈"]:
    for v in "CE":
        d=f[(f.exercise==ex)&(f.view==v)]
        dv=d[(d.vis_sh_hp>=.5)&(d.vis_far_sh>=.2)&(d.vis_far_hp>=.2)]
        print(ex,v,len(d),"sh_along p50/p90",np.round(np.quantile(d.side_sh_along_t,[.5,.9]),3).tolist(),
              "hp_along p50/p90",np.round(np.quantile(d.side_hp_along_t,[.5,.9]),3).tolist(),
              "yaw(max)<=.15 all",round((d.yaw<=.15).mean(),3),"vis-gated",round((dv.yaw<=.15).mean(),3),
              "sh-only<=.15",round((d.side_sh_along_t<=.15).mean(),3), "<=.25", round((d.yaw<=.25).mean(),3),
              "perp p50", round(float(np.median(d.side_sh_perp_t)),3))
