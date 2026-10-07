import json,glob
for f in sorted(glob.glob('C:/Users/hp276/Desktop/trex/data/phone/20260925-main2/sets-20260923.jsonl')):
    for line in open(f,encoding='utf-8'):
        s=json.loads(line)
        if s['set_id']!='20260923T054352-164f08a4': continue
        for k in ['front_camera','up_from_gravity','tilt_deg','sample_interval_ms','up_flipped_frames','up_verified_frames','note','action','mode','anchor_t_ms','assessment_end_t_ms','rules_version','motion']:
            print(k, json.dumps(s.get(k),ensure_ascii=False)[:400])
        print('measurements', json.dumps(s.get('measurements'),ensure_ascii=False)[:800])
        print('results', json.dumps(s.get('results'),ensure_ascii=False)[:1500])
        fr=s['frames']; print('n frames',len(fr), 't range', fr[0]['t_ms'], fr[-1]['t_ms'])
        keys=set()
        for x in fr: keys|=set((x.get('features') or {}).keys())
        print(sorted(keys))
        a=s.get('anchor_t_ms') or 0
        nf=sum(1 for x in fr if x.get('features'))
        ready=[x['t_ms'] for x in fr if (x.get('features') or {}).get('plank_side_ready')==1]
        has_ready=sum(1 for x in fr if 'plank_side_ready' in (x.get('features') or {}))
        post=[x for x in fr if x['t_ms']>=a]
        print('features frames',nf,'ready=1 at',ready,'frames with ready key',has_ready,'frames after anchor',len(post), 'post with ready key', sum(1 for x in post if 'plank_side_ready' in (x.get('features') or {})))
        for x in fr:
            ft=x.get('features') or {}
            print(x['t_ms'], 'v', [round(v,2) for v in x['vis'][11:17]], [round(v,2) for v in x['vis'][23:29]], {k:round(ft[k],3) for k in ('plank_side_ready','hip_dev_ankle_L','hip_dev_ankle_R','knee_ang_L','knee_ang_R','hip_ang_L','plank_hip_offset','plank_head_pitch','trunk_ankle_ang','elbow_ang') if k in ft})
