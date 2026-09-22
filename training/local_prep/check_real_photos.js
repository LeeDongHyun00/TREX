// 실사용 식단 사진의 파일명에 적힌 기대 음식이 모델 클래스(342종)에 있는지 확인한다.
// 추론 전에 "애초에 맞출 수 있는 음식인가"를 가려야 인식 실패의 원인을 구분할 수 있다.
//   node check_real_photos.js "<사진 폴더>"
const fs = require('fs');
const path = require('path');

const dir = process.argv[2];
if (!dir) { console.error('사진 폴더를 넘겨라'); process.exit(1); }

const ROOT = path.resolve(__dirname, '..');
const classes = fs.readFileSync(path.join(ROOT, 'dataset_final', 'food_labels.txt'), 'utf8')
  .split(/\r?\n/).map((s) => s.trim()).filter(Boolean);
const classSet = new Set(classes);

// 파일명 "식단 (A, B, C).jpg" 에서 기대 음식 목록을 뽑는다.
const files = fs.readdirSync(dir).filter((f) => /\.(jpe?g|png)$/i.test(f)).sort();
const parsed = files.map((f) => {
  const m = /\(([^)]*)\)/.exec(f);
  const items = m ? m[1].split(',').map((s) => s.trim()).filter(Boolean) : [];
  return { file: f, expected: items };
});

// 이름이 정확히 같지 않아도 클래스에 사실상 대응하는 경우를 사람이 보고 판단하도록 후보를 보여준다.
function candidates(name) {
  const n = name.replace(/\s+/g, '');
  const exact = classSet.has(name) ? [name] : [];
  if (exact.length) return { hit: true, list: exact };
  const partial = classes.filter((c) => {
    const cc = c.replace(/\s+/g, '');
    return cc.includes(n) || n.includes(cc);
  });
  return { hit: false, list: partial.slice(0, 4) };
}

let total = 0, covered = 0;
const missing = new Map();
console.log('사진별 기대 음식 · 클래스 존재 여부\n');
for (const p of parsed) {
  console.log(`[${p.file}]`);
  for (const item of p.expected) {
    total++;
    const r = candidates(item);
    if (r.hit) { covered++; console.log(`   O ${item}`); }
    else if (r.list.length) { console.log(`   ~ ${item}  → 비슷한 클래스: ${r.list.join(', ')}`); missing.set(item, (missing.get(item) || 0) + 1); }
    else { console.log(`   X ${item}  → 해당 클래스 없음`); missing.set(item, (missing.get(item) || 0) + 1); }
  }
  console.log('');
}

console.log('='.repeat(60));
console.log(`기대 음식 ${total}개 중 클래스에 정확히 있는 것 ${covered}개 (${(covered / total * 100).toFixed(0)}%)`);
console.log(`사진 ${parsed.length}장, 장당 평균 ${(total / parsed.length).toFixed(1)}개`);
const sorted = [...missing.entries()].sort((a, b) => b[1] - a[1]);
console.log(`\n클래스에 없는 음식 ${sorted.length}종:`);
console.log('  ' + sorted.map(([n, c]) => `${n}(${c})`).join(', '));
