# -*- coding: utf-8 -*-
import numpy as np, pandas as pd
from scipy.signal import find_peaks
F = pd.read_parquet("near_frames.parquet")
c = F[(F.exercise == "크런치") & (F.view == "C")].copy()
c["scap"] = c["견갑골이 지면으로부터 충분히 올라옴"].fillna(c["견갑골이 지면으로부터 충분히올라옴"]).astype(bool)
# 진입·이탈(앉음) 프레임: 몸통 현 > 45°
print("몸통 현 >45° 프레임 비율 %.3f, 그 프레임의 위치(첫/끝 3프레임 안) %.2f" % ((c.cr_trunk>45).mean(),
      c[c.cr_trunk>45].frame_idx.isin([0,1,2,13,14,15]).mean()))
print("클립 중 >45° 프레임이 있는 비율 %.3f" % c.groupby("clip_id").cr_trunk.apply(lambda x:(x>45).any()).mean())
reps=[]
for cid,d in c.groupby("clip_id"):
    d=d.sort_values("frame_idx"); d=d[d.cr_trunk<=45]
    if len(d)<8: continue
    s=d.cr_sh_h.to_numpy(); e=d.cr_ear_h.to_numpy(); t=d.cr_trunk.to_numpy(); ht=d.cr_head_trunk.to_numpy()
    rest_t=np.quantile(t,.1); rest_e=np.quantile(e,.1); rest_h=np.quantile(ht,.1)
    # 넓은 신호 = 귀 높이(목만 당김도 사이클을 만든다)
    r=e.max()-e.min()
    if r<=0: continue
    pk,_=find_peaks(np.r_[e.min(),e,e.min()],prominence=max(0.4*r,0.15))
    for p in pk-1:
        lo,hi=max(p-1,0),min(p+2,len(t))
        reps.append(dict(clip_id=cid,type_key=str(d.type_key.iloc[0]),scap=d.scap.iloc[0],performer=d.performer.iloc[0],
            trunk=t[lo:hi].max()-rest_t, ear=e[lo:hi].max()-rest_e, neck=ht[lo:hi].max()-rest_h, knee=d.cr_knee.iloc[p]))
R=pd.DataFrame(reps); R.to_parquet("crunch_reps.parquet",index=False)
ok=R[R.scap]; bad=R[~R.scap]; n489=R[R.type_key.str.startswith("489")]
print("반복 수: 충족 %d 위반 %d 전조건정상 %d"%(len(ok),len(bad),len(n489)))
print("반복 몸통 현 들림(휴식 대비): 충족 p1/p2/p5/p10/p50 %s | 위반 p50/p75 %.1f/%.1f"%(np.round(ok.trunk.quantile([.01,.02,.05,.1,.5]).values,1),bad.trunk.median(),bad.trunk.quantile(.75)))
print("반복 귀 높이 진폭: 충족 p2 %.2f p50 %.2f | 위반 p50 %.2f"%(ok.ear.quantile(.02),ok.ear.median(),bad.ear.median()))
for thr in [3,4,5,6,7,8]:
    print(f"  반복 몸통 들림 < {thr}°: 충족 기각 {(ok.trunk<thr).mean():.3f} · 전조건정상 {(n489.trunk<thr).mean():.3f} · 위반 검출 {(bad.trunk<thr).mean():.3f}")
# 목만 당김 사유: 몸통 < thr 이면서 귀 진폭 >= 0.15(현행 minAmp) → '목만'
for thr in [5,6]:
    b=bad[bad.trunk<thr]; print(f"  기각 회 중 귀 진폭≥0.15(=머리는 움직임) 비율 {(b.ear>=.15).mean():.2f} (n={len(b)})")
# 수행자 단위: 정상 반복 기각이 몰리는 사람
g=ok.assign(rej=ok.trunk<5).groupby("performer").rej.mean().sort_values(ascending=False)
print("수행자별 충족 반복 기각률(<5°) 상위:", g.head(5).round(2).to_dict())
