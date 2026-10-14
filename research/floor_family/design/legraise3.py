# -*- coding: utf-8 -*-
# 무릎각 판정에 '뼈 길이 일관성' 관측 게이트를 더하면 정상 기각이 2% 아래로 내려가는가
import glob, numpy as np, pandas as pd
import pyarrow.dataset as ds
from scipy.signal import find_peaks
from pathlib import Path
S=Path('.').resolve(); A=S.parent/"aihub_local_agent"; OUT=Path(r"C:/Users/hp276/Desktop/trex/research/aihub_fitness/outputs")
F=pd.read_parquet("near_frames.parquet")
c=F[(F.exercise=="라잉 레그 레이즈")&(F.view=="C")]
clips=c.clip_id.unique()
k=ds.dataset(OUT/"kp2d.parquet").to_table(columns=["clip_id","frame_idx","view_letter","img_key"],filter=ds.field("clip_id").isin(list(clips))).to_pandas()
k=k[k.view_letter=="C"]
lm=pd.concat([pd.read_parquet(f) for f in glob.glob(str(A/"mp_full"/"landmarks_*.parquet"))],ignore_index=True)
m=k.merge(lm,on="img_key"); m=m[m.detected]
m=m.merge(c[["clip_id","frame_idx","lr_thigh","lr_knee","type_key","performer","허벅지와 종아리 각도 고정"]],on=["clip_id","frame_idx"]).sort_values(["clip_id","frame_idx"])
vL=np.min(np.c_[m.l11_v,m.l23_v,m.l25_v,m.l27_v],1); vR=np.min(np.c_[m.l12_v,m.l24_v,m.l26_v,m.l28_v],1)
m["left"]=vL>=vR; m["left"]=m.groupby("clip_id").left.transform(lambda s: s.mean()>=.5)
def P(i,j): return np.where(m.left.to_numpy()[:,None],np.c_[m[f"l{i}_x"],m[f"l{i}_y"]],np.c_[m[f"l{j}_x"],m[f"l{j}_y"]])
hp,kn,an=P(23,24),P(25,26),P(27,28)
m["thl"]=np.hypot(*(kn-hp).T); m["shl"]=np.hypot(*(an-kn).T)
m["vk"]=np.where(m.left,np.minimum(m.l25_v,m.l27_v),np.minimum(m.l26_v,m.l28_v))
reps=[]
for cid,d in m.groupby("clip_id"):
    th=d.lr_thigh.to_numpy(); kn_=d.lr_knee.to_numpy()
    rest=np.quantile(th,.1); r=th.max()-th.min()
    if len(th)<8 or r<=0: continue
    rest_idx=th<=rest+0.2*r
    ratio=d.shl.to_numpy()/np.maximum(d.thl.to_numpy(),1); ref=np.median(ratio[rest_idx]) if rest_idx.any() else np.median(ratio)
    pk,_=find_peaks(np.r_[th.min(),th,th.min()],prominence=max(.4*r,20)); pk=pk-1
    for i,p in enumerate(pk):
        a=0 if i==0 else pk[i-1]+np.argmin(th[pk[i-1]:p+1]); b=len(th) if i==len(pk)-1 else p+np.argmin(th[p:pk[i+1]+1])+1
        seg=np.arange(a,b); amp=th[p]-rest; up=seg[th[seg]>=rest+.5*amp]
        good=up[(np.abs(ratio[up]/ref-1)<=.25)&(d.vk.to_numpy()[up]>=.5)]
        reps.append(dict(type_key=str(d.type_key.iloc[0]),ok=bool(d["허벅지와 종아리 각도 고정"].iloc[0]),k_all=np.max(kn_[up]),k_good=np.max(kn_[good]) if len(good) else np.nan,ngood=len(good),nup=len(up)))
R=pd.DataFrame(reps); ok=R[R.ok]; bad=R[~R.ok]; al=R[R.type_key.str.startswith("473")]
print("관측 게이트(정강이/허벅지 비 ±25퍼센트·가시성≥.5) 통과 회 비율: %.3f"%R.k_good.notna().mean())
for thr in [90,100,110,120]:
    print(f" 최대 무릎 <{thr} (게이트 통과 회만, 유보=통과): 충족 기각 {(ok.k_good<thr).mean():.3f} · 전조건정상 {(al.k_good<thr).mean():.3f} · 위반 검출 {(bad.k_good<thr).mean():.3f}")
