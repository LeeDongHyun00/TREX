# 2단계 인식 실험: (1) 그릇/음식 위치 찾기 -> (2) 잘라서 기존 342종 모델로 이름 붙이기
# 실행: pip install ultralytics tensorflow-cpu pandas -> python two_stage.py (CPU 로 수 분)
# 사진·정답표는 저장소 밖(aihub74_raw/eval_real, 환경변수 TREX_EVAL_REAL 로 바꿀 수 있다). 모델은 앱 자산을 그대로 쓴다.
# 채점은 training/eval_heldout_colab.ipynb 실사용 셀과 같은 규칙(클래스 집합 기준 hit/miss/extra).
import csv, json, os, sys, time
from pathlib import Path
import numpy as np, tensorflow as tf
from PIL import Image, ImageDraw, ImageFont

REPO = Path(__file__).resolve().parents[2]
ROOT = Path(os.environ.get('TREX_EVAL_REAL', REPO.parent / 'aihub74_raw' / 'eval_real'))   # 사진 16장 + ground_truth_multi.csv (저장소 밖)
MODEL = str(REPO / 'app/src/main/assets/models/yolov8n_food.tflite')
LABELS = [l.strip() for l in open(REPO / 'app/src/main/assets/models/food_labels.txt', encoding='utf-8') if l.strip()]
THRESHOLD = 0.40
OUT = Path(__file__).parent / 'out'; OUT.mkdir(exist_ok=True)   # 산출물은 .gitignore (사용자 식단 사진이 그려진다)

interp = tf.lite.Interpreter(model_path=MODEL, num_threads=4); interp.allocate_tensors()
inp, out = interp.get_input_details()[0], interp.get_output_details()[0]
shape = list(inp['shape']); CF = shape[1] == 3 and shape[3] != 3
H, W = (shape[2], shape[3]) if CF else (shape[1], shape[2])

def raw(im):
    """앱과 같은 letterbox 전처리. (4+nc, anchors) 출력과 letterbox 변환값을 돌려준다."""
    s = min(W / im.width, H / im.height)
    nw, nh = max(1, int(im.width * s)), max(1, int(im.height * s))
    px, py = (W - nw) // 2, (H - nh) // 2
    canvas = Image.new('RGB', (W, H), (114, 114, 114))
    canvas.paste(im.resize((nw, nh), Image.BILINEAR), (px, py))
    a = np.asarray(canvas, dtype=np.float32) / 255.0
    a = a.transpose(2, 0, 1) if CF else a
    interp.set_tensor(inp['index'], a[None]); interp.invoke()
    o = interp.get_tensor(out['index'])[0]
    o = o if o.shape[0] == len(LABELS) + 4 else o.T
    return o, s, px, py

def scores(im):
    return raw(im)[0][4:, :].max(axis=1)

def nms(boxes, conf, iou_thr=0.5):
    boxes, conf = np.asarray(boxes, float), np.asarray(conf, float)
    order, keep = conf.argsort()[::-1], []
    while order.size:
        i = order[0]; keep.append(i)
        xx0 = np.maximum(boxes[i, 0], boxes[order[1:], 0]); yy0 = np.maximum(boxes[i, 1], boxes[order[1:], 1])
        xx1 = np.minimum(boxes[i, 2], boxes[order[1:], 2]); yy1 = np.minimum(boxes[i, 3], boxes[order[1:], 3])
        inter = np.clip(xx1 - xx0, 0, None) * np.clip(yy1 - yy0, 0, None)
        area = lambda b: (b[..., 2] - b[..., 0]) * (b[..., 3] - b[..., 1])
        iou = inter / (area(boxes[i]) + area(boxes[order[1:]]) - inter + 1e-9)
        order = order[1:][iou < iou_thr]
    return keep

def tidy(boxes, conf, w, h):
    """겹침 정리: 같은 그릇을 가리키는 박스를 하나로. 화면 60% 넘는 박스(식탁 전체)는 버린다."""
    if not boxes: return []
    keep = nms(boxes, conf, 0.5)
    b = [boxes[i] for i in keep if (boxes[i][2]-boxes[i][0])*(boxes[i][3]-boxes[i][1]) <= 0.6*w*h]
    # 다른 박스 안에 85% 이상 들어간 작은 박스는 그 박스와 같은 그릇으로 본다
    area = lambda r: max(1e-9, (r[2]-r[0])*(r[3]-r[1]))
    res = []
    for i, r in enumerate(b):
        inside = False
        for j, q in enumerate(b):
            if i == j or area(q) <= area(r): continue
            ix = max(0, min(r[2], q[2]) - max(r[0], q[0])) * max(0, min(r[3], q[3]) - max(r[1], q[1]))
            if ix / area(r) >= 0.85: inside = True; break
        if not inside: res.append(r)
    return res

# ---- 1단계 후보들 ----
_world = {}
def world(im, conf):
    from ultralytics import YOLOWorld
    if 'm' not in _world:
        m = YOLOWorld('yolov8l-worldv2.pt')
        m.set_classes(['food', 'bowl of food', 'plate of food', 'dish', 'bowl', 'plate', 'rice', 'soup', 'side dish'])
        _world['m'] = m
    r = _world['m'].predict(im, conf=conf, verbose=False, imgsz=960)[0]
    return r.boxes.xyxy.numpy().tolist(), r.boxes.conf.numpy().tolist()

_coco = {}
COCO_FOOD = {45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55}  # bowl + 음식류 (cup·dining table 제외)
def coco(im, conf):
    from ultralytics import YOLO
    if 'm' not in _coco: _coco['m'] = YOLO('yolo11x.pt')
    r = _coco['m'].predict(im, conf=conf, verbose=False, imgsz=960)[0]
    sel = [i for i, c in enumerate(r.boxes.cls.numpy().astype(int)) if c in COCO_FOOD]
    return r.boxes.xyxy.numpy()[sel].tolist(), r.boxes.conf.numpy()[sel].tolist()

def ours(im, conf):
    """우리 342종 모델의 박스를 종류 무시하고 '음식 위치'로만 쓴다."""
    o, s, px, py = raw(im)
    best = o[4:, :].max(axis=0); sel = np.where(best >= conf)[0]
    cx, cy, bw, bh = o[0, sel], o[1, sel], o[2, sel], o[3, sel]
    if cx.max(initial=0) <= 1.5: cx, cy, bw, bh = cx*W, cy*H, bw*W, bh*H   # 정규화 출력 대응
    x0 = (cx - bw/2 - px) / s; y0 = (cy - bh/2 - py) / s; x1 = (cx + bw/2 - px) / s; y1 = (cy + bh/2 - py) / s
    b = np.stack([x0, y0, x1, y1], 1) if sel.size else np.zeros((0, 4))
    b[:, [0, 2]] = b[:, [0, 2]].clip(0, im.width); b[:, [1, 3]] = b[:, [1, 3]].clip(0, im.height)
    return b.tolist(), best[sel].tolist()

# ---- 2단계: 잘라서 이름 붙이기 ----
def label_regions(im, regions, margin=0.10):
    res = []
    for (x0, y0, x1, y1) in regions:
        mw, mh = (x1-x0)*margin, (y1-y0)*margin
        c = im.crop((int(max(0, x0-mw)), int(max(0, y0-mh)), int(min(im.width, x1+mw)), int(min(im.height, y1+mh))))
        sc = scores(c); i = int(sc.argmax())
        res.append({'box': [x0, y0, x1, y1], 'name': LABELS[i], 'score': float(sc[i])})
    return res

def draw(im, regs, path, want):
    im = im.copy(); d = ImageDraw.Draw(im)
    try: f = ImageFont.truetype(r'C:\Windows\Fonts\malgun.ttf', max(18, im.width // 40))
    except Exception: f = None
    for r in regs:
        ok = r['score'] >= THRESHOLD
        col = (0, 200, 0) if ok and r['name'] in want else ((230, 120, 0) if ok else (160, 160, 160))
        d.rectangle(r['box'], outline=col, width=max(3, im.width // 250))
        d.text((r['box'][0]+6, r['box'][1]+4), f"{r['name'] if ok else '?'} {r['score']:.2f}", fill=col, font=f,
               stroke_width=2, stroke_fill=(0, 0, 0))
    im.save(path, quality=85)

rows = list(csv.DictReader(open(ROOT / 'ground_truth_multi.csv', encoding='utf-8')))
imgs = {r['file']: Image.open(ROOT / 'images' / r['file']).convert('RGB') for r in rows}
if __name__ == '__main__':
    full = {f: scores(im) for f, im in imgs.items()}

    def score_sets(got_by_file):
        hit = miss = extra = 0
        for r in rows:
            want = [x for x in r['expected_in_class'].split('|') if x]; got = got_by_file[r['file']]
            hit += len([g for g in got if g in want]); miss += len([w for w in want if w not in got]); extra += len([g for g in got if g not in want])
        return hit, miss, extra

    total_want = sum(len([x for x in r['expected_in_class'].split('|') if x]) for r in rows)
    total_raw = sum(len([x for x in r['expected_raw'].split('|') if x]) for r in rows)
    summary = []
    def report(name, got_by_file, n_regions=None, ms=None):
        h, m, e = score_sets(got_by_file)
        summary.append({'방식': name, '검출': h, '검출률': f'{h/total_want:.0%}', '누락': m, '오검출': e,
                        '손볼/장': round((m+e)/len(rows), 2), '영역/장': n_regions, 'ms/장': ms})

    # 기준선: 앱과 같은 전체 1회 (9/25, 오검출 12 가 나와야 한다)
    report('전체 1회(기준선)', {f: [LABELS[i] for i in np.argsort(-s) if s[i] >= THRESHOLD][:5] for f, s in full.items()}, None, None)

    detail = {}
    for det_name, fn, confs in [('ours', ours, [0.10, 0.20]), ('coco', coco, [0.10, 0.25]), ('world', world, [0.05, 0.10, 0.20])]:
        for conf in confs:
            t0 = time.time(); per, nreg = {}, 0
            for f, im in imgs.items():
                b, c = fn(im, conf); regs = label_regions(im, tidy(b, c, im.width, im.height))
                per[f] = regs; nreg += len(regs)
            ms = round((time.time() - t0) * 1000 / len(imgs))
            crops = {f: list(dict.fromkeys(r['name'] for r in regs if r['score'] >= THRESHOLD)) for f, regs in per.items()}
            fullset = {f: [LABELS[i] for i in np.argsort(-s) if s[i] >= THRESHOLD][:5] for f, s in full.items()}
            union = {f: list(dict.fromkeys(fullset[f] + crops[f])) for f in imgs}
            report(f'{det_name}@{conf} 잘라서만', crops, round(nreg/len(imgs), 1), ms)
            report(f'{det_name}@{conf} 전체+잘라서', union, round(nreg/len(imgs), 1), ms)
            detail[f'{det_name}@{conf}'] = per
            for r in rows:
                want = [x for x in r['expected_in_class'].split('|') if x]
                draw(imgs[r['file']], per[r['file']], OUT / f"{det_name}_{conf}_{r['file']}", want)
            print(det_name, conf, 'done', flush=True)

    import pandas as pd
    df = pd.DataFrame(summary); pd.set_option('display.width', 200)
    print(df.to_string(index=False))
    print(f'\n맞힐 수 있는 음식 {total_want}개 / 사진 속 음식 전체 {total_raw}개 ({total_raw/len(rows):.1f}개/장)')
    json.dump(detail, open(OUT / 'detail.json', 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    df.to_csv(OUT / 'summary.csv', index=False, encoding='utf-8-sig')
