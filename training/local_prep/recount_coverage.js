// 실사용 사진의 기대 음식이 앱에서 실제로 도달 가능한지 다시 센다.
//   node recount_coverage.js   (저장소 루트에서 실행)
// check_coverage_detail.js 는 "정확히 같은 이름의 클래스가 있는가"로 셌다. 그 기준은
// 우동→일식우동, 장국→미소된장국, 양배추→양배추구이 처럼 앱이 실제로 찾아주는 것을
// 놓쳐 없는 음식 비율을 부풀렸다(2026-09-23 정정, docs/FOOD_COVERAGE_FINDINGS.md).
// 별칭 표는 TrexData.kt 에서 직접 읽어 코드와 문서가 어긋나지 않게 한다.
const fs = require('fs');
const rd = p => fs.readFileSync(p, 'utf8').split(/\r?\n/).map(s => s.trim()).filter(Boolean);

const cls342 = rd('training/local_prep/food_labels_342.txt');
const cls400 = rd('C:/Workspace/TREX/aihub74_raw/dataset_final/food_labels.txt');

// TrexData.kt 의 별칭 표를 그대로 읽는다 — 문서와 코드가 어긋나지 않게.
const kt = fs.readFileSync('app/src/main/java/com/example/trex_kotlin/TrexData.kt', 'utf8');
const block = /val foodNameAliases[^(]*\(([\s\S]*?)\n\)/.exec(kt)[1];
const aliases = new Map([...block.matchAll(/"([^"]+)"\s+to\s+"([^"]+)"/g)].map(m => [m[1], m[2]]));

// AppViewModel.searchFoods 와 같은 규칙: 클래스명이 질의를 포함하거나, 별칭이 가리키는 클래스.
function reachable(query, classes) {
  const set = new Set(classes);
  const viaAlias = [...aliases.entries()].filter(([k]) => k.includes(query)).map(([, v]) => v);
  const hits = classes.filter(c => c.includes(query)).concat(viaAlias.filter(v => set.has(v)));
  return [...new Set(hits)];
}

const rows = fs.readFileSync('C:/Workspace/TREX/aihub74_raw/eval_real/ground_truth_multi.csv', 'utf8')
  .split(/\r?\n/).filter(Boolean);
// 필드가 따옴표로 감싸여 있고 안에 | 가 들어간다 — split(',') 로는 안 된다.
const cells = line => [...line.matchAll(/"([^"]*)"/g)].map(m => m[1]);
const hdr = rows.shift().split(',');
const iRaw = hdr.indexOf('expected_raw');

const counts = new Map();
for (const r of rows) for (const n of (cells(r)[iRaw] || '').split('|').map(x => x.trim()).filter(Boolean))
  counts.set(n, (counts.get(n) || 0) + 1);

const NOT_FOOD = new Set(['물', '국', '그 외 반찬']);
let tot = 0, r342 = 0, r400 = 0;
const gained = [], still = [];
for (const [name, n] of [...counts].sort((a, b) => b[1] - a[1])) {
  if (NOT_FOOD.has(name)) continue;          // 음식 이름이 아니라 세지 않는다
  tot += n;
  const a = reachable(name, cls342), b = reachable(name, cls400);
  if (a.length) r342 += n;
  if (b.length) r400 += n;
  if (!a.length && b.length) gained.push(`${name}(${n}) → ${b.slice(0, 2).join(',')}`);
  if (!b.length) still.push(`${name}(${n})`);
}
const pct = x => `${Math.round(x / tot * 100)}%`;
console.log(`기대 음식 ${tot}개 (사진 ${rows.length}장, '물'·'국'·'그 외 반찬' 제외)`);
console.log(`  342종으로 도달 가능: ${r342}개 (${pct(r342)})`);
console.log(`  400종으로 도달 가능: ${r400}개 (${pct(r400)})`);
console.log(`\n400에서 새로 닿는 것: ${gained.join(' · ') || '없음'}`);
console.log(`\n여전히 못 닿는 것 (${tot - r400}개, ${pct(tot - r400)}): ${still.join(' · ')}`);
