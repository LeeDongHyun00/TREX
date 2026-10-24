# PC 에서 aihub242_fetch.py 로 모은 웹 음식 사진(images/w242_*.jpg + labels/*.txt, 라벨의 첫 칸은 음식 이름)을
# 머지 데이터셋에 더한다. 번호는 여기서 매긴다 — 이미 있는 이름은 그 번호, 새 이름(배추김치 등)은 뒤에 붙인다.
# 이름당 최대 PER_CLASS 장(여러 묶음·여러 242 이름이 한 이름으로 모이면 합쳐서), 15% 는 검증으로.
import hashlib, json, random, shutil
from pathlib import Path

PER_CLASS = 400
VAL_PERCENT = 15


def merge(dataset: Path, web: Path, seed=42):
    names = [l.strip() for l in (dataset / 'food_labels.txt').read_text(encoding='utf-8').splitlines() if l.strip()]
    per = {}
    for lbl in sorted((web / 'labels').glob('*.txt')):
        lines = [l.split('\t') for l in lbl.read_text(encoding='utf-8').splitlines() if '\t' in l]
        if lines:
            per.setdefault(lines[0][0], []).append((lbl.stem, lines))
    rng = random.Random(seed)
    added = {}
    for name in sorted(per):
        if name not in names:
            names.append(name)
        idx = names.index(name)
        items = per[name]; rng.shuffle(items)
        for stem, lines in items[:PER_CLASS]:
            split = 'val' if int(hashlib.md5(stem.encode()).hexdigest(), 16) % 100 < VAL_PERCENT else 'train'
            shutil.copyfile(web / 'images' / f'{stem}.jpg', dataset / 'images' / split / f'{stem}.jpg')
            (dataset / 'labels' / split / f'{stem}.txt').write_text(
                '\n'.join(f'{names.index(n)} {c}' for n, c in lines) + '\n', encoding='utf-8')
            added[name] = added.get(name, 0) + 1
    lines = [f'path: {dataset.as_posix()}', 'train: images/train', 'val: images/val', 'names:'] + [f'  {i}: {n}' for i, n in enumerate(names)]
    (dataset / 'data.yaml').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    (dataset / 'food_labels.txt').write_text('\n'.join(names) + '\n', encoding='utf-8')
    stats = {'이름': len(added), '사진': sum(added.values()), '전체 종': len(names), '이름별': added}
    (dataset / 'web242_stats.json').write_text(json.dumps(stats, ensure_ascii=False, indent=1), encoding='utf-8')
    print(json.dumps({k: v for k, v in stats.items() if k != '이름별'}, ensure_ascii=False), flush=True)
    return names
