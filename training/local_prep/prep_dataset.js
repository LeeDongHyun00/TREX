// AI Hub 74번 원천(12MP JPEG) + VAL 라벨(txt, YOLO 형식: 0=그릇, 1=음식)을 YOLO 학습용 640px 데이터셋으로 만든다.
//
//   node prep_dataset.js add --images <원천 압축 해제 폴더> [--classes all|01,04,11|코드,코드] [--per-class 200] [--seed 42]
//     라벨 폴더의 코드별 txt 를 훑어 같은 basename 의 jpg 를 images 폴더에서 찾고, 그릇 박스를 버리고 음식 박스만
//     전역 클래스 id(class_map.json 의 index)로 다시 써서 out/images|labels/{train,val} 에 넣는다. 이미 있는 파일은 건너뛴다.
//     묶음(zip)이 며칠에 걸쳐 도착하므로 여러 번 실행해 누적한다.
//
//   node prep_dataset.js finalize
//     out 에 실제로 들어 있는 클래스만 0..nc-1 로 재배열해 final/ 에 복사하고 data.yaml, food_labels.txt, nutrition.json 을 쓴다.
//     이걸 zip 으로 묶어 Drive 에 올리면 Colab 4번 셀부터 이어진다.
const fs = require('fs');
const path = require('path');
const sharp = require('sharp');

const ROOT = path.resolve(__dirname, '..');
const LABEL_DIR = path.join(ROOT, 'labels_val', 'unz', 'txt');
const MAP = JSON.parse(fs.readFileSync(path.join(ROOT, 'class_map.json'), 'utf8'));
const OUT = path.join(ROOT, 'dataset_640');
const FINAL = path.join(ROOT, 'dataset_final');
const MAX_SIDE = 640;
const VAL_RATIO = 0.15;
const CONCURRENCY = 8;

const args = process.argv.slice(2);
const mode = args[0];
const opt = (k, d) => { const i = args.indexOf(k); return i === -1 ? d : args[i + 1]; };

function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out); else out.push(p);
  }
  return out;
}

function seededShuffle(arr, seed) {
  let s = seed >>> 0;
  const rnd = () => { s = (s * 1664525 + 1013904223) >>> 0; return s / 4294967296; };
  for (let i = arr.length - 1; i > 0; i--) { const j = Math.floor(rnd() * (i + 1)); [arr[i], arr[j]] = [arr[j], arr[i]]; }
  return arr;
}

async function add() {
  const imagesDir = opt('--images');
  if (!imagesDir) throw new Error('--images <원천 압축 해제 폴더> 가 필요하다');
  const perClass = Number(opt('--per-class', '0'));
  const seed = Number(opt('--seed', '42'));
  const classesArg = opt('--classes', 'all');

  const byCode = new Map(MAP.map((m) => [m.code, m]));
  const wanted = classesArg === 'all'
    ? MAP.map((m) => m.code)
    : MAP.filter((m) => classesArg.split(',').some((t) => (t.length === 2 ? m.category === t : m.code === t))).map((m) => m.code);

  console.log(`이미지 색인 중: ${imagesDir}`);
  const index = new Map();
  for (const p of walk(imagesDir)) if (/\.(jpe?g|png)$/i.test(p)) index.set(path.basename(p).replace(/\.[^.]+$/, ''), p);
  console.log(`이미지 ${index.size}장 색인 완료`);

  for (const split of ['train', 'val']) {
    fs.mkdirSync(path.join(OUT, 'images', split), { recursive: true });
    fs.mkdirSync(path.join(OUT, 'labels', split), { recursive: true });
  }

  let done = 0, skipped = 0, missing = 0, noBox = 0;
  const jobs = [];
  for (const code of wanted) {
    const dir = path.join(LABEL_DIR, code);
    if (!fs.existsSync(dir)) continue;
    let files = fs.readdirSync(dir).filter((f) => f.endsWith('.txt'));
    files = seededShuffle(files.sort(), seed + Number(code));
    if (perClass > 0) files = files.slice(0, perClass);
    const nVal = Math.max(1, Math.round(files.length * VAL_RATIO));
    files.forEach((f, i) => {
      const base = f.replace(/\.txt$/, '');
      const src = index.get(base);
      if (!src) { missing++; return; }
      const split = i < nVal ? 'val' : 'train';
      const outImg = path.join(OUT, 'images', split, base + '.jpg');
      const outLbl = path.join(OUT, 'labels', split, base + '.txt');
      if (fs.existsSync(outImg) && fs.existsSync(outLbl)) { skipped++; return; }
      const lines = fs.readFileSync(path.join(dir, f), 'utf8').split(/\r?\n/).filter(Boolean);
      // 0 = 그릇(dish) 은 버리고 1 = 음식만 남긴다. 클래스 id 는 전역 index 로.
      const food = lines.filter((l) => l.startsWith('1 ')).map((l) => `${byCode.get(code).index} ${l.slice(2)}`);
      if (food.length === 0) { noBox++; return; }
      jobs.push(async () => {
        await sharp(src).rotate().resize({ width: MAX_SIDE, height: MAX_SIDE, fit: 'inside', withoutEnlargement: true })
          .jpeg({ quality: 85 }).toFile(outImg);
        fs.writeFileSync(outLbl, food.join('\n') + '\n');
        done++;
        if (done % 500 === 0) console.log(`  ${done}장 변환…`);
      });
    });
  }
  console.log(`대상 ${jobs.length}장 (이미 있음 ${skipped}, 이미지 없음 ${missing}, 음식 박스 없음 ${noBox})`);

  // 라벨(정규화 좌표)이 XML 의 width/height 기준인데, JPEG 가 EXIF 회전을 갖고 있으면 sharp().rotate() 로 맞춘 뒤의
  // 크기가 XML 과 같아야 박스가 어긋나지 않는다. 처음 5장으로 확인해 어긋나면 중단한다.
  const xmlDir = path.join(ROOT, 'labels_val', 'unz', 'xml');
  let checked = 0;
  for (const code of wanted) {
    if (checked >= 5) break;
    const dir = path.join(xmlDir, code);
    if (!fs.existsSync(dir)) continue;
    for (const f of fs.readdirSync(dir).slice(0, 1)) {
      const base = f.replace(/\.xml$/, '');
      const src = index.get(base);
      if (!src) continue;
      const xml = fs.readFileSync(path.join(dir, f), 'utf8');
      const xw = Number(/<width>(\d+)<\/width>/.exec(xml)[1]);
      const xh = Number(/<height>(\d+)<\/height>/.exec(xml)[1]);
      const meta = await sharp(src).metadata();
      const rotated = meta.orientation >= 5;
      const w = rotated ? meta.height : meta.width;
      const h = rotated ? meta.width : meta.height;
      const ok = w === xw && h === xh;
      console.log(`  회전 검사 ${base}: 파일 ${meta.width}x${meta.height} (orientation ${meta.orientation ?? 1}) → 표시 ${w}x${h}, XML ${xw}x${xh} ${ok ? 'OK' : '불일치!'}`);
      if (!ok) throw new Error('이미지 표시 크기와 XML 크기가 다르다 — 회전 처리 방식을 확인해야 한다');
      checked++;
    }
  }
  let cursor = 0;
  await Promise.all(Array.from({ length: CONCURRENCY }, async () => {
    while (cursor < jobs.length) { const job = jobs[cursor++]; try { await job(); } catch (e) { console.warn('실패:', e.message); } }
  }));
  console.log(`완료: ${done}장 추가. 누적 train ${fs.readdirSync(path.join(OUT, 'images', 'train')).length} / val ${fs.readdirSync(path.join(OUT, 'images', 'val')).length}`);
}

function finalize() {
  const present = new Set();
  for (const split of ['train', 'val']) {
    for (const f of fs.readdirSync(path.join(OUT, 'labels', split))) {
      for (const line of fs.readFileSync(path.join(OUT, 'labels', split, f), 'utf8').split(/\r?\n/)) {
        if (line) present.add(Number(line.split(' ')[0]));
      }
    }
  }
  const kept = MAP.filter((m) => present.has(m.index));
  const remap = new Map(kept.map((m, i) => [m.index, i]));
  console.log(`실제 포함 클래스 ${kept.length}종 → 0..${kept.length - 1} 로 재배열`);

  fs.rmSync(FINAL, { recursive: true, force: true });
  for (const split of ['train', 'val']) {
    fs.mkdirSync(path.join(FINAL, 'images', split), { recursive: true });
    fs.mkdirSync(path.join(FINAL, 'labels', split), { recursive: true });
    for (const f of fs.readdirSync(path.join(OUT, 'labels', split))) {
      const lines = fs.readFileSync(path.join(OUT, 'labels', split, f), 'utf8').split(/\r?\n/).filter(Boolean)
        .map((l) => { const [id, ...rest] = l.split(' '); return `${remap.get(Number(id))} ${rest.join(' ')}`; });
      fs.writeFileSync(path.join(FINAL, 'labels', split, f), lines.join('\n') + '\n');
      const img = f.replace(/\.txt$/, '.jpg');
      fs.copyFileSync(path.join(OUT, 'images', split, img), path.join(FINAL, 'images', split, img));
    }
  }
  const names = kept.map((m) => m.name);
  fs.writeFileSync(path.join(FINAL, 'food_labels.txt'), names.join('\n') + '\n', 'utf8');
  fs.writeFileSync(path.join(FINAL, 'data.yaml'),
    `path: /content/dataset\ntrain: images/train\nval: images/val\nnames:\n${names.map((n, i) => `  ${i}: ${n}`).join('\n')}\n`, 'utf8');
  // 앱 foodDatabase 갱신용: 이름 → 1인분 영양값
  fs.writeFileSync(path.join(FINAL, 'nutrition.json'), JSON.stringify(
    kept.map((m) => ({ name: m.name, code: m.code, weightG: m.weightG, kcal: m.kcal, carb: m.carb, protein: m.protein, fat: m.fat })), null, 1), 'utf8');
  console.log(`final/: train ${fs.readdirSync(path.join(FINAL, 'images', 'train')).length} / val ${fs.readdirSync(path.join(FINAL, 'images', 'val')).length}, 클래스 ${kept.length}종`);
  console.log('다음: dataset_final 을 zip 으로 묶어 Drive(MyDrive/trex/dataset.zip)에 올린다');
}

(mode === 'add' ? add() : mode === 'finalize' ? Promise.resolve(finalize()) : Promise.reject(new Error('add | finalize')))
  .catch((e) => { console.error(e.message); process.exit(1); });
