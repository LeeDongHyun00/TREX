# -*- coding: utf-8 -*-
import numpy as np, pandas as pd
from scipy.signal import find_peaks
from sklearn.metrics import roc_auc_score
F = pd.read_parquet("near_frames.parquet")
for view in "CE":
    c = F[(F.exercise == "라잉 레그 레이즈") & (F.view == view)].copy()
    c["kneeok"]=c["허벅지와 종아리 각도 고정"].astype(bool); c["headok"]=c["고개 숙임 여부"].astype(bool)
    c["dropok"]=c["이완 시 다리 긴장유지"].astype(bool); c["waistok"]=c["허리 지면 고정"].astype(bool)
    reps=[]; clips=[]
    for cid,d in c.groupby("clip_id"):
        d=d.sort_values("frame_idx")
        if len(d)<8: continue
        th=d.lr_thigh.to_numpy(); kn=d.lr_knee.to_numpy(); hd=d.lr_head.to_numpy(); tr=d.lr_trunk_hor.to_numpy(); lg=d.lr_leg.to_numpy(); ah=d.lr_ankle_h.to_numpy()
        rest=np.quantile(th,.1)
        r=th.max()-th.min()
        base=dict(clip_id=cid,type_key=str(d.type_key.iloc[0]),performer=d.performer.iloc[0],kneeok=d.kneeok.iloc[0],headok=d.headok.iloc[0],dropok=d.dropok.iloc[0],waistok=d.waistok.iloc[0])
        clips.append(dict(base,head_med=np.median(hd),head_p90=np.quantile(hd,.9),trunk_max=tr.max(),trunk_med=np.median(tr),thigh_min=th.min(),leg_min=lg.min(),ank_min=ah.min(),knee_med=np.median(kn)))
        if r<=0: continue
        pk,_=find_peaks(np.r_[th.min(),th,th.min()],prominence=max(0.4*r,20))
        tro,_=find_peaks(np.r_[th.max(),-th,th.max()] if False else -np.r_[th.max(),th,th.max()],prominence=max(0.4*r,20))
        for p in pk-1:
            lo,hi=max(p-1,0),min(p+2,len(th)); j=lo+np.argmax(th[lo:hi])
            reps.append(dict(base,thigh_amp=th[j]-rest,thigh_peak=th[j],leg_peak=lg[j],knee_peak=kn[j],knee_min=kn[lo:hi].min(),head_peak=hd[j],trunk_peak=tr[lo:hi].max()))
        for p in tro-1:
            reps_b=dict(base,leg_bottom=lg[p],ank_bottom=ah[p],thigh_bottom=th[p])
            clips[-1].setdefault("bottoms",[]).append(lg[p])
    R=pd.DataFrame(reps); C=pd.DataFrame(clips)
    R.to_parquet(f"legraise_reps_{view}.parquet",index=False)
    ok=R[R.kneeok]; bad=R[~R.kneeok]; allok=R[R.type_key.str.startswith("473")]
    print(f"\n==== 뷰 {view} 반복: 무릎 충족 {len(ok)} 위반 {len(bad)} 전조건정상 {len(allok)}")
    print("정점 무릎각(가까운 쪽): 충족 p1/p2/p5/p10/p50 %s | 위반 p50/p90 %.0f/%.0f | AUC %.3f"%(np.round(ok.knee_peak.quantile([.01,.02,.05,.1,.5]).values,0),bad.knee_peak.median(),bad.knee_peak.quantile(.9),roc_auc_score((~R.kneeok).astype(int),-R.knee_peak)))
    for thr in [100,110,120,130,140]:
        print(f"  정점 무릎 < {thr}°: 충족 기각 {(ok.knee_peak<thr).mean():.3f} · 전조건정상 {(allok.knee_peak<thr).mean():.3f} · 위반(무릎 90) 검출 {(bad.knee_peak<thr).mean():.3f}")
    print("허벅지 들림 진폭(휴식 대비): 전 반복 p1/p2/p5/p50 %s, 전조건정상 p2 %.0f"%(np.round(R.thigh_amp.quantile([.01,.02,.05,.5]).values,0),allok.thigh_amp.quantile(.02)))
    print("정점 다리 들림각: 전조건정상 p2/p10/p50/p90 %s"%np.round(allok.leg_peak.quantile([.02,.1,.5,.9]).values,0))
    print("정점 몸통(화면 수평 대비) 최대: 전 반복 p50/p98/max %.1f/%.1f/%.1f"%(R.trunk_peak.median(),R.trunk_peak.quantile(.98),R.trunk_peak.max()))
    hc=C.assign(y=(~C.headok).astype(int))
    print("머리 들림 중앙값(클립): 충족 p50/p95/p98 %.1f/%.1f/%.1f · 위반 p10/p50 %.1f/%.1f · AUC %.3f"%(hc[hc.y==0].head_med.median(),hc[hc.y==0].head_med.quantile(.95),hc[hc.y==0].head_med.quantile(.98),hc[hc.y==1].head_med.quantile(.1),hc[hc.y==1].head_med.median(),roc_auc_score(hc.y,hc.head_med)))
    for thr in [20,22,25,28]:
        print(f"  머리 들림 > {thr}°: 충족 오탐 {(hc[hc.y==0].head_med>thr).mean():.3f} · 전조건정상 {(hc[hc.type_key.str.startswith('473')].head_med>thr).mean():.3f} · 위반 검출 {(hc[hc.y==1].head_med>thr).mean():.3f}")
    # 하단: 다리 떨어트리기 / 바닥 닿음
    C["bot"]=C.bottoms.apply(lambda b: np.min(b) if isinstance(b,list) and len(b) else np.nan)
    print("하단 다리 들림각(트로프 최소): 다리긴장 충족 p50 %.1f · 위반 p50 %.1f · AUC %.3f"%(C[C.dropok].bot.median(),C[~C.dropok].bot.median(),roc_auc_score((~C.dropok[C.bot.notna()]).astype(int),-C.bot.dropna())))
    print("하단 다리 들림각 전조건정상 p10/p50/p90 %s"%np.round(C[C.type_key.str.startswith('473')].bot.quantile([.1,.5,.9]).values,1))
