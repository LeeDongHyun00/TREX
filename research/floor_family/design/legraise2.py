# -*- coding: utf-8 -*-
import numpy as np, pandas as pd
from scipy.signal import find_peaks
from sklearn.metrics import roc_auc_score
F = pd.read_parquet("near_frames.parquet")
c = F[(F.exercise == "라잉 레그 레이즈") & (F.view == "C")].copy()
c["kneeok"]=c["허벅지와 종아리 각도 고정"].astype(bool); c["headok"]=c["고개 숙임 여부"].astype(bool)
reps=[]
for cid,d in c.groupby("clip_id"):
    d=d.sort_values("frame_idx")
    th=d.lr_thigh.to_numpy(); kn=d.lr_knee.to_numpy(); nm=d.near_min.to_numpy(); hd=d.lr_head.to_numpy(); tr=d.lr_trunk_hor.to_numpy()
    if len(th)<8: continue
    rest=np.quantile(th,.1); r=th.max()-th.min()
    if r<=0: continue
    pk,_=find_peaks(np.r_[th.min(),th,th.min()],prominence=max(0.4*r,20))
    # 사이클 경계 = 인접 피크 사이의 최소
    pk=pk-1
    for i,p in enumerate(pk):
        a=0 if i==0 else pk[i-1]+np.argmin(th[pk[i-1]:p+1]); b=len(th) if i==len(pk)-1 else p+np.argmin(th[p:pk[i+1]+1])+1
        seg=np.arange(a,b); amp=th[p]-rest
        up=seg[th[seg]>=rest+0.5*amp]                       # 다리를 든 프레임(진폭 절반 이상)
        reps.append(dict(clip_id=cid,type_key=str(d.type_key.iloc[0]),performer=d.performer.iloc[0],kneeok=d.kneeok.iloc[0],headok=d.headok.iloc[0],
            knee_peak=kn[p],knee_up_med=np.median(kn[up]),knee_up_max=np.max(kn[up]),n_up=len(up),amp=amp,near=nm[p],
            head_max=hd[seg].max(),head_med=np.median(hd[seg]),trunk_max=tr[seg].max()))
R=pd.DataFrame(reps)
ok=R[R.kneeok]; bad=R[~R.kneeok]; allok=R[R.type_key.str.startswith("473")]
for f in ["knee_peak","knee_up_med","knee_up_max"]:
    print(f, "AUC %.3f"%roc_auc_score((~R.kneeok).astype(int),-R[f]), "충족 p1/p2/p5 %s"%np.round(ok[f].quantile([.01,.02,.05]).values,0), "위반 p50/p90 %.0f/%.0f"%(bad[f].median(),bad[f].quantile(.9)))
    for thr in [100,110,120,130]:
        print(f"   <{thr}: 충족 기각 {(ok[f]<thr).mean():.3f} 전조건정상 {(allok[f]<thr).mean():.3f} 위반 검출 {(bad[f]<thr).mean():.3f}")
lowok=ok[ok.knee_up_max<110]
print("무릎 충족인데 든 구간 최대 무릎 <110 인 회: 수행자 분포", lowok.performer.value_counts().head(6).to_dict(), "가까운쪽 vis 중앙 %.2f vs 전체 %.2f"%(lowok.near.median(),ok.near.median()))
print("허벅지 진폭: 전조건정상 p1/p2/p5 %s, 전체 p1/p2 %s"%(np.round(allok.amp.quantile([.01,.02,.05]).values,0),np.round(R.amp.quantile([.01,.02]).values,0)))
for thr in [25,30,35,40,45]:
    print(f"   허벅지 진폭 <{thr}°: 전조건정상 기각 {(allok.amp<thr).mean():.3f} 전체 {(R.amp<thr).mean():.3f}")
print("반복 몸통 최대(화면 수평 대비): 전체 p98/p99/p99.5 %s"%np.round(R.trunk_max.quantile([.98,.99,.995]).values,1))
# 머리: 반복 단위
h=R.assign(y=(~R.headok).astype(int))
for f in ["head_med","head_max"]:
    print(f,"AUC %.3f"%roc_auc_score(h.y,h[f]),"충족 p95/p98/p99 %s"%np.round(h[h.y==0][f].quantile([.95,.98,.99]).values,1))
    for thr in [30,35,38,40,45]:
        print(f"   >{thr}: 충족 오탐 {(h[h.y==0][f]>thr).mean():.3f} 전조건정상 {(h[h.type_key.str.startswith('473')][f]>thr).mean():.3f} 위반 검출 {(h[h.y==1][f]>thr).mean():.3f}")
print("---- 니 턱(무릎 접어 당김) 후보")
for thr in [70,80,90]:
    print(f"   든 구간 최대 무릎 <{thr}: 충족 기각 {(ok.knee_up_max<thr).mean():.3f} 전조건정상 {(allok.knee_up_max<thr).mean():.3f} 위반(무릎90) {(bad.knee_up_max<thr).mean():.3f}")
# 반복 단위 정상 기각을 수행자 단위로: 한 사람에게 몰리나
g=allok.assign(r=allok.knee_up_max<110).groupby("performer").r.mean()
print("전조건정상 수행자별 무릎<110 기각률 >0 인 사람 수 %d / %d"%((g>0).sum(),len(g)))
