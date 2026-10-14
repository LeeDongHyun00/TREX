import warnings; warnings.filterwarnings("ignore")
import os,sys,io,contextlib
os.chdir("rel_designer"); sys.path.insert(0, os.getcwd())
with contextlib.redirect_stdout(io.StringIO()):
    from m2_count3 import chords, df
import numpy as np
for (w,k),d in df.groupby(["w","set"]):
    d=chords(d.sort_values("t_ms")); ins=d[d.in_set]; ch=ins.chord.dropna()
    # fraction of in-set frames with chord <=12 / <=15 ; longest still run (<=3deg span over 1s)
    t=ins.t_ms.values; c=ins.chord.values
    still=0
    for i in range(len(t)):
        j=np.searchsorted(t,t[i]+1000)
        seg=c[i:j]; seg=seg[np.isfinite(seg)]
        if len(seg)>=3 and seg.max()-seg.min()<=3: still+=1
    print(w,k,"in-set min %.1f p05 %.1f"%(ch.min(),ch.quantile(.05)),"<=12°: %.2f"%(ch<=12).mean(),"<=15°: %.2f"%(ch<=15).mean(),"1s창 폭≤3° 시작점 수",still)
