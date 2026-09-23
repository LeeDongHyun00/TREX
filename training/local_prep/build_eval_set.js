// 학습에 쓰지 않은 Training 원천에서 held-out 평가셋을 만든다.
//
// 학습은 Validation 원천(11/04/06_07_08/09_10/01/02_03_05)으로만 했으므로 Training 이미지는
// 모델이 한 번도 보지 않았다. 같은 342클래스의 미공개 사진이라 클래스별 정확도를 정직하게 잴 수 있다.
//
//   node build_eval_set.js --images <원천 해제 폴더> [--per-class 20]
//
// 출력: ../eval_set/{images/<파일명>.jpg, ground_truth.csv, labels.txt}
// ground_truth.csv 는 file,class_index,class_name. Colab 평가 노트북이 이걸 읽는다.
const fs = require('fs');
const path = require('path');
const sharp = require('sharp');

const ROOT = path.resolve(__dirname, '..');
const LABEL_DIR = path.join(ROOT, 'labels_train', 'unz', 'txt'); // Training 라벨(클래스 코드 폴더)
const CLASSES = path.join(ROOT, 'dataset_final', 'food_labels.txt'); // 학습에 쓴 342클래스 순서
const MAP = JSON.parse(fs.readFileSync(path.join(ROOT, 'class_map.json'), 'utf8'));
const OUT = path.join(ROOT, 'eval_set');
const MAX_SIDE = 640;
const CONCURRENCY = 8;

const args = process.argv.slice(2);
const opt = (k, d) => { const i = args.indexOf(k); return i === -1 ? d : args[i + 1]; };
const imagesDir = opt('--images');
const perClass = Number(opt('--per-class', '20'));
if (!imagesDir) { console.error('--images <원천 해제 폴더> 가 필요하다'); process.exit(1); }

const classNames = fs.readFileSync(CLASSES, 'utf8').split(/\r?\n/).map((s) => s.trim()).filter(Boolean);
const indexByName = new Map(classNames.map((n, i) => [n, i]));
// 학습한 342클래스에 해당하는 코드만 고른다(나머지 58종은 모델이 모르므로 평가 대상이 아니다).
const codeToIndex = new Map();
for (const m of MAP) {
  const idx = indexByName.get(m.name);
  if (idx !== undefined) codeToIndex.set(m.code, { index: idx, name: m.name });
}
console.log(`평가 대상 클래스 ${codeToIndex.size}종 / 학습 클래스 ${classNames.length}종`);

function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out); else out.push(p);
  }
  return out;
}

(async () => {
  console.log('이미지 색인 중…');
  const index = new Map();
  for (const p of walk(imagesDir)) {
    if (/\.(jpe?g|png)$/i.test(p)) index.set(path.basename(p).replace(/\.[^.]+$/, ''), p);
  }
  console.log(`이미지 ${index.size}장`);

  fs.rmSync(OUT, { recursive: true, force: true });
  fs.mkdirSync(path.join(OUT, 'images'), { recursive: true });

  const rows = [];
  const jobs = [];
  let noImage = 0;
  for (const [code, info] of codeToIndex) {
    const dir = path.join(LABEL_DIR, code);
    if (!fs.existsSync(dir)) continue;
    let taken = 0;
    for (const f of fs.readdirSync(dir).sort()) {
      if (taken >= perClass) break;
      const base = f.replace(/\.txt$/, '');
      const src = index.get(base);
      if (!src) { noImage++; continue; }
      const out = path.join(OUT, 'images', base + '.jpg');
      rows.push(`${base}.jpg,${info.index},${info.name}`);
      taken++;
      jobs.push(async () => {
        await sharp(src).rotate()
          .resize({ width: MAX_SIDE, height: MAX_SIDE, fit: 'inside', withoutEnlargement: true })
          .jpeg({ quality: 85 }).toFile(out);
      });
    }
  }
  console.log(`대상 ${jobs.length}장 (이미지 못 찾음 ${noImage})`);

  let cursor = 0, done = 0;
  await Promise.all(Array.from({ length: CONCURRENCY }, async () => {
    while (cursor < jobs.length) {
      const job = jobs[cursor++];
      try { await job(); done++; if (done % 500 === 0) console.log(`  ${done}장…`); }
      catch (e) { console.warn('실패:', e.message); }
    }
  }));

  fs.writeFileSync(path.join(OUT, 'ground_truth.csv'), 'file,class_index,class_name\n' + rows.join('\n') + '\n', 'utf8');
  fs.copyFileSync(CLASSES, path.join(OUT, 'labels.txt'));
  const covered = new Set(rows.map((r) => r.split(',')[1])).size;
  console.log(`완료: ${done}장, 클래스 ${covered}종 커버`);
  console.log('다음: eval_set 을 tar 로 묶어 Drive 에 올리고 eval_heldout_colab.ipynb 를 실행한다');
})();
