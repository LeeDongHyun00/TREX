# AI Hub 242(건강관리를 위한 음식 이미지)에서 대조표(training/aihub242_map.py)에 든 음식만 골라 작은 학습 데이터로 만든다.
#
# 묶음(음식001_Tra.zip 등, 10~29GB)을 하나씩: 받기 → zip 에서 필요한 사진만 읽어 긴 변 640px 로 줄이고 YOLO 라벨을 쓰기 → 원본 지우기.
# 끝난 묶음은 done/ 에 표시해 두고 다시 돌리면 건너뛴다(AI Hub 는 이어받기가 안 돼 끊기면 그 묶음만 처음부터 받는다).
# 디스크는 한 번에 한 묶음(받는 tar + 풀린 zip, 최대 약 60GB)만 쓴다.
#
# 실행(PC, 한국 IP — AI Hub 는 해외 IP 를 막는다):
#   AIHUB_APIKEY=<키> python aihub242_fetch.py --root C:/Workspace/TREX/aihub242
# 키는 인자·파일에 남기지 않고 환경 변수로만 받는다.
# 결과: <root>/web/images/*.jpg, <root>/web/labels/*.txt(클래스 칸은 음식 이름 — 번호는 Colab 머지 때 매긴다), <root>/web/meta.tsv
import argparse, io, json, os, random, re, shutil, subprocess, sys, time, zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from aihub242_map import TO, NEW, PER_CLASS   # noqa: E402

SHELL = Path(__file__).resolve().parents[3] / 'aihub74_raw' / 'aihubshell'
MAX_SIDE = 640
WANT = {**TO, **NEW}


def file_keys(tree: Path):
    """filetree_242.txt 에서 원천(사진) zip 이름 → 파일 키."""
    keys = {}
    for line in tree.read_text(encoding='utf-8', errors='ignore').splitlines():
        m = re.search(r'(음식\d{3}_(Tra|Val))\.zip \| ([\d.]+) ([GM])B \| (\d+)', line)
        if m:
            keys[m.group(1)] = (m.group(5), float(m.group(3)) * (1e9 if m.group(4) == 'G' else 1e6))
    return keys


def groups_needed(tsv: Path):
    """names_242g.tsv(이름·장수·묶음) 에서 대조표 이름이 든 묶음."""
    need = set()
    for line in tsv.read_text(encoding='utf-8').splitlines():
        parts = line.split('\t')
        if len(parts) >= 3 and parts[0].replace('.json', '') in WANT:
            need.update(parts[2].split())
    return sorted(need)


class ConcatReader(io.RawIOBase):
    """tar 안의 조각(<이름>.zip.part<오프셋>)들을 풀지 않고 이어 붙인 것처럼 읽는다 — zipfile 이 바로 열 수 있다.
    tar 를 풀고 조각을 합치면 같은 크기가 세 번(tar·조각·zip) 디스크에 놓여, 29GB 묶음이면 87GB 가 든다."""

    def __init__(self, path, segments):
        self.f = open(path, 'rb'); self.segs = segments; self.size = sum(n for _, n in segments); self.pos = 0
        self.starts = []; acc = 0
        for _, n in segments:
            self.starts.append(acc); acc += n

    def readable(self): return True
    def seekable(self): return True
    def tell(self): return self.pos

    def seek(self, off, whence=0):
        self.pos = off if whence == 0 else self.pos + off if whence == 1 else self.size + off
        return self.pos

    def readinto(self, buf):
        want = len(buf); done = 0
        while done < want and self.pos < self.size:
            i = max(k for k, st in enumerate(self.starts) if st <= self.pos)
            data_off, n = self.segs[i]; inner = self.pos - self.starts[i]
            take = min(want - done, n - inner)
            self.f.seek(data_off + inner); chunk = self.f.read(take)
            buf[done:done + len(chunk)] = chunk; done += len(chunk); self.pos += len(chunk)
            if not chunk:
                break
        return done

    def close(self):
        self.f.close(); super().close()


def download(group, key, work: Path, apikey, expect_bytes):
    """한 묶음을 tar 로 받아(AI Hub API, aihubshell 과 같은 주소), 그 안의 zip 을 가상 파일로 연다. 실패하면 None."""
    import tarfile
    if work.exists():
        shutil.rmtree(work)
    work.mkdir(parents=True)
    tar_path = work / 'download.tar'
    url = f'https://api.aihub.or.kr/down/0.6/242.do?fileSn={key}'
    r = subprocess.run(['curl', '-sS', '-L', '-o', str(tar_path), '-H', f'apikey:{apikey}', '-w', '%{http_code}', url],
                       capture_output=True, text=True)
    size = tar_path.stat().st_size if tar_path.exists() else 0
    if r.returncode != 0 or r.stdout.strip() != '200' or size < 0.9 * expect_bytes - 5e8:   # 목록의 GB 는 반올림이라 작은 묶음은 여유를 더 준다
        # curl 이 0 으로 끝나도 파일이 덜 받아진 적이 있다(74번 때) — 예상 크기의 90% 로 확인한다.
        print(f'  받기 실패(curl {r.returncode}, HTTP {r.stdout.strip()}, {size / 1e9:.1f}/{expect_bytes / 1e9:.1f}GB) {r.stderr[-200:]}', flush=True)
        return None
    with tarfile.open(tar_path) as t:
        members = [m for m in t.getmembers() if m.isfile() and f'{group}.zip' in m.name]
    whole = [m for m in members if m.name.endswith('.zip')]
    if whole:
        segs = [(whole[0].offset_data, whole[0].size)]
    else:
        parts = sorted(members, key=lambda m: int(re.search(r'\.part(\d+)$', m.name).group(1)))
        segs = [(m.offset_data, m.size) for m in parts]
    if not segs:
        print(f'  tar 안에 {group}.zip 이 없다: {[m.name for m in members][:3]}', flush=True)
        return None
    reader = io.BufferedReader(ConcatReader(tar_path, segs), buffer_size=1 << 20)
    try:
        zipfile.ZipFile(reader).namelist()
    except zipfile.BadZipFile:
        print('  zip 이 깨졌다', flush=True)
        return None
    return reader


def group_contents(root: Path):
    """묶음 → {242 이름: 장수}. 라벨 zip 목록(classes_242.tsv: zip 이름·음식 이름·장수)에서 읽는다."""
    out = {}
    for line in (root / 'classes_242.tsv').read_text(encoding='utf-8').splitlines():
        z, src, n = line.split('\t')
        g = z.replace('_json.zip', '')
        src = src.replace(' json', '').replace('.json', '').strip()
        out.setdefault(g, {})[src] = out.get(g, {}).get(src, 0) + int(n)
    return out


def collected(out: Path):
    """지금까지 모은 장수(우리 이름별)."""
    have = {}
    if (out / 'meta.tsv').exists():
        for line in open(out / 'meta.tsv', encoding='utf-8'):
            t = line.rstrip('\n').split('\t')[2]
            have[t] = have.get(t, 0) + 1
    return have


def usefulness(contents, have):
    """이 묶음이 아직 모자란 이름을 몇 장 채우는가(이름당 PER_CLASS 까지만 센다)."""
    gain = {}
    for src, n in contents.items():
        if src in WANT:
            t = WANT[src]
            gain[t] = gain.get(t, 0) + min(n, PER_CLASS)
    return sum(min(v, max(0, PER_CLASS - have.get(t, 0))) for t, v in gain.items())


def labels_zip(root: Path, group):
    """같은 묶음의 라벨 zip(음식001_Tra → 음식001_Tra_json.zip)."""
    hits = list(root.rglob(f'{group}_json.zip'))
    return hits[0] if hits else None


def zname(info):
    """zip 안 파일 이름. AI Hub 242 zip 은 UTF-8 표시 없이 CP949 로 이름을 넣어, 파이썬이 cp437 로 읽으면 한글이 깨진다."""
    if info.flag_bits & 0x800:
        return info.filename
    raw = info.filename.encode('cp437')
    for enc in ('utf-8', 'cp949'):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            pass
    return info.filename


def process(group, img_zip, lbl_zip: Path, out: Path, rng):
    from PIL import Image
    (out / 'images').mkdir(parents=True, exist_ok=True); (out / 'labels').mkdir(parents=True, exist_ok=True)
    # 라벨: "<음식이름> json/<코드>.json" → 박스들
    boxes = {}
    with zipfile.ZipFile(lbl_zip) as lz:
        for info in lz.infolist():
            n = zname(info)
            if not n.endswith('.json'):
                continue
            src = n.split('/')[0].replace(' json', '').replace('.json', '').strip()
            if src not in WANT:
                continue
            boxes[(src, Path(n).stem)] = info
        by_src = {}
        for (src, code) in boxes:
            by_src.setdefault(src, []).append(code)
        chosen = {}
        for src, codes in by_src.items():
            rng.shuffle(codes)
            for c in codes[:PER_CLASS]:
                chosen[(src, c)] = json.loads(lz.read(boxes[(src, c)]).decode('utf-8', errors='ignore'))
    n_ok = n_skip = 0
    meta = open(out / 'meta.tsv', 'a', encoding='utf-8')
    with zipfile.ZipFile(img_zip) as iz:
        index = {}
        for info in iz.infolist():
            n = zname(info)
            if n.lower().endswith(('.jpg', '.jpeg', '.png')):
                index[Path(n).stem] = info
        for (src, code), ann in chosen.items():
            name = index.get(code)
            if not name:
                n_skip += 1; continue
            try:
                im = Image.open(io.BytesIO(iz.read(name)))
                if im.getexif().get(0x0112, 1) != 1:   # 회전 정보가 있는 사진은 라벨이 어느 방향 기준인지 몰라 뺀다
                    n_skip += 1; continue
                im = im.convert('RGB'); s = MAX_SIDE / max(im.size)
                if s < 1:
                    im = im.resize((max(1, int(im.width * s)), max(1, int(im.height * s))), Image.BILINEAR)
                lines = []
                for a in ann if isinstance(ann, list) else [ann]:
                    x, y = map(float, str(a['Point(x,y)']).split(','))
                    w, h = float(a['W']), float(a['H'])
                    x0, y0, x1, y1 = max(0, x - w / 2), max(0, y - h / 2), min(1, x + w / 2), min(1, y + h / 2)
                    if x1 - x0 > 0.01 and y1 - y0 > 0.01:
                        lines.append(f'{WANT[src]}\t{(x0 + x1) / 2:.6f} {(y0 + y1) / 2:.6f} {x1 - x0:.6f} {y1 - y0:.6f}')
                if not lines:
                    n_skip += 1; continue
                stem = f'w242_{code}'
                im.save(out / 'images' / f'{stem}.jpg', quality=90)
                (out / 'labels' / f'{stem}.txt').write_text('\n'.join(lines) + '\n', encoding='utf-8')
                meta.write(f'{stem}\t{src}\t{WANT[src]}\t{group}\n'); n_ok += 1
            except Exception as e:  # 깨진 사진·라벨 한 장은 건너뛴다
                n_skip += 1
    meta.close()
    return n_ok, n_skip


if __name__ == '__main__':
    ap = argparse.ArgumentParser(); ap.add_argument('--root', required=True); ap.add_argument('--only', default='')
    a = ap.parse_args()
    apikey = os.environ.get('AIHUB_APIKEY')
    assert apikey, 'AIHUB_APIKEY 환경 변수가 없다'
    root = Path(a.root); out = root / 'web'; done = root / 'done'; done.mkdir(exist_ok=True)
    keys = file_keys(root / 'filetree_242.txt')
    groups = [g for g in groups_needed(root / 'names_242g.tsv') if not a.only or g in a.only.split(',')]
    rng = random.Random(42)
    contents = group_contents(root)
    groups = [g for g in groups if not (done / g).exists()]
    print(f'남은 묶음 {len(groups)}개', flush=True)
    i = 0
    while groups:
        # 매번 "아직 모자란 이름을 GB 당 가장 많이 채우는" 묶음을 고른다. 다 찼으면 남은 묶음은 받지 않는다.
        have = collected(out)
        score = {g: usefulness(contents.get(g, {}), have) / max(keys.get(g, ('', 1e9))[1] / 1e9, 0.5) for g in groups}
        g = max(groups, key=score.get); groups.remove(g); i += 1
        if score[g] <= 0:
            print(f'[{i}] {g} 건너뜀 — 필요한 이름이 이미 {PER_CLASS}장씩 찼다', flush=True)
            (done / g).write_text('skip\n'); continue
        lz = labels_zip(root, g)
        if not lz or g not in keys:
            print(f'[{i}] {g} 라벨 zip 또는 파일 키가 없다 — 건너뜀', flush=True); continue
        for attempt in range(1, 4):
            t0 = time.time()
            key, expect = keys[g]
            print(f'[{i}] {g} 받는 중(시도 {attempt}, 키 {key}, 약 {expect / 1e9:.0f}GB, 새로 채울 장수 약 {usefulness(contents.get(g, {}), have)})', flush=True)
            z = download(g, key, root / 'work', apikey, expect)
            if z:
                n_ok, n_skip = process(g, z, lz, out, rng)
                z.close()
                shutil.rmtree(root / 'work', ignore_errors=True)
                (done / g).write_text(f'{n_ok} {n_skip}\n')
                print(f'  완료 {n_ok}장(건너뜀 {n_skip}) · {(time.time() - t0) / 60:.0f}분', flush=True)
                break
            shutil.rmtree(root / 'work', ignore_errors=True)
        else:
            print(f'  {g} 3번 실패 — 나중에 다시 돌리면 이 묶음부터 한다', flush=True)
    total = sum(1 for _ in open(out / 'meta.tsv', encoding='utf-8')) if (out / 'meta.tsv').exists() else 0
    print(f'끝 — 모은 사진 {total}장 · {out}', flush=True)
