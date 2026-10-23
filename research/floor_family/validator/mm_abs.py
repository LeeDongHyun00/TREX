import warnings; warnings.filterwarnings("ignore")
import os,sys,io,contextlib
os.chdir(r"C:/Users/hp276/Desktop/trex/data/floor_family/rel_designer")
sys.path.insert(0, os.getcwd())
with contextlib.redirect_stdout(io.StringIO()):
    from m2_count3 import chords, df
    from m2_count4 import FloorCycle4
import numpy as np
for w in ("w06","w14","w18","w19","w20"):
    peaks=[]
    for (ww,k),d in df.groupby(["w","set"]):
        if ww!=w: continue
        d=chords(d.sort_values("t_ms"))
        fc=FloorCycle4()
        for r in d.itertuples(index=False): fc.on(r.t_ms,r.chord)
        s0,s1=d.t_ms[d.in_set].min(), d.t_ms[d.in_set].max()
        for c in fc.cycles:
            if s0-1500<=c[1]<=s1+1500:
                seg=d[(d.t_ms>=c[0])&(d.t_ms<=c[1])].chord
                peaks.append(seg.max())
    p=np.array(peaks)
    print(w,len(p),"abs peak min/p10/p50/max",np.round([p.min(),np.quantile(p,.1),np.median(p),p.max()],1).tolist(),">50",round((p>50).mean(),2),">80",round((p>80).mean(),2))
