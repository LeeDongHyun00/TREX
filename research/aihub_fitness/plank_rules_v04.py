"""§39 플랭크 직접 정렬 규칙. 탐색 허용폭이며 출시 정확도/정상 분포 적합값으로 주장하지 않는다."""
import json
import hashlib
import math
from collections import Counter
from pathlib import Path
from plank_reference_features import features

ROOT = Path(__file__).resolve().parents[2]
paths = [ROOT/'app/src/main/assets/posture/rules_floor_v0.json',
         ROOT/'research/aihub_fitness/rules/rules_floor_v0.json']
doc = json.loads(paths[0].read_text(encoding='utf-8'))
template = next(r for r in doc['rules'] if r['exercise'] == '플랭크')
doc['rules'] = [r for r in doc['rules'] if r['exercise'] != '플랭크']
source=ROOT/'research/aihub_fitness/outputs/device_replay'
manifest={s['id']:s for s in json.loads((source/'manifest.json').read_text(encoding='utf-8'))['sequences']}
raw=(source/'results.jsonl').read_bytes()
normal={};clips={};people={}
for replay in map(json.loads,raw.splitlines()):
    s=manifest[replay['id']]
    if replay['exercise']!='플랭크' or not all(s['conditions'].values()):continue
    for frame in replay['frames']:
        for key,value in features(frame).items():
            normal.setdefault(key,[]).append(value);clips.setdefault(key,set()).add(s['clip_id']);people.setdefault(key,set()).add(s['performer'])
def quantile(xs,q):
    xs=sorted(xs);p=(len(xs)-1)*q;a=int(p);b=min(a+1,len(xs)-1)
    return xs[a]+(xs[b]-xs[a])*(p-a)
fixture=[]
for name, feature, limit in [('몸통과 엉덩이의 정렬 유지','plank_hip_offset',.06),
                             ('고개 젖힘과 숙임','plank_head_pitch',25),
                             ('목과 몸통의 정렬 유지','plank_neck_pitch',25)]:
    r = dict(template)
    margin=.015 if feature=='plank_hip_offset' else 5
    lower=min(-limit,quantile(normal[feature],.02)-margin)
    upper=max(limit,quantile(normal[feature],.98)+margin)
    # 기하학적 0을 항상 포함하고 원본 정상 표본의 편차도 허용한다. 소수 표본이므로 beta를 유지한다.
    lower=math.floor(lower*1000)/1000;upper=math.ceil(upper*1000)/1000
    fixture.append(f'{feature}\t{lower}\t{upper}')
    r.update(id='floor|플랭크|'+name, condition=name, status='beta', feature=feature, base_feature=feature,
             kind='alignment', stat='alignment', op='band',
             alignment=dict(lower=lower,upper=upper,sustain_ms=1000), threshold=upper, hold=None, personal_baseline=None,
             view_best_front='C', view_best_front_desc='몸 옆에서, 전신이 길게 보이게',
             cv_auc=None,cv_balacc=None,n=0,mp_fidelity=None,normal_median=None,normal_fpr=None,
             exploratory_clip_auc=None,
             reason='§39: 초기 자세를 빼지 않는 직접 정렬 검사. 기하학적 0을 포함하는 탐색 허용폭과 관측 조건을 통과한 Android 정상 표본 p2~p98에 여유폭을 더한 범위의 합집합. '
                    '고개 독립 정답 라벨은 없으며 2클립 정상 표본 재사용 설정이다. 독립 성능이 아니다.',
             cautions=['측면 관측 조건을 통과한 한쪽 관절만 사용', '1초 지속·경계 80% 복귀·가림 유보',
                       '고개 정답 라벨 없음. 정상 라벨 표본에서도 투영/정의 불일치 가능. 출시 검증 전 참고 안내'],
             validation_scope='기하 합성 관절·실제 Android 좌표 재생. 실제 사용자 자세 정답 정확도 미검증.')
    r['reference']={'clips':len(clips[feature]),'people':len(people[feature]),'frames':len(normal[feature]),'margin':margin,
                    'results_sha256':hashlib.sha256(raw).hexdigest(),'training_reuse':True}
    doc['rules'].append(r)
doc.update(version='floor_v0.4',generated='2026-09-11',counts=dict(Counter(r['status'] for r in doc['rules'])))
if 'plank_rules_v04.py' not in doc['source']:
    doc['source'] += ' | §39 plank_rules_v04.py: 플랭크 고개/골반 직접 정렬'
for path in paths:
    path.write_text(json.dumps(doc,ensure_ascii=False,indent=1)+'\n',encoding='utf-8')
ROOT.joinpath('app/src/test/resources/plank_alignment_limits.tsv').write_text('\n'.join(fixture)+'\n',encoding='utf-8')
print(doc['version'],doc['counts'])
print('\n'.join(fixture))

# 정상 범위 산출식과 앱 수치의 파리티, 가림/시간 판정 재생을 같은 원본 좌표로 재현한다.
replay_fixture=[]
for replay in map(json.loads,raw.splitlines()):
    if replay['exercise']!='플랭크':continue
    s=manifest[replay['id']]
    replay_fixture.append(f"CASE\t{s['clip_id']}\t{s['view']}\t{all(s['conditions'].values())}")
    for f in replay['frames']:
        expected=';'.join(f'{k}={v}' for k,v in features(f).items())
        replay_fixture.append('\t'.join(['FRAME',str(f['t_ms']),str(f['w']),str(f['h']),
            ','.join(map(str,f['xy'])),','.join(map(str,f['visibility'])),expected]))
ROOT.joinpath('app/src/test/resources/plank_replay_fixture.tsv').write_text('\n'.join(replay_fixture)+'\n',encoding='utf-8')
