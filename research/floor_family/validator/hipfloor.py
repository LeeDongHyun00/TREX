import numpy as np, pandas as pd
f=pd.read_parquet(r"../rel_designer/m1_frames.parquet")
p=f[(f.exercise=="플랭크")&(f.view=="C")&(f.vis_chain>=.5)&(f.pl_knee>=145)]
ok=p["몸통과 엉덩이의 정렬 유지"].astype(bool)
for name,d in (("충족",p[ok]),("553",p[p.type_key=="553"]),("위반",p[~ok])):
    print("D1 pl_hip_floor",name,"p1/p2/p5/p10",np.round(np.quantile(d.pl_hip_floor,[.01,.02,.05,.1]),3).tolist(),"<0.08:",round((d.pl_hip_floor<.08).mean(),3), "n",len(d))
# clip-level: any 1s(2 consecutive) below 0.08
def run2(g,thr):
    x=(g.sort_values("frame_idx").pl_hip_floor<thr).to_numpy(); return bool(np.any(x[1:]&x[:-1]))
for thr in (0.08,0.10):
    r=p.groupby("clip_id").apply(lambda g: run2(g,thr)); okc=p.groupby("clip_id")["몸통과 엉덩이의 정렬 유지"].first().astype(bool)
    print("2연속 <",thr,"충족 clip",round(r[okc].mean(),3),"all",round(r.mean(),3))
F=pd.read_parquet(r"../designer/near_frames.parquet")
q=F[(F.exercise=="플랭크")&(F.view=="C")&(F.chain_vis>=.5)&(F.knee>=145)]
ok=q["몸통과 엉덩이의 정렬 유지"].astype(bool)
print("D3 hip_floor 충족 <0.10", round((q.hip_floor[ok]<.10).mean(),4), "p1", round(float(np.quantile(q.hip_floor[ok],.01)),3))
# torso/length ratio: what hip_floor value corresponds?
print("torso px / elbow-ankle len median (C)", )
