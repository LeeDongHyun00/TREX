import numpy as np, pandas as pd
from scipy.stats import beta
R=pd.read_parquet(r"../design/crunch_reps.parquet")
ok=R[R.scap]
def ci(k,n): return (beta.ppf(.025,k,n-k+1) if k>0 else 0, beta.ppf(.975,k+1,n-k))
for th in (4,4.5,5):
    k=int((ok.trunk<th).sum()); n=len(ok); lo,hi=ci(k,n)
    g=ok.assign(r=ok.trunk<th).groupby("performer").r.agg(['mean','size']).sort_values('mean',ascending=False)
    print(f"crunch neck_only <{th}: {k}/{n}={k/n:.3f} 95%CI[{lo:.3f},{hi:.3f}] performers>0: {(g['mean']>0).sum()}/{len(g)} top: {g.head(3).round(2).to_dict('index')}")
L=pd.read_parquet(r"../design/legraise_reps_C.parquet")
for th in (30,):
    a=L[L.type_key.str.startswith('473')]; k=int((a.thigh_amp<th).sum()); n=len(a); print("leg shallow 473",k,n, ci(k,n))
for th in (38,):
    pass
