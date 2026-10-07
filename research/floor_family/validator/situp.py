import numpy as np, pandas as pd
from scipy.signal import find_peaks
F=pd.read_parquet(r"../design/near_frames.parquet")
c=F[(F.exercise=="크런치")&(F.view=="C")].copy()
c["scap"]=c["견갑골이 지면으로부터 충분히 올라옴"].fillna(c["견갑골이 지면으로부터 충분히올라옴"]).astype(bool)
# D2 method but WITHOUT truncation at 45
reps=[]
for cid,d in c.groupby("clip_id"):
    d=d.sort_values("frame_idx")
    if len(d)<8: continue
    e=d.cr_ear_h.to_numpy(); t=d.cr_trunk.to_numpy()
    r=e.max()-e.min()
    if r<=0: continue
    pk,_=find_peaks(np.r_[e.min(),e,e.min()],prominence=max(0.4*r,0.15))
    for p in pk-1:
        lo,hi=max(p-1,0),min(p+2,len(t))
        reps.append(dict(scap=d.scap.iloc[0],tk=str(d.type_key.iloc[0]),perf=d.performer.iloc[0],abs_peak=t[lo:hi].max(), rel=t[lo:hi].max()-np.quantile(t,.1)))
R=pd.DataFrame(reps); ok=R[R.scap]
print("n ok reps",len(ok))
for th in (45,50,60):
    print(f"abs trunk peak >{th}: 충족 {(ok.abs_peak>th).mean():.3f} 489 {(R[R.tk.str.startswith('489')].abs_peak>th).mean():.3f} performers {ok[ok.abs_peak>th].perf.nunique()}")
print("rel p99/max (untruncated)", round(ok.rel.quantile(.99),1), round(ok.rel.max(),1))
