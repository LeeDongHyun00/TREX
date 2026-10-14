import warnings; warnings.filterwarnings("ignore")
import os,sys
os.chdir(r"C:/Users/hp276/Desktop/trex/data/floor_family/rel_designer")
sys.path.insert(0, os.getcwd())
import numpy as np, pandas as pd
__file__=os.path.join(os.getcwd(),"x.py"); exec(open("m2_count3.py", encoding="utf-8").read().split("class FloorCycle3")[0])
from m2_count3 import chords
from m2_count4 import FloorCycle4
for (w,k),d in df.groupby(["w","set"]):
    d=chords(d.sort_values("t_ms"))
    ins=d[d.in_set]
    ch=ins.chord
    fc=FloorCycle4()
    g=d.assign(bin=d.t_ms//300).groupby("bin").head(1) if False else d
    for r in d.itertuples(index=False): fc.on(r.t_ms, r.chord)
    s0,s1=ins.t_ms.min(), ins.t_ms.max()
    exc=[round(c[2]) for c in fc.cycles if s0-1500<=c[1]<=s1+1500]
    print(w,k,"valid%",round(ch.notna().mean()*100), "p10",round(ch.quantile(.1),1),"p50",round(ch.quantile(.5),1),"p90",round(ch.quantile(.9),1),"max",round(ch.max(),1),"cycle amps",exc)
