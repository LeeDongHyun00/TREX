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


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    source = ASSETS / 'atlas.js'
    atlas = json.loads(source.read_text().split('=', 1)[1].rstrip(';\n'))
    data = {'sourceSHA256': hashlib.sha256(source.read_bytes()).hexdigest(),
            'viewBox': f'0 0 {WIDTH} {HEIGHT}', 'back': project(atlas, True), 'front': project(atlas, False)}
    text = 'window.TREX_ATLAS_2D=' + json.dumps(data, separators=(',', ':')) + ';\n'
    target = ASSETS / 'atlas-2d.js'
    if args.check:
        assert target.read_text() == text, '2D 도형을 다시 생성하세요.'
    else:
        target.write_text(text)
    print(f"2D 근육도: 뒷면 {len(data['back'])}영역 / 앞면 {len(data['front'])}영역, {len(text)} bytes")


if __name__ == '__main__':
    main()
