"""기존 해부 모델을 앞·뒷면 2D 도형으로 굽는다. 런타임에는 3D/WebGL이 필요 없다.

재생성: pip install numpy opencv-python-headless
        python3 research/muscle_load/export_atlas_2d.py
정점의 깊이 버퍼로 가려진 면을 제거한 뒤 가시 영역의 윤곽만 SVG 경로로 저장한다.
원본 atlas.js와 근육/좌우 식별자를 보존한다. 파생 도형도 CC BY-SA 4.0이다.
"""
import argparse
import base64
import hashlib
import json
from pathlib import Path

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'app/src/main/assets/muscle_load'
WIDTH, HEIGHT, SCALE = 360, 1200, 640


def contours(mask):
    rings, _ = cv2.findContours(mask.astype(np.uint8), cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
    paths = []
    for ring in rings:
        if abs(cv2.contourArea(ring)) < 3:
            continue
        points = cv2.approxPolyDP(ring, .65, True).reshape(-1, 2)
        if len(points) >= 3:
            paths.append('M' + 'L'.join(f'{x},{y}' for x, y in points) + 'Z')
    return ''.join(paths)


def project(atlas, back):
    depth = np.full((HEIGHT, WIDTH), -np.inf)
    regions = np.zeros((HEIGHT, WIDTH), dtype=np.uint16)
    keys = ['']
    sign = -1 if back else 1
    for part in atlas['parts']:
        key = f"{part['muscle']}_{part['side']}" if part['muscle'] and part['side'] else 'skin'
        if key not in keys:
            keys.append(key)
        region = keys.index(key)
        vertices = np.frombuffer(base64.b64decode(part['p']), '<i2').reshape(-1, 3) / atlas['scale']
        points = np.column_stack((WIDTH / 2 + sign * vertices[:, 0] * SCALE,
                                  20 + (1.8 - vertices[:, 1]) * SCALE, sign * vertices[:, 2]))
        faces = np.frombuffer(base64.b64decode(part['i']), '<u2').reshape(-1, 3)
        for triangle in points[faces]:
            (ax, ay, az), (bx, by, bz), (cx, cy, cz) = triangle
            denom = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
            if abs(denom) < 1e-8:
                continue
            x0, x1 = max(0, int(np.floor(triangle[:, 0].min()))), min(WIDTH - 1, int(np.ceil(triangle[:, 0].max())))
            y0, y1 = max(0, int(np.floor(triangle[:, 1].min()))), min(HEIGHT - 1, int(np.ceil(triangle[:, 1].max())))
            if x0 > x1 or y0 > y1:
                continue
            yy, xx = np.mgrid[y0:y1+1, x0:x1+1]
            u = ((by-cy)*(xx+.5-cx)+(cx-bx)*(yy+.5-cy))/denom
            v = ((cy-ay)*(xx+.5-cx)+(ax-cx)*(yy+.5-cy))/denom
            w = 1-u-v
            z = u*az+v*bz+w*cz
            previous = depth[y0:y1+1, x0:x1+1]
            visible = (u >= 0) & (v >= 0) & (w >= 0) & (z > previous)
            previous[visible] = z[visible]
            regions[y0:y1+1, x0:x1+1][visible] = region
    shapes = [{'key': '', 'path': contours(regions != 0)}]
    for index, key in enumerate(keys):
        if key in ('', 'skin'):
            continue
        path = contours(regions == index)
        if path:
            shapes.append({'key': key, 'path': path})
    return shapes


# §130(2026-11-07): 3D 모델에 없는 근육 4종의 손 그린 근사 영역. 덮는 근육(승모근·삼각근·복사근·종아리) 위에 겹쳐 그린다.
# L 좌표만 정의하고 R 은 x → 360−x 거울. 뷰어는 뒤에 온 도형을 위에 칠한다.
MANUAL_REGIONS = {
    'front': {
        'TIBIALIS': [[265, 936], [265, 970], [261, 1010], [254, 1050], [246, 1090], [233, 1090], [234, 1050], [235, 1010], [233, 970], [234, 936]],
    },
    'back': {
        'RHOMBOIDS': [[178, 252], [156, 262], [142, 300], [146, 345], [160, 368], [178, 372]],
        'ROTATOR_CUFF': [[104, 262], [150, 270], [148, 326], [118, 338], [98, 306]],
        'QUADRATUS_LUMBORUM': [[152, 438], [124, 446], [118, 516], [152, 522]],
    },
}


def manual_shapes(view):
    def path(points):
        return 'M' + 'L'.join(f'{x},{y}' for x, y in points) + 'Z'
    shapes = []
    for key, points in MANUAL_REGIONS[view].items():
        shapes.append({'key': key + '_L', 'path': path(points)})
        shapes.append({'key': key + '_R', 'path': path([(WIDTH - x, y) for x, y in points])})
    return shapes


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    source = ASSETS / 'atlas.js'
    atlas = json.loads(source.read_text().split('=', 1)[1].rstrip(';\n'))
    data = {'sourceSHA256': hashlib.sha256(source.read_bytes()).hexdigest(),
            'viewBox': f'0 0 {WIDTH} {HEIGHT}', 'back': project(atlas, True) + manual_shapes('back'),
            'front': project(atlas, False) + manual_shapes('front'),
            'manual': {'keys': list(MANUAL_REGIONS['front']) + list(MANUAL_REGIONS['back']),
                       'note': '§130 손 그린 근사 영역(3D 모델에 없는 근육) — 덮는 근육 위에 겹쳐 그린다'}}
    text = 'window.TREX_ATLAS_2D=' + json.dumps(data, separators=(',', ':')) + ';\n'
    target = ASSETS / 'atlas-2d.js'
    if args.check:
        assert target.read_text(encoding='utf-8') == text, '2D 도형을 다시 생성하세요.'
    else:
        target.write_text(text, encoding='utf-8', newline='\n')
    print(f"2D 근육도: 뒷면 {len(data['back'])}영역 / 앞면 {len(data['front'])}영역, {len(text)} bytes")


if __name__ == '__main__':
    main()
