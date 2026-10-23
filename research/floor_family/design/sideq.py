import glob, numpy as np, pandas as pd, pyarrow.dataset as ds
from pathlib import Path
A=Path('..').resolve()/"aihub_local_agent"; OUT=Path(r"C:/Users/hp276/Desktop/trex/research/aihub_fitness/outputs")
clips=pd.read_parquet(OUT/"clips.parquet",columns=["clip_id","exercise"]); clips=clips[clips.exercise.isin(["플랭크","크런치","라잉 레그 레이즈"])]
k=ds.dataset(OUT/"kp2d.parquet").to_table(columns=["clip_id","view_letter","img_key"],filter=ds.field("clip_id").isin(list(clips.clip_id))).to_pandas()
lm=pd.concat([pd.read_parquet(f) for f in glob.glob(str(A/"mp_full"/"landmarks_*.parquet"))],ignore_index=True)
m=k.merge(lm,on="img_key").merge(clips,on="clip_id"); m=m[m.detected]
sw=np.hypot(m.l11_x-m.l12_x,m.l11_y-m.l12_y); hw=np.hypot(m.l23_x-m.l24_x,m.l23_y-m.l24_y)
left=np.minimum.reduce([m.l11_v,m.l23_v])>=np.minimum.reduce([m.l12_v,m.l24_v])
sh=np.where(left[:,None],np.c_[m.l11_x,m.l11_y],np.c_[m.l12_x,m.l12_y]); hp=np.where(left[:,None],np.c_[m.l23_x,m.l23_y],np.c_[m.l24_x,m.l24_y])
torso=np.hypot(*(sh-hp).T)
m["q"]=torso/np.maximum((sw+hw)/2,1)
for (ex,v),d in m.groupby(["exercise","view_letter"]):
    print(ex,v,"몸통/평균(어깨폭,골반폭) p5/p50/p95",np.round(d.q.quantile([.05,.5,.95]).values,1),"≥3:",round((d.q>=3).mean(),3),"≥4:",round((d.q>=4).mean(),3))
