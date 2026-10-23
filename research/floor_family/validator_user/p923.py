import json
for line in open('C:/Users/hp276/Desktop/trex/data/phone/20260925-main/sets-20260923.jsonl',encoding='utf-8'):
    s=json.loads(line)
    if s.get('exercise')!='플랭크': continue
    print(s.get('set_id'), s.get('mode'), len(s['frames']), [k for k in s.keys()][:30])
    t0=s['frames'][0]['t_ms']
    for fr in s['frames']:
        f=fr.get('features',{}); v=fr.get('vis',[])
        def g(k): 
            x=f.get(k); return '' if x is None else f'{x:.3f}' if abs(x)<10 else f'{x:.0f}'
        print(f"{(fr['t_ms']-t0)/1000:5.1f} n={len(f):2d} rdy={g('plank_side_ready')} hipL={g('hip_dev_ankle_L')} hipR={g('hip_dev_ankle_R')} kneeL={g('knee_ang_L')} kneeR={g('knee_ang_R')} hipangL={g('hip_ang_L')} hip={g('plank_hip_offset')} ta={g('trunk_ankle_ang')} elbL={g('elbow_ang_L') if 'elbow_ang_L' in f else g('elbow_ang')} vAnkL={v[27] if len(v)>27 else ''} vAnkR={v[28] if len(v)>28 else ''}")
    break
