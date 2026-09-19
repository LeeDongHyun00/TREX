// dataset_final/nutrition.json(AI Hub 영양DB 실측 1인분 값)에서 앱 TrexData.kt 의 foodDatabase 블록을 만든다.
// 기존 큐레이션 9종(닭가슴살·바나나 등 모델에 없는 일반 식품)은 남기고, 내가 추정으로 넣었던 30종은 실측치로 교체한다.
// 사용: node gen_food_db.js > food_db_block.kt
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const items = JSON.parse(fs.readFileSync(path.join(ROOT, 'dataset_final', 'nutrition.json'), 'utf8'));

// 모델 클래스에 없지만 직접 기록 검색에 쓰이는 일반 식품 — 기존 값 유지
const KEEP = [
  ['닭가슴살', 165, 0.0, 31.0, 3.6],
  ['현미밥', 220, 46.0, 5.0, 1.7],
  ['바나나', 89, 23.0, 1.1, 0.3],
  ['오트밀', 150, 27.0, 5.0, 3.0],
  ['그릭요거트', 100, 4.0, 17.0, 0.0],
  ['고구마', 130, 30.0, 2.0, 0.1],
  ['샐러드', 120, 8.0, 4.0, 7.0],
  ['사과', 95, 25.0, 0.5, 0.3],
  ['아몬드', 160, 6.0, 6.0, 14.0],
];

const modelNames = new Set(items.map((i) => i.name));
const kept = KEEP.filter(([name]) => !modelNames.has(name)); // 모델에 같은 이름이 있으면 실측치를 쓴다
const dropped = KEEP.filter(([name]) => modelNames.has(name)).map(([n]) => n);

const r1 = (v) => Math.round(v * 10) / 10;
const line = (name, kcal, carb, protein, fat) =>
  `    "${name}" to Nutrition(${Math.round(kcal)}, ${r1(carb).toFixed(1)}, ${r1(protein).toFixed(1)}, ${r1(fat).toFixed(1)}),`;

const out = [];
out.push('val foodDatabase = linkedMapOf(');
out.push('    // 모델 클래스에 없는 일반 식품 — 직접 기록 검색용');
for (const [n, k, c, p, f] of kept) out.push(line(n, k, c, p, f));
out.push('    // 아래는 음식 인식 모델(yolov8n_food)의 클래스와 1:1 대응한다.');
out.push('    // AI Hub 74번 "음식분류 AI 데이터 영양DB" 의 1인분 실측값 — 괄호 안은 1인분 기준 중량.');
for (const it of items) {
  out.push(`${line(it.name, it.kcal, it.carb, it.protein, it.fat)}  // ${it.weightG}g`);
}
out.push(')');

console.error(`클래스 ${items.length}종 + 일반 식품 ${kept.length}종 = ${items.length + kept.length}개`);
if (dropped.length) console.error(`모델에 같은 이름이 있어 실측치로 대체: ${dropped.join(', ')}`);
console.log(out.join('\n'));
