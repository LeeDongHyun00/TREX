// 실사용 사진의 기대 음식을 세 갈래로 가른다.
//   1) 학습한 342클래스에 있음 (이름만 다를 수 있음 → 별칭 표로 맞춘다)
//   2) AI Hub 400종에는 있는데 우리가 안 받은 58종에 속함 → 묶음을 더 받으면 해결
//   3) AI Hub 데이터셋 자체에 없음 → 데이터로는 해결 불가(사용자 직접 등록 영역)
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const trained = new Set(
  fs.readFileSync(path.join(ROOT, 'dataset_final', 'food_labels.txt'), 'utf8')
    .split(/\r?\n/).map((s) => s.trim()).filter(Boolean),
);
const all400 = JSON.parse(fs.readFileSync(path.join(ROOT, 'class_map.json'), 'utf8'));
const byName = new Map(all400.map((m) => [m.name, m]));

// 사람이 부르는 이름 → 데이터셋 클래스명. 같은 음식인데 표기가 다른 경우만 넣는다.
const ALIAS = {
  '흰쌀밥': '쌀밥',
  '장국': '미소된장국',
  '된장국': '미소된장국',
  '국': '미소된장국',
  '돈까스': '돈가스',
  '덴푸라': '모둠튀김',
  '가라아게': '후라이드치킨',
  '단무지': '단무지무침',
  '김치': '배추김치',
  '깍두기': '깍두기',
  '소면': '잔치국수',
  '냉모밀': '메밀국수',
  '모듬초밥': '모둠초밥',
  '초밥': '모둠초밥',
  '토마토스파게티': '토마토소스스파게티',
  '야채무침': '무생채',
  '양념고기': '불고기',
  '찐대게': '대게찜',
  '오징어 순대': '오징어순대',
};

const dir = process.argv[2];
const files = fs.readdirSync(dir).filter((f) => /\.(jpe?g|png)$/i.test(f)).sort();

const buckets = { trained: [], missingBundle: [], notInDataset: [] };
const perPhoto = [];

for (const f of files) {
  const m = /\(([^)]*)\)/.exec(f);
  const items = m ? m[1].split(',').map((s) => s.trim()).filter(Boolean) : [];
  const detail = items.map((raw) => {
    const name = ALIAS[raw] ?? raw;
    if (trained.has(name)) { buckets.trained.push(raw); return { raw, name, state: 'trained' }; }
    if (byName.has(name)) { buckets.missingBundle.push(raw); return { raw, name, state: 'bundle' }; }
    buckets.notInDataset.push(raw);
    return { raw, name, state: 'none' };
  });
  perPhoto.push({ file: f, detail });
}

const mark = { trained: 'O', bundle: '+', none: 'X' };
for (const p of perPhoto) {
  const line = p.detail.map((d) => `${mark[d.state]}${d.raw}${d.name !== d.raw && d.state !== 'none' ? `→${d.name}` : ''}`).join('  ');
  console.log(`${p.file.replace(/^식단 |\.jpg$/g, '')}\n    ${line}`);
}

const total = buckets.trained.length + buckets.missingBundle.length + buckets.notInDataset.length;
console.log('\n' + '='.repeat(64));
console.log(`기대 음식 ${total}개 (사진 ${files.length}장, 장당 ${(total / files.length).toFixed(1)}개)`);
console.log(`  O 학습한 342클래스에 있음        ${buckets.trained.length}개 (${(buckets.trained.length / total * 100).toFixed(0)}%)`);
console.log(`  + 안 받은 58종에 있음(묶음 추가로 해결) ${buckets.missingBundle.length}개 (${(buckets.missingBundle.length / total * 100).toFixed(0)}%)`);
console.log(`  X 데이터셋에 아예 없음            ${buckets.notInDataset.length}개 (${(buckets.notInDataset.length / total * 100).toFixed(0)}%)`);
console.log(`\n묶음 추가로 살릴 수 있는 것: ${[...new Set(buckets.missingBundle)].join(', ')}`);
console.log(`\n데이터셋에 없는 것: ${[...new Set(buckets.notInDataset)].join(', ')}`);
