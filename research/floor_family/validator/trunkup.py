import numpy as np, pandas as pd
R=pd.read_parquet(r"../design/legraise_reps_C.parquet")
for th in (25,35):
    a=R[R.type_key.str.startswith('473')]; ok=R[R.kneeok]
    print(f"trunk_peak>{th}: all {(R.trunk_peak>th).mean():.3f} 473 {(a.trunk_peak>th).mean():.3f} kneeok {(ok.trunk_peak>th).mean():.3f} perf {R[R.trunk_peak>th].performer.nunique()}")
print(R.columns.tolist())
