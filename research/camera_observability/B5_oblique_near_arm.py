"""B5 — 덤벨 컬 사선(B/D) 가까운 팔 하나로 보는 벌림·앞/뒤 (docs/CURL_OBLIQUE_ELBOW_RESEARCH.md).
실행: 저장소 루트에서 `python research/camera_observability/B5_oblique_near_arm.py` (B1 의 MediaPipe 추론 산출물·AIHub kp2d 필요).
앱 정의 2D 피처(Arm2d 와 같은 식)로 정상 오탐·앞 이탈 검출을 잰다.
클립 = 한 반복(16프레임). 기준 = 그 클립의 이완(e, 가까운 팔 팔꿈치각 최대) 프레임, 판정 = 수축(c, 최소) 프레임 — 앱의 '시작 대비 수축 구간' 에 대응.
가시성 0.5 미만 관절은 앱처럼 버린다."""
import glob,sys,math
import numpy as np, pandas as pd
sys.stdout.reconfigure(encoding='utf-8')
ROOT='research/camera_observability/outputs/B1/mp'
SRC='research/aihub_fitness/outputs'
k2=pd.read_parquet(f'{SRC}/kp2d.parquet',columns=['img_key','clip_id','view_letter','frame_idx'])
k2=k2[k2.img_key!='']
lab=pd.read_csv('research/camera_observability/outputs/B4/aihub_clips.csv',encoding='utf-8-sig',
                usecols=['clip_id','session','팔꿈치 위치 고정','수축 시 어깨 으쓱 없음','척추의 중립'])
V=0.5
def P(df,i): return df[f'l{i}_x'].to_numpy()/df['h'].to_numpy(), df[f'l{i}_y'].to_numpy()/df['h'].to_numpy(), df[f'l{i}_v'].to_numpy()
def W(df,i): return np.stack([df[f'w{i}_x'],df[f'w{i}_y'],df[f'w{i}_z']],1).astype(float)
rows=[]
for fn in sorted(glob.glob(f'{ROOT}/lm_*_full.parquet')):
    d=pd.read_parquet(fn)
    d=d[d.detected==True].merge(k2,on='img_key',how='inner')
    d=d[d.view_letter.isin(['B','D'])]
    for view,g in d.groupby('view_letter'):
        near_L = (view=='D')          # D = 사용자 왼쪽 앞(왼팔꿈치가 카메라 쪽), B = 오른팔
        sh,el,wr = (11,13,15) if near_L else (12,14,16)
        fsign = -1.0 if view=='D' else 1.0   # Arm2d.forwardSign: B(yaw>0) 앞 = +x, D 앞 = −x
        xLs,yLs,vLs=P(g,11); xRs,yRs,vRs=P(g,12); xLh,yLh,vLh=P(g,23); xRh,yRh,vRh=P(g,24)
        xs,ys,vs=P(g,sh); xe,ye,ve=P(g,el); xw,yw,vw=P(g,wr)
        ok=(vLs>=V)&(vRs>=V)&(vLh>=V)&(vRh>=V)&(ve>=V)
        shX=(xLs+xRs)/2; shY=(yLs+yRs)/2; hX=(xLh+xRh)/2; hY=(yLh+yRh)/2
        dx=shX-hX; dy=shY-hY; torso=np.hypot(dx,dy)
        latDir=xLh-xRh; shGap=np.abs(xLs-xRs)
        outward=(1.0 if near_L else -1.0)*np.sign(latDir)
        lat=outward*(xe-xs)/np.where(shGap>1e-6,shGap,np.nan)
        lineX=hX+(ye-hY)/np.where(np.abs(dy)>1e-6,dy,np.nan)*dx
        fwd=fsign*(xe-lineX)/torso
        # 가까운 팔 팔꿈치각(월드) — 수축/이완 프레임 고르기
        a=W(g,sh)-W(g,el); b=W(g,wr)-W(g,el)
        ang=np.degrees(np.arccos(np.clip((a*b).sum(1)/(np.linalg.norm(a,axis=1)*np.linalg.norm(b,axis=1)+1e-9),-1,1)))
        gg=pd.DataFrame(dict(clip_id=g.clip_id.to_numpy(),frame=g.frame_idx.to_numpy(),lat=np.where(ok,lat,np.nan),fwd=np.where(ok,fwd,np.nan),ang=ang,visFar=(vRs if near_L else vLs)))
        for cid,c in gg.groupby('clip_id'):
            c=c.dropna(subset=['lat','fwd'])
            if len(c)<6: continue
            ic=c.ang.idxmin(); ie=c.ang.idxmax()
            if c.ang.max()-c.ang.min()<60: continue
            rows.append(dict(clip_id=cid,view=view,lat_c=c.lat[ic],lat_e=c.lat[ie],fwd_c=c.fwd[ic],fwd_e=c.fwd[ie]))
r=pd.DataFrame(rows).merge(lab,on='clip_id')
r['dlat']=r.lat_c-r.lat_e; r['dfwd']=r.fwd_c-r.fwd_e
r['normal']=(r['팔꿈치 위치 고정']==True)&(r['수축 시 어깨 으쓱 없음']==True)&(r['척추의 중립']==True)
r['fwdviol']=(r['팔꿈치 위치 고정']==False)
r['fixed']=(r['팔꿈치 위치 고정']==True)
for view in ('D','B'):
    s=r[r.view==view]
    n=s[s.normal]; fx=s[s.fixed]; bad=s[s.fwdviol]
    q=lambda x:[round(v,2) for v in np.percentile(x.dropna(),[1,5,50,95,99])]
    print(f'==== 뷰 {view}: 클립 {len(s)} (정상 {len(n)}, 팔꿈치 고정 충족 {len(fx)}, 앞 내밀기 위반 {len(bad)})')
    print('  Δ옆(가까운 팔, 수축−이완) 정상 p1/5/50/95/99', q(n.dlat), '| 고정 충족', q(fx.dlat), '| 앞 위반', q(bad.dlat))
    print('  Δ앞(가까운 팔)             정상', q(n.dfwd), '| 고정 충족', q(fx.dfwd), '| 앞 위반', q(bad.dfwd))
    for t in (0.10,0.15,0.20,0.25,0.30):
        print(f'   몸에서 떨어짐 Δ옆 ≥ {t:.2f}: 고정 충족 오탐 {np.mean(fx.dlat>=t):.3f} · 앞 위반 중 {np.mean(bad.dlat>=t):.3f}   |  뒤 Δ앞 ≤ −{t:.2f}: 고정 충족 {np.mean(fx.dfwd<=-t):.3f}')
    for t in (0.05,0.07,0.10,0.12,0.15):
        print(f'   앞 이탈 Δ앞 ≥ {t:.2f}: 고정 충족 오탐 {np.mean(fx.dfwd>=t):.3f} · 앞 위반 검출 {np.mean(bad.dfwd>=t):.3f}')
    # 앞 성분 원값(수축 프레임) AUC — 가까운 팔만
    from itertools import product
    a=bad.fwd_c.dropna().to_numpy(); b=fx.fwd_c.dropna().to_numpy()
    auc=np.mean(a[:,None]>b[None,:])+0.5*np.mean(a[:,None]==b[None,:])
    a2=bad.dfwd.dropna().to_numpy(); b2=fx.dfwd.dropna().to_numpy()
    auc2=np.mean(a2[:,None]>b2[None,:])
    print(f'   AUC 앞 위반 vs 고정 충족: 수축 원값 {auc:.3f} · 수축−이완 {auc2:.3f}')

print()
print('==== 수축 대 수축(세션 안 "팔꿈치 고정" 클립 수축값 중앙값 = 본인 기준) — 매 반복에 들어 있는 이완→수축 호를 지운다')
for view in ('D','B'):
    s=r[r.view==view].copy()
    ref=s[s.fixed].groupby('session')[['lat_c','fwd_c']].median().rename(columns={'lat_c':'ref_lat','fwd_c':'ref_fwd'})
    s=s.join(ref,on='session')
    s['rlat']=s.lat_c-s.ref_lat; s['rfwd']=s.fwd_c-s.ref_fwd
    fx=s[s.fixed]; bad=s[s.fwdviol]
    q=lambda x:[round(v,2) for v in np.percentile(x.dropna(),[50,95,98,99])]
    print(f'-- 뷰 {view}: 고정 충족 Δ옆 p50/95/98/99 {q(fx.rlat)} · 앞 위반 Δ옆 p50 {np.nanmedian(bad.rlat):.2f}')
    for t in (0.15,0.20,0.25,0.30,0.40): print(f'   몸에서 떨어짐 Δ옆 ≥ {t:.2f}: 고정 충족 오탐 {np.mean(fx.rlat>=t):.3f}')
    print(f'   고정 충족 Δ앞 p50/95/98/99 {q(fx.rfwd)} · 앞 위반 Δ앞 p50 {np.nanmedian(bad.rfwd):.2f}')
    for t in (0.07,0.10,0.12,0.15,0.20): print(f'   앞 이탈 Δ앞 ≥ {t:.2f}: 고정 충족 오탐 {np.mean(fx.rfwd>=t):.3f} · 앞 위반 검출 {np.mean(bad.rfwd>=t):.3f}')
    for t in (0.15,0.20,0.25): print(f'   뒤 Δ앞 ≤ −{t:.2f}: 고정 충족 오탐 {np.mean(fx.rfwd<=-t):.3f}')
