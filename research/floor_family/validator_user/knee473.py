import pandas as pd, numpy as np
d=pd.read_parquet('rel_designer/m1_frames.parquet')
print(d.exercise.unique(), d.view.unique())
lr=d[(d.exercise.str.contains('레그'))&(d.view=='C')].copy()
print(lr.type_key.unique()[:20], len(lr.clip_id.unique()))
# top frames: thigh >= clip max - 15, ankle vis>=0.5
rows=[]
for cid,g in lr.groupby('clip_id'):
    g=g[g.vis_chain>=0.5]
    if len(g)<3: continue
    top=g[g.lr_thigh>=g.lr_thigh.max()-15]
    topv=top[top.vis_an>=0.5]
    rows.append(dict(clip=cid,type=g.type_key.iloc[0],perf=g.performer.iloc[0],
        knee_top_med=top.lr_knee.median(), knee_top_med_anvis=topv.lr_knee.median() if len(topv) else np.nan,
        n_top=len(top), n_topv=len(topv), knee_min=g.lr_knee.min(), thigh_max=g.lr_thigh.max(),
        cond=g['허벅지와 종아리 각도 고정'].iloc[0]))
r=pd.DataFrame(rows)
a=r[r.type.astype(str).str.endswith('473')|(r.type.astype(str)=='473')]
print('473 clips',len(a))
print(a[['perf','knee_top_med','knee_top_med_anvis','n_top','n_topv','knee_min','thigh_max']].sort_values('knee_top_med').to_string())
for th in [100,110,120,150]:
    print(th,'473 top-med<th (all top)',(a.knee_top_med<th).mean().round(3),' (ankle vis only, nan->pass)',(a.knee_top_med_anvis<th).mean().round(3))
s=r[r.cond==1]
print('cond satisfied clips',len(s))
for th in [100,110,120]:
    print(th,'satisfied top-med<th',(s.knee_top_med<th).mean().round(3),'anvis',(s.knee_top_med_anvis<th).mean().round(3))
