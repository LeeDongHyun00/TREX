import warnings; warnings.filterwarnings("ignore")
import os,sys,io,contextlib
os.chdir(r"C:/Users/hp276/Desktop/trex/data/floor_family/rel_designer")
sys.path.insert(0, os.getcwd())
with contextlib.redirect_stdout(io.StringIO()):
    from m2_count3 import df, near_side
import numpy as np
from scipy.signal import find_peaks
def devup(p,a,b):
    u=b-a; L=max(np.hypot(*u),1e-9); ux,uy=u/L; nx,ny=-uy,ux
    if ny>0: nx,ny=-nx,-ny
    return ((p[0]-a[0])*nx+(p[1]-a[1])*ny)
for (w,k),d in df.groupby(["w","set"]):
    if w!="w20": continue
    d=d.sort_values("t_ms"); sh,hp,kn,an,ear=near_side(d[d.in_set])
    d=d.assign(bin=d.t_ms//300).groupby("bin").head(1); ins=d[d.in_set]
    h=[]
    for r in ins.itertuples(index=False):
        rr=r._asdict(); P=lambda i: np.array([rr[f"x{i}"]*rr["W"], rr[f"y{i}"]*rr["H"]])
        torso=np.hypot(*(P(sh)-P(hp)))
        h.append(devup(P(ear),P(an),P(hp))/torso)
    h=np.array(h); tr,_=find_peaks(-h,prominence=.05); pk,_=find_peaks(h,prominence=.05)
    p5=np.quantile(h,.05)
    print(w,k,"head_h p5",round(p5,3),"peak amp med",round(float(np.median(h[pk]-p5)),3),"troughs above p5",np.round(h[tr]-p5,3).tolist())
