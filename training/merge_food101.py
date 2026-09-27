# AI Hub 한식 데이터셋(YOLO 형식)에 Food-101 의 양식·일식 35종을 합친다 — 새 음식만 따로 학습하면 기존 음식을 잊어버리므로
# (파국적 망각) 옛 데이터와 새 데이터를 한 데이터셋으로 머지해 다시 학습한다. Colab 노트북 train_food_merge_colab.ipynb 가 부른다.
#
# Food-101 에는 박스가 없다. 두 모델로 가짜 라벨(pseudo label)을 만든다.
#  1) 위치 모델 YOLOE-11S("food·bowl·plate…" 고정 어휘)로 가장 큰 음식 자리 = 그 사진의 음식(새 클래스).
#  2) 기존 342/400종 모델(best.pt)이 0.5 이상으로 잡은 **다른** 한식 자리(감자튀김·샐러드 등)도 라벨로 넣는다 —
#     빼면 햄버거 옆 감자튀김이 "배경" 으로 학습돼 기존 감자튀김 클래스를 깎는다.
# 로컬에서 작게 돌려 검증한다: python merge_food101.py --dry  (학습은 하지 않는다)
import argparse, json, os, random, shutil
from pathlib import Path

# (Food-101 폴더명, 앱 이름). AI Hub 에 이미 있는 음식(비빔밥·초밥·회·만두·볶음밥·감자튀김·미소된장국·쌀국수·카레·스파게티·치킨윙)은 뺐다 —
# 같은 음식을 두 이름으로 가르치면 모델이 헷갈린다. 이름은 food_labels_400.txt 와 겹치지 않는 것을 확인했다.
NEW_CLASSES = [
    ('pizza', '피자'), ('hamburger', '햄버거'), ('hot_dog', '핫도그'), ('ramen', '라멘'), ('takoyaki', '타코야키'),
    ('steak', '스테이크'), ('tacos', '타코'), ('nachos', '나초'), ('caesar_salad', '시저샐러드'), ('club_sandwich', '클럽샌드위치'),
    ('grilled_cheese_sandwich', '그릴드치즈샌드위치'), ('french_toast', '프렌치토스트'), ('pancakes', '팬케이크'), ('waffles', '와플'),
    ('donuts', '도넛'), ('ice_cream', '아이스크림'), ('cheesecake', '치즈케이크'), ('chocolate_cake', '초콜릿케이크'), ('cup_cakes', '컵케이크'),
    ('macarons', '마카롱'), ('tiramisu', '티라미수'), ('churros', '추로스'), ('onion_rings', '어니언링'), ('garlic_bread', '마늘빵'),
    ('lasagna', '라자냐'), ('risotto', '리조또'), ('macaroni_and_cheese', '맥앤치즈'), ('fish_and_chips', '피시앤칩스'), ('pad_thai', '팟타이'),
    ('eggs_benedict', '에그베네딕트'), ('baby_back_ribs', '폭립'), ('chicken_quesadilla', '퀘사디아'), ('omelette', '오믈렛'),
    ('edamame', '에다마메'), ('spring_rolls', '스프링롤'),
]
PROMPTS = ['food', 'bowl of food', 'plate of food', 'dish', 'bowl', 'plate', 'rice', 'soup', 'side dish']


def read_names(dataset: Path):
    return [l.strip() for l in (dataset / 'food_labels.txt').read_text(encoding='utf-8').splitlines() if l.strip()]


def write_yaml(dataset: Path, names):
    lines = [f'path: {dataset.as_posix()}', 'train: images/train', 'val: images/val', 'names:']
    lines += [f'  {i}: {n}' for i, n in enumerate(names)]
    (dataset / 'data.yaml').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    (dataset / 'food_labels.txt').write_text('\n'.join(names) + '\n', encoding='utf-8')


def iou(a, b):
    ix = max(0, min(a[2], b[2]) - max(a[0], b[0])) * max(0, min(a[3], b[3]) - max(a[1], b[1]))
    ar = lambda r: (r[2] - r[0]) * (r[3] - r[1])
    return ix / (ar(a) + ar(b) - ix + 1e-9)


def main_box(yoloe, img_path, w, h):
    """위치 모델이 찾은 음식 자리 중 가장 큰 것(점수 0.1 이상). Food-101 사진은 음식 하나를 중심에 찍은 것이라 가장 큰 자리가 그 음식이다.
    못 찾으면 사진 가운데 90% 를 쓴다."""
    r = yoloe.predict(str(img_path), conf=0.1, verbose=False, imgsz=640)[0]
    boxes = [b for b in r.boxes.xyxy.tolist() if (b[2] - b[0]) * (b[3] - b[1]) < 0.98 * w * h]
    if not boxes:
        return [0.05 * w, 0.05 * h, 0.95 * w, 0.95 * h], False
    return max(boxes, key=lambda b: (b[2] - b[0]) * (b[3] - b[1])), True


def known_boxes(base, img_path, name_to_index, conf=0.5):
    """기존 모델이 확신하는 한식 자리들 → (새 인덱스, 박스)."""
    if base is None:
        return []
    r = base.predict(str(img_path), conf=conf, verbose=False, imgsz=640)[0]
    out = []
    for c, b in zip(r.boxes.cls.tolist(), r.boxes.xyxy.tolist()):
        name = base.names[int(c)]
        if name in name_to_index:
            out.append((name_to_index[name], b))
    return out


def yolo_line(ci, b, w, h):
    x0, y0, x1, y1 = b
    return f'{ci} {((x0 + x1) / 2 / w):.6f} {((y0 + y1) / 2 / h):.6f} {((x1 - x0) / w):.6f} {((y1 - y0) / h):.6f}'


def merge(dataset: Path, food101: Path, base_pt: str | None, per_train: int, per_val: int, seed=42, log_every=500):
    from PIL import Image
    from ultralytics import YOLO, YOLOE
    random.seed(seed)
    old = read_names(dataset)
    names = old + [ko for _, ko in NEW_CLASSES]
    assert len(set(names)) == len(names), '새 이름이 기존 이름과 겹친다'
    name_to_index = {n: i for i, n in enumerate(names)}
    yoloe = YOLOE('yoloe-11s-seg.pt'); yoloe.set_classes(PROMPTS, yoloe.get_text_pe(PROMPTS))
    base = YOLO(base_pt) if base_pt and Path(base_pt).exists() else None
    print(f'기존 {len(old)}종 + 새 {len(NEW_CLASSES)}종 = {len(names)}종 · 기존 모델로 한식 자리 라벨: {"있음" if base else "없음(위치 모델만)"}', flush=True)

    split = {s: [l.strip() for l in (food101 / 'meta' / f'{s}.txt').read_text().splitlines() if l.strip()] for s in ('train', 'test')}
    stats = {'images': 0, 'no_region': 0, 'extra_known': 0}
    for k, (folder, ko) in enumerate(NEW_CLASSES):
        ci = name_to_index[ko]
        for s, n, sub in (('train', per_train, 'train'), ('test', per_val, 'val')):
            files = [f for f in split[s] if f.startswith(folder + '/')]
            random.shuffle(files)
            for f in files[:n]:
                src = food101 / 'images' / (f + '.jpg')
                dst_img = dataset / 'images' / sub / f'f101_{folder}_{Path(f).name}.jpg'
                dst_lbl = dataset / 'labels' / sub / f'f101_{folder}_{Path(f).name}.txt'
                with Image.open(src) as im:
                    w, h = im.size
                mb, found = main_box(yoloe, src, w, h)
                lines = [yolo_line(ci, mb, w, h)]
                for kci, kb in known_boxes(base, src, name_to_index):
                    if iou(kb, mb) < 0.4:   # 주 음식 자리와 겹치면 주 음식(새 클래스)이 맞다고 본다
                        lines.append(yolo_line(kci, kb, w, h)); stats['extra_known'] += 1
                shutil.copyfile(src, dst_img)
                dst_lbl.write_text('\n'.join(lines) + '\n', encoding='utf-8')
                stats['images'] += 1; stats['no_region'] += (not found)
                if stats['images'] % log_every == 0:
                    print(f'  {stats["images"]}장 ({k + 1}/{len(NEW_CLASSES)} {ko})', flush=True)
    write_yaml(dataset, names)
    print(f'완료: Food-101 {stats["images"]}장 · 위치 모델이 자리를 못 찾아 가운데 90% 를 쓴 사진 {stats["no_region"]}장 · '
          f'함께 넣은 한식 자리 {stats["extra_known"]}개', flush=True)
    (dataset / 'merge_stats.json').write_text(json.dumps(stats, ensure_ascii=False), encoding='utf-8')
    return names


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('--dry', action='store_true', help='로컬 검증: 작은 가짜 데이터셋에 클래스당 2장씩만')
    ap.add_argument('--dataset'); ap.add_argument('--food101'); ap.add_argument('--base')
    ap.add_argument('--per-train', type=int, default=500); ap.add_argument('--per-val', type=int, default=100)
    a = ap.parse_args()
    if a.dry:
        import tempfile
        tmp = Path(tempfile.mkdtemp())
        for s in ('train', 'val'):
            (tmp / 'images' / s).mkdir(parents=True); (tmp / 'labels' / s).mkdir(parents=True)
        src = Path(a.dataset)
        shutil.copyfile(src / 'food_labels.txt', tmp / 'food_labels.txt')
        names = merge(tmp, Path(a.food101), a.base, per_train=2, per_val=1, log_every=10)
        print('dry 결과 폴더:', tmp, '· 클래스', len(names))
        for p in sorted((tmp / 'labels' / 'train').glob('*.txt'))[:6]:
            print(' ', p.name, '|', p.read_text(encoding='utf-8').strip().replace('\n', ' / '))
        print((tmp / 'data.yaml').read_text(encoding='utf-8').splitlines()[:4], '…', (tmp / 'data.yaml').read_text(encoding='utf-8').splitlines()[-2:])
    else:
        merge(Path(a.dataset), Path(a.food101), a.base, a.per_train, a.per_val)
