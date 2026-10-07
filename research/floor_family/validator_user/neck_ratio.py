import pandas as pd, numpy as np
r=pd.read_parquet('design/crunch_reps.parquet').dropna(subset=['trunk','ear'])
ok=r[r.scap]; bad=r[~r.scap]
def at_fpr(score_ok, score_bad, fpr=0.02):
    th=np.quantile(score_ok, fpr)  # reject if score < th
    return th, (score_ok<th).mean(), (score_bad<th).mean()
print('n ok',len(ok),'bad',len(bad))
for name,f in [('trunk',lambda d:d.trunk),('trunk/ear',lambda d:d.trunk/np.maximum(d.ear,0.05)),
               ('trunk-30*ear',lambda d:d.trunk-30*d.ear),('neck(-)',lambda d:-d.neck)]:
    for fpr in [0.02,0.05]:
        th,a,b=at_fpr(f(ok),f(bad),fpr); print(f'{name:14s} fpr目標{fpr}: th {th:.3f} 정상기각 {a:.3f} 위반검출 {b:.3f}')
# within-performer: trunk relative to performer's normal median
med=ok.groupby('performer').trunk.median()
r['rel']=r.trunk/r.performer.map(med)
ok=r[r.scap]; bad=r[~r.scap]
for fpr in [0.02,0.05]:
    th,a,b=at_fpr(ok.rel,bad.rel,fpr); print(f'본인정상중앙대비 trunk fpr{fpr}: th {th:.3f} 정상 {a:.3f} 검출 {b:.3f}')
from sklearn.metrics import roc_auc_score
y=np.r_[np.zeros(len(ok)),np.ones(len(bad))]
for c in ['trunk','rel']:
    print(c,'AUC',round(roc_auc_score(y,-np.r_[ok[c],bad[c]]),3))
print('ratio AUC', round(roc_auc_score(y,-np.r_[ok.trunk/np.maximum(ok.ear,.05),bad.trunk/np.maximum(bad.ear,.05)]),3))
