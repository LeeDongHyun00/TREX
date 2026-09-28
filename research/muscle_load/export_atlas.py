"""검토된 차렷 모델에서 앱 표시용 정적 메시를 굽는다. 원본/리그를 수정하지 않는다."""
from pathlib import Path
import base64, gzip, json, re, shutil, hashlib
import numpy as np
from polish_surfaces import polish_surfaces

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / 'outputs/muscle-mode-neutral/anatomy-inline.b64'
OUT = ROOT / 'app/src/main/assets/muscle_load'
OUT.mkdir(parents=True, exist_ok=True)
d = json.loads(gzip.decompress(base64.b64decode(SRC.read_text())))
pose = np.array(d['clips']['rest']['frames'][0])
groups = dict(rectus='QUADS', vastus_l='QUADS', vastus_m='QUADS', vastus_i='QUADS',
    glute_max='GLUTES', glute_med='ABDUCTORS', glute_min='ABDUCTORS', adductors='ADDUCTORS',
    hamstrings='HAMSTRINGS', gastro='CALVES', soleus='CALVES', abs='ABS', obliques='OBLIQUES',
    erectors='ERECTORS', pecs='PECS', deltoids='DELTOIDS', lats='LATS', traps='TRAPS',
    serratus='SERRATUS', biceps='BICEPS', triceps='TRICEPS', sartorius='HIP_FLEXORS')
parts = []
for part in d['parts']:
    name, group = part['name'], part['group']
    if group == 'equipment': continue
    if group == 'bones' and not any(t in name.lower() for t in ['hand','foot','carpal','tarsal','patella','clavicle','talus','calcaneus']): continue
    p = np.frombuffer(base64.b64decode(part['p']), '<i2').reshape(-1,3).astype(float) / d['scale']
    joints = np.frombuffer(base64.b64decode(part['j']), 'u1').reshape(-1,4)
    w = np.frombuffer(base64.b64decode(part['w']), 'u1').reshape(-1,4).astype(float)
    w /= w.sum(axis=1,keepdims=True)
    baked = np.zeros_like(p)
    for k in range(4):
        q = pose[joints[:,k],3:7]; v = q[:,:3]; t = 2*np.cross(v,p)
        baked += (p + q[:,3:4]*t + np.cross(v,t) + pose[joints[:,k],:3]) * w[:,k:k+1]
    baked = baked[:,[0,2,1]] * [1,1,-1]
    region = groups.get(group)
    if group == 'other':
        if 'Brachialis' in name: region='BICEPS'
        elif re.search('Brachioradialis|pronator|carpi|Palmaris|digitorum superficialis',name,re.I) or (name.startswith('Extensor digitorum.')): region='FOREARMS'
        elif 'Tensor fasciae' in name: region='ABDUCTORS'
    side = 'L' if name.endswith('.l') or name.endswith('.L') else 'R' if name.endswith('.r') or name.endswith('.R') else ''
    parts.append(dict(name=name, group=group, muscle=region, side=side,
        p=base64.b64encode(np.rint(baked*10000).astype('<i2').tobytes()).decode(),i=part['i']))
payload=dict(scale=10000,parts=parts)
surface_report = polish_surfaces(payload)
(OUT/'atlas.js').write_text('window.TREX_ATLAS='+json.dumps(payload,separators=(',',':'))+';',encoding='utf-8')
shutil.copy2(ROOT/'outputs/muscle-atlas-3d/.tools/three.min.js',OUT/'three.min.js')
shutil.copy2(ROOT/'outputs/muscle-mode/sources/License.txt',OUT/'Z-Anatomy-LICENSE.txt')
shutil.copy2(ROOT/'outputs/muscle-mode/sources/provenance.json',OUT/'provenance.json')
(OUT/'NOTICE.txt').write_text('''TREX 해부학 파생 표시 자산 — CC BY-SA 4.0
Z-Anatomy https://github.com/Z-Anatomy/Models-of-human-anatomy
BodyParts3D — Database Center for Life Science, CC BY-SA 2.1 Japan
https://creativecommons.org/licenses/by-sa/4.0/
TREX 변경: 근육별 분리, 표면 정리, 리깅, 손 방향/차렷 자세, 정적 경량화.
atlas.js는 수정 가능한 정점/삼각형과 근육 식별자를 포함한다.
얼굴·손·발 피부: MakeHuman CC0. Three.js: MIT (라이브러리 헤더 참조).
남성 기준 해부 형상이며 개인/성별 근육량을 측정한 모델이 아니다.
HIP_FLEXORS 색은 모델에 있는 봉공근만 연결한다. 장요근 전체를 보여 주지 않는다.
''',encoding='utf-8')
print(json.dumps({'parts':len(parts),'bytes':(OUT/'atlas.js').stat().st_size,'sourceSHA256':hashlib.sha256(SRC.read_bytes()).hexdigest(), 'surfaceCorrections':surface_report}))
