import json,glob,collections,os
seen={}
for f in sorted(glob.glob('C:/Users/hp276/Desktop/trex/data/phone/*/sets-*.jsonl')):
    for line in open(f,encoding='utf-8'):
        try: s=json.loads(line)
        except: continue
        sid=s.get('set_id') or s.get('id')
        ex=s.get('exercise')
        if sid not in seen: seen[sid]=(ex,f,s)
c=collections.Counter(v[0] for v in seen.values())
print(len(seen)); print(c)
for sid,(ex,f,s) in seen.items():
    if ex in ('플랭크','크런치','라잉 레그 레이즈','레그 레이즈','푸시업'):
        fr=s.get('frames',[])
        print(sid, ex, os.path.basename(os.path.dirname(f)), len(fr), list(s.keys())[:40])
        if fr: print('  frame keys', list(fr[0].keys()))
