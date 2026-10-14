import numpy as np, pandas as pd
F=pd.read_parquet(r"../designer/near_frames.parquet")
for ex in ["라잉 레그 레이즈","크런치"]:
    for v in "CE":
        d=F[(F.exercise==ex)&(F.view==v)&(F.chain_vis>=.5)]
        print(ex,v,"ratio p10/50/90",np.round(np.quantile(d.ratio,[.1,.5,.9]),1).tolist(),">=8",round((d.ratio>=8).mean(),3),">=3",round((d.ratio>=3).mean(),3))
d=F[(F.exercise=="라잉 레그 레이즈")&(F.view=="C")&(F.chain_vis>=.5)]
# clip-level: fraction of clips where >=50% frames pass ratio>=8
cl=d.groupby("clip_id").ratio.apply(lambda s:(s>=8).mean())
print("leg C clips with >=half frames ratio>=8:", round((cl>=.5).mean(),3), "median clip pass", round(cl.median(),3))
# high-thigh frames
hi=d[d.lr_thigh>=45] if 'lr_thigh' in d else None
if hi is not None: print("leg C frames thigh>=45: ratio>=8", round((hi.ratio>=8).mean(),3))
print([c for c in F.columns if c.startswith('lr_')])
