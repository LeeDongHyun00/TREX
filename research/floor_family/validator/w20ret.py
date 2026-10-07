import warnings; warnings.filterwarnings("ignore")
import os,sys,io,contextlib
os.chdir(r"C:/Users/hp276/Desktop/trex/data/floor_family/rel_designer")
sys.path.insert(0, os.getcwd())
with contextlib.redirect_stdout(io.StringIO()):
    from m2_count3 import chords, df, near_side, selev
import numpy as np
from scipy.signal import find_peaks
for (w,k),d in df.groupby(["w","set"]):
    if w not in ("w20","w19"): continue
    d=d.sort_values("t_ms"); sh,hp,kn,an,ear=near_side(d[d.in_set])
    d=d.assign(bin=d.t_ms//300).groupby("bin").head(1)
    ins=d[d.in_set]
    ch=[];el=[]
    for r in ins.itertuples(index=False):
        rr=r._asdict(); P=lambda i: np.array([rr[f"x{i}"]*rr["W"], rr[f"y{i}"]*rr["H"]])
        if not np.isfinite(rr.get(f"x{sh}",np.nan)): ch.append(np.nan); el.append(np.nan); continue
        ch.append(selev(P(sh)-P(hp),P(hp)-P(an))); el.append(selev(P(ear)-P(hp),P(hp)-P(an)))
    ch=np.array(ch); el=np.array(el)
    # troughs between peaks
    pk,_=find_peaks(el,prominence=4); tr,_=find_peaks(-el,prominence=4)
    base=np.nanmin(el[:5]) if np.isfinite(el[:5]).any() else np.nan
    trv=el[tr]-np.nanquantile(el,.05)
    print(w,k,"ear p5",round(np.nanquantile(el,.05),1),"peaks",len(pk),"troughs",len(tr),"trough above p5 (deg)",np.round(trv,1).tolist())
