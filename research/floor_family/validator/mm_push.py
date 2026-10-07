import numpy as np, glob, os
W,H=1280,720
for f in sorted(glob.glob(r"C:/Users/hp276/Desktop/trex/data/mm-fit/captures/w1[89]/*pushups.trexcap"))+sorted(glob.glob(r"C:/Users/hp276/Desktop/trex/data/mm-fit/captures/w20/*pushups.trexcap")):
    R=[];K=[]
    for line in open(f):
        if not line.startswith("F"): continue
        parts=line.rstrip("\n").split("\t")
        lm={}
        for p in parts[4:]:
            i,v=p.split(":"); a=[float(x) for x in v.split(",")]; lm[int(i)]=a
        if not lm: continue
        P=lambda i: np.array([lm[i][0]*W, lm[i][1]*H])
        vL=min(lm[i][3] for i in (11,23,25,27)); vR=min(lm[i][3] for i in (12,24,26,28))
        sh,an,osh = (P(11),P(27),P(12)) if vL>=vR else (P(12),P(28),P(11))
        L=np.hypot(*(an-sh)); sep=np.hypot(*(osh-sh))
        R.append(L/max(sep,1)); 
    R=np.array(R)
    print(os.path.basename(f), len(R), "ratio p10/50/90", np.round(np.quantile(R,[.1,.5,.9]),1), ">=8", round((R>=8).mean(),2))
