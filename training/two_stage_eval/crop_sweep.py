# 2단계 자르는 방식 비교: 1단계 박스(detail.json)는 그대로 두고 크롭만 바꾼다.
#  - ctx m : 박스 주변을 m 배만큼 더 포함(주변 음식이 섞일 수 있다)
#  - gray f: 박스만 잘라 회색 캔버스 가운데에 놓아 박스가 화면의 f 만큼만 차지하게(학습 사진 중앙값 29%)
import csv, json, sys
from pathlib import Path
import numpy as np
from PIL import Image
sys.path.insert(0, str(Path(__file__).parent))
import two_stage as ts   # two_stage.py 를 먼저 돌려 out/detail.json 이 있어야 한다

detail = json.load(open(ts.OUT / 'detail.json', encoding='utf-8'))
rows = ts.rows; L = ts.LABELS

def crop_ctx(im, b, m):
    x0, y0, x1, y1 = b; mw, mh = (x1-x0)*m, (y1-y0)*m
    return im.crop((int(max(0, x0-mw)), int(max(0, y0-mh)), int(min(im.width, x1+mw)), int(min(im.height, y1+mh))))

def crop_gray(im, b, frac):
    c = im.crop(tuple(int(v) for v in b)); side = int(max(c.width, c.height) / np.sqrt(frac))
    canvas = Image.new('RGB', (side, side), (114, 114, 114))
    canvas.paste(c, ((side - c.width) // 2, (side - c.height) // 2)); return canvas

variants = [('ctx', 0.1), ('ctx', 0.3), ('ctx', 0.6), ('gray', 0.6), ('gray', 0.3), ('gray', 0.15)]
total_want = sum(len([x for x in r['expected_in_class'].split('|') if x]) for r in rows)
for det in ('world@0.1', 'coco@0.25'):
    print(f'\n=== 1단계 {det} ===')
    for kind, p in variants:
        hit = miss = extra = 0; in_top3 = 0; ranks = []
        for r in rows:
            im = ts.imgs[r['file']]; want = [x for x in r['expected_in_class'].split('|') if x]
            regs = detail[det][r['file']]; got = []; best_rank = {w: 999 for w in want}
            for g in regs:
                c = crop_ctx(im, g['box'], p) if kind == 'ctx' else crop_gray(im, g['box'], p)
                sc = ts.scores(c); order = list(np.argsort(-sc))
                if sc[order[0]] >= ts.THRESHOLD: got.append(L[order[0]])
                for w in want: best_rank[w] = min(best_rank[w], order.index(L.index(w)))
            got = list(dict.fromkeys(got))
            hit += len([g for g in got if g in want]); miss += len([w for w in want if w not in got]); extra += len([g for g in got if g not in want])
            in_top3 += sum(1 for v in best_rank.values() if v < 3)
        print(f'{kind} {p:<4}  검출 {hit}/{total_want} ({hit/total_want:.0%})  누락 {miss}  오검출 {extra}  손볼/장 {(miss+extra)/len(rows):.2f}  '
              f'| 어느 그릇에서든 정답이 후보 3위 안: {in_top3}/{total_want}')
