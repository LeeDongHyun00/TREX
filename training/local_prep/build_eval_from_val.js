// 우리 val 분할(342클래스)로 평가셋을 만든다.
//
// Training 원천은 클래스별로 쪼개져 있어(묶음 하나에 8클래스) 342종을 덮으려면 1.2TB 가 든다.
// 그래서 전체 클래스 그림은 val 분할로 본다. val 은 gradient 학습에 쓰이지 않았지만 조기 종료
// 판단에는 쓰였으므로 **약간 낙관적**이다 — 진짜 held-out(밥 8종)과 같이 보고 편향을 가늠한다.
//
//   node build_eval_from_val.js [--per-class 0]   (0 = 전부)
// 출력: ../eval_val/{images/, ground_truth.csv, labels.txt}
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const SRC = path.join(ROOT, 'dataset_final');
const OUT = path.join(ROOT, 'eval_val');
const args = process.argv.slice(2);
const opt = (k, d) => { const i = args.indexOf(k); return i === -1 ? d : args[i + 1]; };
const perClass = Number(opt('--per-class', '0'));

const labels = fs.readFileSync(path.join(SRC, 'food_labels.txt'), 'utf8').split(/\r?\n/).map((s) => s.trim()).filter(Boolean);

fs.rmSync(OUT, { recursive: true, force: true });
fs.mkdirSync(path.join(OUT, 'images'), { recursive: true });

const byClass = new Map();
for (const f of fs.readdirSync(path.join(SRC, 'labels', 'val'))) {
  const lines = fs.readFileSync(path.join(SRC, 'labels', 'val', f), 'utf8').split(/\r?\n/).filter(Boolean);
  if (lines.length === 0) continue;
  // 한 사진에 박스가 여럿일 수 있으나 이 데이터셋은 메뉴 1종이라 첫 줄의 클래스를 정답으로 쓴다.
  const idx = Number(lines[0].split(' ')[0]);
  if (!byClass.has(idx)) byClass.set(idx, []);
  byClass.get(idx).push(f.replace(/\.txt$/, '.jpg'));
}

const rows = [];
for (const [idx, files] of [...byClass.entries()].sort((a, b) => a[0] - b[0])) {
  const take = perClass > 0 ? files.slice(0, perClass) : files;
  for (const img of take) {
    const src = path.join(SRC, 'images', 'val', img);
    if (!fs.existsSync(src)) continue;
    fs.copyFileSync(src, path.join(OUT, 'images', img));
    rows.push(`${img},${idx},${labels[idx]}`);
  }
}

fs.writeFileSync(path.join(OUT, 'ground_truth.csv'), 'file,class_index,class_name\n' + rows.join('\n') + '\n', 'utf8');
fs.copyFileSync(path.join(SRC, 'food_labels.txt'), path.join(OUT, 'labels.txt'));
console.log(`완료: ${rows.length}장, 클래스 ${byClass.size}종`);
