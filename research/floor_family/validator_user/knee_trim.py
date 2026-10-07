import pandas as pd, numpy as np
d=pd.read_parquet('rel_designer/m1_frames.parquet')
lr=d[(d.exercise=='라잉 레그 레이즈')&(d.view=='C')].sort_values(['clip_id','frame_idx'])
def topmed(g):
    g=g[g.vis_chain>=0.5]
    if len(g)<3: return np.nan
    top=g[g.lr_thigh>=g.lr_thigh.max()-15]; return top.lr_knee.median()
rows=[]
for cid,g in lr.groupby('clip_id'):
    n=len(g)
    rows.append(dict(clip=cid,type=g.type_key.iloc[0],perf=g.performer.iloc[0],cond=g['허벅지와 종아리 각도 고정'].iloc[0],
      full=topmed(g), trim=topmed(g.iloc[2:n-4]),
      # per-frame: fraction of lifted frames (thigh>=30) with knee<110 in trimmed
      ))
r=pd.DataFrame(rows)
ok=r[r.cond==1]; bad=r[r.cond==0]; a=r[r.type=='473']
for th in [100,110,120,130]:
    print(th,'full: ok %.3f 473 %.3f bad %.3f'%((ok.full<th).mean(),(a.full<th).mean(),(bad.full<th).mean()),
          '| trim(앞2·뒤4 제외): ok %.3f 473 %.3f bad %.3f'%((ok.trim<th).mean(),(a.trim<th).mean(),(bad.trim<th).mean()))
print(ok[ok.full<110][['clip','perf','full','trim']].to_string())
