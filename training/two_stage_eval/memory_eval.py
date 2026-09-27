# 내 음식 기억 실험: 자리 그림의 특징값(임베딩)으로 "기억한 음식을 다시 알아보는가" 를 잰다.
#
# 절차: 음식 이미지마다 앱과 같은 방식으로 자리를 하나 잘라(위치 모델 최고 점수 자리, 둘레 10%) 특징값을 뽑는다.
#  - 아는 음식(known) 클래스마다 K장을 "기억" 으로 저장하고, 나머지 사진을 질의로 쓴다.
#  - 모르는 음식(unknown) 클래스 사진도 질의로 섞는다 — 기억에 없는 음식을 잘못 끌어오는 비율을 보려고.
#  - 질의마다 가장 비슷한 기억(코사인)을 찾아, 임계값 t 이상이면 "자동으로 이름 붙임" 으로 본다.
# 보는 것: 자동 이름의 정확도(precision), 아는 음식 중 자동으로 붙은 비율(coverage), 모르는 음식에 잘못 붙은 비율.
#
# 실행: python memory_eval.py aihub | food101   (two_stage_eval 의 venv — tensorflow·pillow)
import csv, os, random, sys, time
from pathlib import Path
import numpy as np, tensorflow as tf
from PIL import Image

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
import two_stage as ts   # 342종 모델(점수 벡터 후보)

REGION_MODEL = ts.REPO / 'app/src/main/assets/models/food_region.tflite'
random.seed(7)

# ---------------- 위치 모델로 자리 하나 자르기(앱과 같은 decode·tidy 를 파이썬으로)
_rit = tf.lite.Interpreter(model_path=str(REGION_MODEL), num_threads=4); _rit.allocate_tensors()
_ri, _ro = _rit.get_input_details()[0], _rit.get_output_details()[0]
def best_spot(im):
    S = 640; s = min(S / im.width, S / im.height); nw, nh = int(im.width * s), int(im.height * s); px, py = (S - nw) // 2, (S - nh) // 2
    c = Image.new('RGB', (S, S), (114, 114, 114)); c.paste(im.resize((nw, nh), Image.BILINEAR), (px, py))
    _rit.set_tensor(_ri['index'], (np.asarray(c, np.float32) / 255.0)[None]); _rit.invoke()
    o = _rit.get_tensor(_ro['index'])[0]
    best = o[4:].max(0); sel = np.where(best >= 0.05)[0]
    if sel.size == 0: return im
    cx, cy, w, h = o[0, sel], o[1, sel], o[2, sel], o[3, sel]
    b = np.stack([(cx - w/2 - px) / s, (cy - h/2 - py) / s, (cx + w/2 - px) / s, (cy + h/2 - py) / s], 1)
    b[:, [0, 2]] = b[:, [0, 2]].clip(0, im.width); b[:, [1, 3]] = b[:, [1, 3]].clip(0, im.height)
    regs = ts.tidy(b.tolist(), best[sel].tolist(), im.width, im.height)
    if not regs: return im
    x0, y0, x1, y1 = regs[0]; pw, ph = (x1 - x0) * 0.1, (y1 - y0) * 0.1
    return im.crop((int(max(0, x0 - pw)), int(max(0, y0 - ph)), int(min(im.width, x1 + pw)), int(min(im.height, y1 + ph))))

# ---------------- 특징값 후보
def keras_embedder(name):
    app = {'mnv3s': tf.keras.applications.MobileNetV3Small, 'mnv3l': tf.keras.applications.MobileNetV3Large,
           'effb0': tf.keras.applications.EfficientNetB0}[name]
    m = app(include_top=False, pooling='avg', weights='imagenet', input_shape=(224, 224, 3))
    def f(im):
        a = np.asarray(im.convert('RGB').resize((224, 224), Image.BILINEAR), np.float32)[None]
        return m(a, training=False).numpy()[0]
    return f

def clip_embedder(name='MobileCLIP2-S0', pretrained='dfndr2b'):
    import open_clip, torch
    model, _, preprocess = open_clip.create_model_and_transforms(name, pretrained=pretrained)
    model.eval()
    def f(im):
        with torch.no_grad():
            return model.encode_image(preprocess(im.convert('RGB'))[None]).numpy()[0]
    return f

def dino_embedder():
    import timm, torch
    m = timm.create_model('vit_small_patch14_dinov2.lvd142m', pretrained=True, num_classes=0, img_size=224).eval()
    cfg = timm.data.resolve_data_config({'input_size': (3, 224, 224)}, model=m); tf_ = timm.data.create_transform(**cfg)
    def f(im):
        with torch.no_grad():
            return m(tf_(im.convert('RGB'))[None]).numpy()[0]
    return f

def food342_embedder():
    def f(im):
        return ts.scores(_gray30(im))
    return f

def _gray30(im):
    side = int(max(im.width, im.height) / np.sqrt(0.3)); c = Image.new('RGB', (side, side), (114, 114, 114))
    c.paste(im, ((side - im.width) // 2, (side - im.height) // 2)); return c

# ---------------- 데이터
def load_aihub(n_known=40, n_unknown=20, per_class=12):
    root = ts.REPO.parent / 'aihub74_raw' / 'eval_val'
    rows = list(csv.DictReader(open(root / 'ground_truth.csv', encoding='utf-8')))
    by = {}
    for r in rows: by.setdefault(r['class_name'], []).append(root / 'images' / r['file'])
    classes = [c for c, v in by.items() if len(v) >= per_class]
    random.shuffle(classes)
    pick = classes[:n_known + n_unknown]
    return {c: random.sample(by[c], per_class) for c in pick}, pick[:n_known], pick[n_known:]

def load_food101(n_known=40, n_unknown=20, per_class=12):
    root = ts.REPO.parent / 'extra_data' / 'food-101' / 'images'
    classes = sorted(os.listdir(root)); random.shuffle(classes)
    pick = classes[:n_known + n_unknown]
    return {c: [root / c / f for f in random.sample(sorted(os.listdir(root / c)), per_class)] for c in pick}, pick[:n_known], pick[n_known:]

def run(source):
    n_known = int(os.environ.get('N_KNOWN', '40'))
    data, known, unknown = (load_aihub if source == 'aihub' else load_food101)()
    known = known[:n_known]
    t0 = time.time()
    # 자리 자르기는 느려서(수 분) 잘라낸 그림을 저장해 다시 쓴다.
    cache = HERE / 'out' / f'spots_{source}'; cache.mkdir(parents=True, exist_ok=True)
    spots = {}
    for ci, (c, ps) in enumerate(data.items()):
        spots[c] = []
        for i, p in enumerate(ps):
            f = cache / f'{ci:03d}_{i:02d}.jpg'
            if not f.exists(): best_spot(Image.open(p).convert('RGB')).save(f, quality=92)
            spots[c].append(Image.open(f).convert('RGB'))
    print(f'{source}: 클래스 {len(data)} (아는 {len(known)} / 모르는 {len(unknown)}), 자리 자르기 {time.time()-t0:.0f}s', flush=True)
    makers = {'mnv3s': lambda: keras_embedder('mnv3s'), 'mnv3l': lambda: keras_embedder('mnv3l'), 'effb0': lambda: keras_embedder('effb0'),
              'food342': food342_embedder, 'mclip2s0': clip_embedder, 'dinov2s': dino_embedder}
    only = sys.argv[2].split(',') if len(sys.argv) > 2 else list(makers)
    for ename in only:
        emb = makers[ename]()
        E = {c: np.stack([emb(im) for im in ims]) for c, ims in spots.items()}
        E = {c: v / (np.linalg.norm(v, axis=1, keepdims=True) + 1e-9) for c, v in E.items()}
        for K in (1, 3):
            mem = [(c, E[c][i]) for c in known for i in range(K)]
            M = np.stack([v for _, v in mem]); names = [c for c, _ in mem]
            res = []   # (sim, correct?, is_known)
            for c in known:
                for v in E[c][K:]:
                    s = M @ v; j = int(s.argmax()); res.append((float(s[j]), names[j] == c, True))
            for c in unknown:
                for v in E[c]:
                    s = M @ v; j = int(s.argmax()); res.append((float(s[j]), False, False))
            kn = [r for r in res if r[2]]; un = [r for r in res if not r[2]]
            top1 = np.mean([r[1] for r in kn])
            # 후보 3위 안: 질의마다 이름별 최고 유사도로 줄 세워 상위 3개 이름에 정답이 드는가
            hit3 = []
            for c in known:
                for v in E[c][K:]:
                    s = M @ v; best = {}
                    for n, x in zip(names, s): best[n] = max(best.get(n, -1), float(x))
                    hit3.append(c in sorted(best, key=best.get, reverse=True)[:3])
            line = f'  {ename:8} K={K} 기억{len(known)}종  1위 {top1:.0%} · 3위 안 {np.mean(hit3):.0%}'
            for t in sorted({round(float(q), 2) for q in np.quantile([r[0] for r in res], [0.5, 0.7, 0.8, 0.9, 0.95])}):
                auto = [r for r in res if r[0] >= t]
                prec = np.mean([r[1] for r in auto]) if auto else float('nan')
                cov = np.mean([r[0] >= t and r[1] for r in kn])
                fa = np.mean([r[0] >= t for r in un])
                line += f' | t={t:.2f} 정확 {prec:.0%} 덮음 {cov:.0%} 오붙임(모름) {fa:.0%}'
            print(line, flush=True)

if __name__ == '__main__':
    run(sys.argv[1] if len(sys.argv) > 1 else 'aihub')
