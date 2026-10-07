# -*- coding: utf-8 -*-
import numpy as np, pandas as pd
F = pd.read_parquet("near_frames.parquet")
for view in "CE":
    p = F[(F.exercise == "플랭크") & (F.view == view)].copy()
    p["alok"]=p["몸통과 엉덩이의 정렬 유지"].astype(bool); p["elok"]=p["팔꿈치가 어깨보다 안쪽에 위치하지 않음"].astype(bool); p["upok"]=p["상체의 지면으로부터 충분한 거리 유지"].astype(bool)
    on=p[p.pl_knee>=145]; off=p[p.pl_knee<145]
    print(f"\n== 뷰 {view}: 프레임 {len(p)}, 무릎≥145 {len(on)/len(p):.2f}")
    print(" 투영비(어깨–발목/어깨폭) 무릎≥145: p10/p50/p90 %s · ≥8 통과 %.2f ≥5 %.2f ≥4 %.2f"%(np.round(on.pl_ratio.quantile([.1,.5,.9]).values,1),(on.pl_ratio>=8).mean(),(on.pl_ratio>=5).mean(),(on.pl_ratio>=4).mean()))
    print(" 수평성분 p10/p50 %s"%np.round(on.pl_horiz.quantile([.1,.5]).values,2))
    ok=on[on.alok]; bad=on[~on.alok]; a553=on[on.type_key.astype(str).str.startswith("553")]
    print(" 골반 오프셋(가까운 쪽) 정렬 충족 p0.5/p2/p50/p98/p99.5 %s | 위반 p50/p90 %.3f/%.3f"%(np.round(ok.pl_hip.quantile([.005,.02,.5,.98,.995]).values,3),bad.pl_hip.median(),bad.pl_hip.quantile(.9)))
    for thr in [.124,.15,.2,.25,.3]:
        print(f"   골반 > {thr}: 충족 {(ok.pl_hip>thr).mean():.3f} · 553 {(a553.pl_hip>thr).mean():.3f} · 위반 {(bad.pl_hip>thr).mean():.3f}")
    print(" 무릎<145(진입·이탈) 골반 오프셋 p10/p50/p90 %s"%np.round(off.pl_hip.quantile([.1,.5,.9]).values,3))
    print(" 팔꿈치각 무릎≥145 p10/p50/p90 %s"%np.round(on.pl_elbow.quantile([.1,.5,.9]).values,0))
    print(" 상완각(팔꿈치→발목 선) p10/p50/p90 %s"%np.round(on.pl_upperarm.quantile([.1,.5,.9]).values,0))
    # 클립 안 1 s 지속 근사: 600 ms 가정 → 연속 2프레임 띠 밖
    def runs(d,lo,hi):
        n=0;tot=0
        for cid,g in d.groupby("clip_id"):
            x=g.sort_values("frame_idx").pl_hip.to_numpy(); out=(x<lo)|(x>hi)
            tot+=1; n+= any(out[i] and out[i+1] for i in range(len(out)-1))
        return n/max(tot,1)
    for hi in [.124,.2,.25]:
        print(f"   상한 {hi}: 연속 2프레임 이탈 클립 비율 충족 {runs(ok,-.06,hi):.3f} · 553 {runs(a553,-.06,hi):.3f} · 위반 {runs(bad,-.06,hi):.3f}")
