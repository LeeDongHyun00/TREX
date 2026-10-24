# UEC FOOD-256(일본 전기통신대, 비상업 연구용)을 머지 데이터셋에 더한다. Food-101 과 달리 **진짜 박스**가 있고
# 한 사진에 여러 음식이 담긴 식탁 사진이 섞여 있다 — 우리 모델의 약점("여러 음식 장면", FOOD_EVAL §3)을 직접 보강한다.
#
# 음식마다 셋 중 하나로 정한다(MAP):
#  - 기존 이름(AI Hub 342종·Food-101 35종)에 붙인다 — 같은 음식을 두 이름으로 가르치지 않는다.
#  - 새 이름으로 더한다(NEW) — 규동·가츠동·텐동처럼 대학생이 자주 먹는데 아직 없는 음식.
#  - 표에 없는 음식이 **한 번이라도** 박스로 들어 있는 사진은 통째로 뺀다 — 라벨 없는 음식이 사진에 남으면 모델이 그걸 "배경" 으로 배운다.
# UEC 폴더 구조: <카테고리 번호>/<사진 id>.jpg + bb_info.txt("img x1 y1 x2 y2"). 여러 음식 사진은 카테고리 폴더마다 같은 id 로 들어 있다.
# 로컬 검증: python merge_uec.py --dry --uec <UECFOOD256 폴더> --labels <food_labels.txt>
import argparse, hashlib, json, shutil
from pathlib import Path

# UEC 번호 → 이름. 기존 이름에 붙이는 것.
MAP = {
    1: '쌀밥', 2: '장어덮밥', 134: '장어덮밥', 6: '카레라이스', 9: '볶음밥', 11: '일반비빔밥', 20: '일식우동', 21: '일식우동',
    22: '메밀국수', 36: '미소된장국', 42: '군만두', 81: '고기만두', 51: '탕수육', 53: '달걀찜', 60: '햄버거스테이크', 64: '마파두부',
    71: '달걀말이', 82: '오므라이스', 84: '토마토소스스파게티', 85: '새우튀김', 35: '채소튀김', 55: '닭튀김', 94: '주먹밥',
    106: '닭갈비', 115: '짬뽕', 139: '돈가스', 140: '돈가스', 174: '돈가스', 141: '치킨가스', 197: '치킨윙', 98: '감자튀김',
    206: '쌀국수', 243: '자장면',
    # Food-101 에서 이미 더한 것
    17: '햄버거', 18: '피자', 23: '라멘', 29: '타코야키', 61: '스테이크', 40: '오믈렛', 67: '오믈렛', 97: '핫도그', 114: '팬케이크',
    117: '티라미수', 118: '와플', 119: '치즈케이크', 123: '리조또', 126: '프렌치토스트', 148: '타코', 149: '나초', 153: '라자냐',
    154: '시저샐러드', 161: '도넛', 209: '스프링롤', 225: '추로스',
}
# UEC 번호 → 새 이름. 대학생이 자주 먹는 일식·빵·디저트 중 아직 없는 것.
NEW = {
    92: '규동', 5: '가츠동', 4: '오야코동', 10: '텐동', 76: '카이센동', 77: '카이센동', 83: '카츠카레', 28: '오코노미야키',
    96: '츠케멘', 26: '야키소바', 65: '야키토리', 32: '고로케', 86: '감자샐러드', 88: '마카로니샐러드', 87: '샐러드',
    68: '달걀프라이', 46: '연어구이', 13: '크루아상', 145: '베이글', 158: '머핀', 146: '스콘', 12: '토스트', 116: '크레페',
    120: '생크림케이크', 224: '브라우니', 160: '슈크림', 162: '애플파이', 163: '파르페', 130: '치킨너겟', 170: '샤오롱바오',
    220: '스팸무스비',
}
VAL_PERCENT = 15


def read_boxes(uec: Path):
    """사진 id → [(UEC 번호, x1, y1, x2, y2, 그 사진 경로)]."""
    per = {}
    for d in sorted(p for p in uec.iterdir() if p.is_dir() and p.name.isdigit()):
        cat = int(d.name)
        info = d / 'bb_info.txt'
        if not info.exists():
            continue
        for line in info.read_text(encoding='utf-8', errors='ignore').splitlines()[1:]:
            parts = line.split()
            if len(parts) != 5:
                continue
            img = d / f'{parts[0]}.jpg'
            if img.exists():
                per.setdefault(parts[0], []).append((cat, *map(float, parts[1:]), img))
    return per


def plan(uec: Path, old_names):
    """어떤 사진을 넣고 뺄지. 반환: (새 이름 목록, [(id, 사진, [(이름, 박스)])], 통계)."""
    new_names = []
    for n in NEW.values():
        if n not in new_names and n not in old_names:
            new_names.append(n)
    clash = [n for n in dict.fromkeys(NEW.values()) if n in old_names]
    assert not clash, f'새 이름이 기존 이름과 겹친다: {clash}'
    missing = sorted({n for n in MAP.values() if n not in old_names})
    assert not missing, f'붙일 기존 이름이 라벨에 없다: {missing}'
    names = list(old_names) + new_names
    per = read_boxes(uec)
    keep, dropped = [], 0
    for pid, boxes in per.items():
        if any(b[0] not in MAP and b[0] not in NEW for b in boxes):
            dropped += 1
            continue
        labeled = [((MAP.get(b[0]) or NEW[b[0]]), b[1:5]) for b in boxes]
        keep.append((pid, boxes[0][5], labeled))
    stats = {'사진': len(per), '넣음': len(keep), '뺌(표에 없는 음식 포함)': dropped,
             '여러 음식 사진(넣은 것)': sum(1 for _, _, l in keep if len(l) > 1), '새 이름': len(new_names)}
    return names, keep, stats


def merge(dataset: Path, uec: Path, dry=False):
    from PIL import Image, ImageOps
    old = [l.strip() for l in (dataset / 'food_labels.txt').read_text(encoding='utf-8').splitlines() if l.strip()]
    names, keep, stats = plan(uec, old)
    index = {n: i for i, n in enumerate(names)}
    per_class = {}
    for pid, img, labeled in keep:
        split = 'val' if int(hashlib.md5(pid.encode()).hexdigest(), 16) % 100 < VAL_PERCENT else 'train'
        with Image.open(img) as im:
            w, h = ImageOps.exif_transpose(im).size if im.getexif().get(0x0112, 1) != 1 else im.size
        lines = []
        for n, (x1, y1, x2, y2) in labeled:
            x1, y1, x2, y2 = max(0, x1), max(0, y1), min(w, x2), min(h, y2)
            if x2 - x1 < 2 or y2 - y1 < 2:
                continue
            lines.append(f'{index[n]} {(x1 + x2) / 2 / w:.6f} {(y1 + y2) / 2 / h:.6f} {(x2 - x1) / w:.6f} {(y2 - y1) / h:.6f}')
            per_class[n] = per_class.get(n, 0) + 1
        if not lines or dry:
            continue
        shutil.copyfile(img, dataset / 'images' / split / f'uec_{pid}.jpg')
        (dataset / 'labels' / split / f'uec_{pid}.txt').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    stats['박스(이름별)'] = dict(sorted(per_class.items(), key=lambda x: -x[1]))
    if not dry:
        lines = [f'path: {dataset.as_posix()}', 'train: images/train', 'val: images/val', 'names:'] + [f'  {i}: {n}' for i, n in enumerate(names)]
        (dataset / 'data.yaml').write_text('\n'.join(lines) + '\n', encoding='utf-8')
        (dataset / 'food_labels.txt').write_text('\n'.join(names) + '\n', encoding='utf-8')
        (dataset / 'uec_stats.json').write_text(json.dumps(stats, ensure_ascii=False, indent=1), encoding='utf-8')
    print(json.dumps({k: v for k, v in stats.items() if k != '박스(이름별)'}, ensure_ascii=False), flush=True)
    return names, stats


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('--uec', required=True); ap.add_argument('--labels', required=True); ap.add_argument('--dry', action='store_true')
    a = ap.parse_args()
    import tempfile
    tmp = Path(tempfile.mkdtemp()); shutil.copyfile(a.labels, tmp / 'food_labels.txt')
    names, stats = merge(tmp, Path(a.uec), dry=True)
    print('전체', len(names), '종 · 새', stats['새 이름'])
    print(json.dumps(stats['박스(이름별)'], ensure_ascii=False))
