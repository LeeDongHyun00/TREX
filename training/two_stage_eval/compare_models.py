# 음식 모델 두 개를 식탁 사진 16장에서 앱과 같은 방식으로 견준다 — 전체 사진 1회 / 2단계(위치 모델 → 회색 여백 30% 자르기 → 이름).
# TensorFlow 없이 LiteRT(ai_edge_litert)만 쓴다(이 PC 는 보안 정책이 TF DLL 을 막는다, 2026-09-28).
# 실행: python compare_models.py <옛 모델.tflite> <옛 라벨.txt> <새 모델.tflite> <새 라벨.txt>
import csv, sys
from pathlib import Path
import numpy as np
from PIL import Image
from ai_edge_litert.interpreter import Interpreter

REPO = Path(__file__).resolve().parents[2]
ROOT = REPO.parent / 'aihub74_raw' / 'eval_real'
REGION = REPO / 'app/src/main/assets/models/food_region.tflite'
THRESHOLD = 0.40
# 새 음식이 생기면 "맞힐 수 있는 음식" 도 늘어난다 — 원래 정답표(342종 기준)에 더한다.
EXTRA_IN_CLASS = {'photo05.jpg': ['피자'], 'photo14.jpg': ['고로케'], 'photo02.jpg': ['야키토리']}   # 라벨에 없는 모델에서는 자동으로 빠진다


class Model:
    def __init__(self, path):
        self.it = Interpreter(model_path=str(path), num_threads=4); self.it.allocate_tensors()
        self.i, self.o = self.it.get_input_details()[0], self.it.get_output_details()[0]
        sh = self.i['shape']; self.cf = sh[1] == 3 and sh[3] != 3; self.S = sh[2] if self.cf else sh[1]

    def run(self, canvas):
        a = np.asarray(canvas, np.float32)[None] / 255.0
        if self.cf: a = a.transpose(0, 3, 1, 2)
        self.it.set_tensor(self.i['index'], a); self.it.invoke()
        o = self.it.get_tensor(self.o['index'])[0]
        return o if o.shape[0] < o.shape[1] else o.T

    def letterbox(self, im):
        S = self.S; s = min(S / im.width, S / im.height); nw, nh = max(1, int(im.width * s)), max(1, int(im.height * s))
        c = Image.new('RGB', (S, S), (114, 114, 114)); c.paste(im.resize((nw, nh), Image.BILINEAR), ((S - nw) // 2, (S - nh) // 2))
        return c, s, (S - nw) // 2, (S - nh) // 2


def scores(model, canvas):
    return model.run(canvas)[4:].max(1)


def gray30(im, b, S):
    c = im.crop(tuple(int(v) for v in b)); side = int(max(c.width, c.height) / np.sqrt(0.3))
    g = Image.new('RGB', (side, side), (114, 114, 114)); g.paste(c, ((side - c.width) // 2, (side - c.height) // 2))
    return g.resize((S, S), Image.BILINEAR)


def iou(a, b):
    ix = max(0, min(a[2], b[2]) - max(a[0], b[0])) * max(0, min(a[3], b[3]) - max(a[1], b[1]))
    ar = lambda r: (r[2] - r[0]) * (r[3] - r[1]); return ix / (ar(a) + ar(b) - ix + 1e-9)


def regions(rm, im):
    """앱 FoodRegions.decode + tidy 와 같은 규칙."""
    c, s, px, py = rm.letterbox(im); o = rm.run(c)
    best = o[4:].max(0); sel = np.where(best >= 0.05)[0]
    cx, cy, w, h = o[0, sel], o[1, sel], o[2, sel], o[3, sel]
    b = np.stack([(cx - w/2 - px) / s, (cy - h/2 - py) / s, (cx + w/2 - px) / s, (cy + h/2 - py) / s], 1)
    b[:, [0, 2]] = b[:, [0, 2]].clip(0, im.width); b[:, [1, 3]] = b[:, [1, 3]].clip(0, im.height)
    order = best[sel].argsort()[::-1]; kept = []
    for k in order:
        if all(iou(b[k], b[j]) < 0.5 for j in kept): kept.append(k)
    boxes = [b[k].tolist() for k in kept if (b[k][2]-b[k][0])*(b[k][3]-b[k][1]) <= 0.6 * im.width * im.height]
    area = lambda r: (r[2]-r[0])*(r[3]-r[1])
    out = [r for r in boxes if not any(area(q) > area(r) and max(0, min(r[2], q[2]) - max(r[0], q[0])) * max(0, min(r[3], q[3]) - max(r[1], q[1])) / max(area(r), 1e-9) >= 0.85 for q in boxes)]
    return out[:20]


def evaluate(path, labels_path, rm, rows, imgs):
    m = Model(path); L = [l.strip() for l in open(labels_path, encoding='utf-8-sig') if l.strip()]
    res = {}
    for mode in ('전체 1회', '2단계'):
        hit = miss = extra = top3 = 0; want_total = 0
        for r in rows:
            im = imgs[r['file']]
            want = [x for x in r['expected_in_class'].split('|') if x] + EXTRA_IN_CLASS.get(r['file'], [])
            want = [w for w in dict.fromkeys(want) if w in L]; want_total += len(want)
            if mode == '전체 1회':
                s = scores(m, m.letterbox(im)[0]); got = [L[i] for i in np.argsort(-s) if s[i] >= THRESHOLD][:5]
                rank = {w: list(np.argsort(-s)).index(L.index(w)) for w in want}
            else:
                got, rank = [], {w: 999 for w in want}
                for b in regions(rm, im):
                    s = scores(m, gray30(im, b, m.S)); o = list(np.argsort(-s))
                    if s[o[0]] >= THRESHOLD: got.append(L[o[0]])
                    for w in want: rank[w] = min(rank[w], o.index(L.index(w)))
                got = list(dict.fromkeys(got))
            hit += len([g for g in got if g in want]); miss += len([w for w in want if w not in got]); extra += len([g for g in got if g not in want])
            top3 += sum(1 for v in rank.values() if v < 3)
        res[mode] = (hit, want_total, miss, extra, top3)
    return res


if __name__ == '__main__':
    old_m, old_l, new_m, new_l = sys.argv[1:5]
    rows = list(csv.DictReader(open(ROOT / 'ground_truth_multi.csv', encoding='utf-8')))
    imgs = {r['file']: Image.open(ROOT / 'images' / r['file']).convert('RGB') for r in rows}
    rm = Model(REGION)
    for tag, mp, lp in (('옛 모델', old_m, old_l), ('새 모델', new_m, new_l)):
        for mode, (h, wt, mi, ex, t3) in evaluate(mp, lp, rm, rows, imgs).items():
            print(f'{tag} · {mode}: 검출 {h}/{wt} ({h/max(wt,1):.0%}) · 누락 {mi} · 오검출 {ex} · 손볼/장 {(mi+ex)/len(rows):.2f} · 정답 후보 3위 안 {t3}/{wt}', flush=True)
