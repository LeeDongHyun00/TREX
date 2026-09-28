"""국소 수정의 범위·연결·좌우 대칭을 검사하고 동일 조건 비교판을 저장한다."""
import base64
import hashlib
import json
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw
from polish_surfaces import polish_surfaces

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'outputs/muscle-surface-polish'


def load(path):
    return json.loads(path.read_text(encoding='utf-8').removeprefix('window.TREX_ATLAS=').removesuffix(';'))


before = load(OUT / 'source-backup/atlas.js')
after = load(ROOT / 'app/src/main/assets/muscle_load/atlas.js')
repeated = json.loads(json.dumps(before))
polish_surfaces(repeated)
assert repeated == after, '동일 원본으로 재생성한 결과가 다르다.'
assert len(before['parts']) == len(after['parts']) == 170
changed = []
for a, b in zip(before['parts'], after['parts']):
    assert {k: v for k, v in a.items() if k != 'p'} == {k: v for k, v in b.items() if k != 'p'}
    if a == b:
        continue
    va, vb = [np.frombuffer(base64.b64decode(p['p']), '<i2').reshape(-1, 3) / 10000 for p in (a, b)]
    faces = np.frombuffer(base64.b64decode(a['i']), '<u2').reshape(-1, 3)
    edges = np.sort(np.concatenate([faces[:, [0, 1]], faces[:, [1, 2]], faces[:, [2, 0]]]), axis=1)
    _, counts = np.unique(edges, axis=0, return_counts=True)
    degenerate = []
    for v in (va, vb):
        area2 = np.linalg.norm(np.cross(v[faces[:, 1]] - v[faces[:, 0]], v[faces[:, 2]] - v[faces[:, 0]]), axis=1)
        degenerate.append(int((area2 < 1e-10).sum()))
    assert degenerate[1] <= degenerate[0], '새 영면적 삼각형이 생겼다.'
    changed.append(dict(name=a['name'], vertices=len(vb), changedVertices=int((va != vb).any(axis=1).sum()),
                        openEdges=int((counts == 1).sum()), nonManifoldEdges=int((counts > 2).sum()),
                        degenerateBeforeAfter=degenerate))
assert len(changed) == 3
hands = [np.frombuffer(base64.b64decode(p['p']), '<i2').reshape(-1, 3) for p in after['parts'] if p['group'] == 'hand_skin']
assert np.array_equal(hands[0] * [-1, 1, 1], hands[1]), '양손 대칭이 다르다.'

for group, views in [('pelvis', ['pelvis-back', 'pelvis-oblique', 'pelvis-front']), ('hand', ['hand-outer', 'hand-front', 'hand-back'])]:
    board = Image.new('RGB', (1200, 1860), '#202426')
    draw = ImageDraw.Draw(board)
    draw.text((20, 10), 'BEFORE', fill='white'); draw.text((620, 10), 'AFTER', fill='white')
    for row, view in enumerate(views):
        for col, version in enumerate(['before', 'after']):
            im = Image.open(OUT / version / f'{view}.png').resize((600, 600))
            board.paste(im, (col * 600, 40 + row * 600))
    board.save(OUT / f'{group}-comparison.jpg', quality=94)
report = dict(parts=170, unchangedParts=167, topologyPreserved=True, deterministicFromOriginal=True,
              mirroredHands=True, changed=changed,
              atlasSHA256=hashlib.sha256((ROOT / 'app/src/main/assets/muscle_load/atlas.js').read_bytes()).hexdigest(),
              scope='앱 정적 메시의 국소 보정. 원본 리그/운동 애니메이션은 변경하지 않음. 전수 자기교차 검사는 아님.')
(OUT / 'geometry-verification.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(report, ensure_ascii=True))
