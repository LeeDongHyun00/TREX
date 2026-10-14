import warnings; warnings.filterwarnings("ignore")
import os,sys
os.chdir(r"C:/Users/hp276/Desktop/trex/data/floor_family/rel_designer")
sys.path.insert(0, os.getcwd())
import io, contextlib
with contextlib.redirect_stdout(io.StringIO()):
    from m2_count3 import chords, df
    from m2_count4 import FloorCycle4
import pandas as pd
for lift in (5,8,10,12):
  tot=0; lost=0
  for (w,k),d in df.groupby(["w","set"]):
    if w!="w20": continue
    d=chords(d.sort_values("t_ms"))
    # 300ms grid as in m2_count4? check chords
    fc=FloorCycle4(lift=lift)
    for r in d.itertuples(index=False): fc.on(r.t_ms, r.chord)
    s0,s1=d.t_ms[d.in_set].min(), d.t_ms[d.in_set].max()
    ins=sum(1 for c in fc.cycles if s0-1500<=c[1]<=s1+1500)
    tot+=ins
  print("lift",lift,"w20 counted",tot,"of 30")
d=df[df.w=="w20"]; print("grid check dt median ms", d.sort_values("t_ms").groupby("set").t_ms.diff().median())
