# 폰용(tflite)으로 변환한 1단계 후보를 PC 에서 그대로 돌려 비교한다.
# 준비: two_stage.py 를 먼저 돌려 out/detail.json(YOLO-World L 박스) 생성 -> export_onnx.py -> onnx_to_tflite.py
# 2단계·채점은 training/two_stage_eval/two_stage.py 를 그대로 쓴다.
import json, sys, time
from pathlib import Path
import numpy as np, tensorflow as tf
from PIL import Image
sys.path.insert(0, r'C:\Workspace\TREX\TREX_UI\training\two_stage_eval')
import two_stage as ts

def crop_ctx(im, b, m):
    x0, y0, x1, y1 = b; mw, mh = (x1-x0)*m, (y1-y0)*m
    return im.crop((int(max(0, x0-mw)), int(max(0, y0-mh)), int(min(im.width, x1+mw)), int(min(im.height, y1+mh))))
def crop_gray(im, b, frac):
    c = im.crop(tuple(int(v) for v in b)); side = int(max(c.width, c.height) / np.sqrt(frac))
    canvas = Image.new('RGB', (side, side), (114, 114, 114)); canvas.paste(c, ((side - c.width) // 2, (side - c.height) // 2)); return canvas

HERE = Path(__file__).parent
REF = json.load(open(ts.OUT / 'detail.json', encoding='utf-8'))['world@0.1']   # PC 원본 YOLO-World L 박스(눈으로 확인한 기준)
COCO_FOOD = sorted(ts.COCO_FOOD)

class Region:
    def __init__(self, path, nc, keep=None):
        self.it = tf.lite.Interpreter(model_path=str(path), num_threads=4); self.it.allocate_tensors()
        self.i = self.it.get_input_details()[0]; self.o = self.it.get_output_details()
        self.nc, self.keep = nc, keep
        sh = self.i['shape']; self.S = sh[1] if sh[3] == 3 else sh[2]; self.nhwc = sh[3] == 3
    def __call__(self, im, conf):
        S = self.S; s = min(S / im.width, S / im.height); nw, nh = int(im.width * s), int(im.height * s)
        px, py = (S - nw) // 2, (S - nh) // 2
        c = Image.new('RGB', (S, S), (114, 114, 114)); c.paste(im.resize((nw, nh), Image.BILINEAR), (px, py))
        a = np.asarray(c, np.float32)[None] / 255.0
        if not self.nhwc: a = a.transpose(0, 3, 1, 2)
        self.it.set_tensor(self.i['index'], a); self.it.invoke()
        outs = [self.it.get_tensor(d['index'])[0] for d in self.o]
        o = next(x for x in outs if x.ndim == 2 and 8400 in x.shape)
        if o.shape[0] == 8400: o = o.T
        cls = o[4:4 + self.nc]
        if self.keep is not None: cls = cls[self.keep]
        best = cls.max(0); sel = np.where(best >= conf)[0]
        cx, cy, w, h = o[0, sel], o[1, sel], o[2, sel], o[3, sel]
        if o[0].max() <= 1.5: cx, cy, w, h = cx * S, cy * S, w * S, h * S
        b = np.stack([(cx - w/2 - px) / s, (cy - h/2 - py) / s, (cx + w/2 - px) / s, (cy + h/2 - py) / s], 1)
        b[:, [0, 2]] = b[:, [0, 2]].clip(0, im.width); b[:, [1, 3]] = b[:, [1, 3]].clip(0, im.height)
        return b.tolist(), best[sel].tolist()

def iou(a, b):
    ix = max(0, min(a[2], b[2]) - max(a[0], b[0])) * max(0, min(a[3], b[3]) - max(a[1], b[1]))
    ar = lambda r: (r[2]-r[0])*(r[3]-r[1]); return ix / (ar(a) + ar(b) - ix + 1e-9)

def evaluate(tag, det, conf):
    tw = 0; hit = {'gray': [0, 0, 0, 0], 'ctx': [0, 0, 0, 0]}; ref_hit = ref_n = nreg = 0; t_det = 0
    for r in ts.rows:
        im = ts.imgs[r['file']]; want = [x for x in r['expected_in_class'].split('|') if x]; tw += len(want)
        t0 = time.perf_counter(); b, c = det(im, conf); t_det += time.perf_counter() - t0
        regs = ts.tidy(b, c, im.width, im.height); nreg += len(regs)
        ref = [g['box'] for g in REF[r['file']]]; ref_n += len(ref)
        ref_hit += sum(1 for q in ref if any(iou(q, x) >= 0.5 for x in regs))
        for kind in ('gray', 'ctx'):
            got, best_rank = [], {w: 999 for w in want}
            for x in regs:
                cr = crop_gray(im, x, 0.3) if kind == 'gray' else crop_ctx(im, x, 0.6)
                sc = ts.scores(cr); order = list(np.argsort(-sc))
                if sc[order[0]] >= ts.THRESHOLD: got.append(ts.LABELS[order[0]])
                for w in want: best_rank[w] = min(best_rank[w], order.index(ts.LABELS.index(w)))
            got = list(dict.fromkeys(got)); h = hit[kind]
            h[0] += len([g for g in got if g in want]); h[1] += len([w for w in want if w not in got])
            h[2] += len([g for g in got if g not in want]); h[3] += sum(1 for v in best_rank.values() if v < 3)
    n = len(ts.rows)
    g, x = hit['gray'], hit['ctx']
    print(f"{tag:<34} conf {conf:<5} 영역/장 {nreg/n:4.1f} | L박스 재현 {ref_hit}/{ref_n} ({ref_hit/ref_n:.0%}) | "
          f"회색30%: 검출 {g[0]}/{tw} 오검 {g[2]} 후보3 {g[3]} | 주변60%: 검출 {x[0]}/{tw} 오검 {x[2]} 후보3 {x[3]} | PC {t_det*1000/n:.0f}ms", flush=True)

D = HERE
cands = [
    ('world-S fp16', D / 'tf_yolov8s-worldv2_food/yolov8s-worldv2_food_float16.tflite', 9, None),
    ('world-S int8(dyn)', D / 'tf_yolov8s-worldv2_food/yolov8s-worldv2_food_dynamic_range_quant.tflite', 9, None),
    ('world-M fp16', D / 'tf_yolov8m-worldv2_food/yolov8m-worldv2_food_float16.tflite', 9, None),
    ('yoloe-S fp16', D / 'tf_yoloe-11s-seg/yoloe-11s-seg_float16.tflite', 9, None),
    ('coco yolo11n fp16', D / 'tf_yolo11n/yolo11n_float16.tflite', 80, COCO_FOOD),
    ('coco yolo11s fp16', D / 'tf_yolo11s/yolo11s_float16.tflite', 80, COCO_FOOD),
]
only = sys.argv[1:] or None
for tag, p, nc, keep in cands:
    if only and not any(o in tag for o in only): continue
    det = Region(p, nc, keep)
    for conf in ((0.05, 0.1, 0.2) if keep is None else (0.1, 0.25)):
        evaluate(tag, det, conf)
