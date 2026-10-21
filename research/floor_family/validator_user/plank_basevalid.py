# 설계안 1 플랭크 본인 기준 타당 범위 |med|<=0.08 이 AIHub 정상 수행자를 얼마나 거부하는가 (C뷰, 사슬 vis>=0.5, 무릎>=145)
import pandas as pd, numpy as np
d=pd.read_parquet('rel_designer/m1_frames.parquet')
p=d[(d.exercise=='플랭크')&(d.view=='C')&(d.vis_chain>=0.5)&(d.pl_knee>=145)]
c=p.groupby('clip_id').agg(med=('pl_hip','median'),ok=('몸통과 엉덩이의 정렬 유지','first'),type=('type_key','first'),n=('pl_hip','size'))
c=c[c.n>=3]
for name,s in [('충족',c[c.ok==1]),('553',c[c.type=='553']),('위반',c[c.ok==0])]:
    print(name,len(s),'|med|>0.08 %.3f'%(s.med.abs()>0.08).mean())
