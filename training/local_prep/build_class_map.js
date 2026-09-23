// AI Hub 74번: 영양DB.xlsx 의 행 순서가 클래스 코드(8자리) 정렬 순서와 1:1 이라는 관측을 바탕으로
// 코드 → 음식명 → 1인분 영양값 표를 만든다. 실행: node build_class_map.js
// 입력: labels_val/xlsx_unz (영양DB 압축 해제본), labels_val/unz/xml (클래스 코드 폴더 목록)
// 출력: ../class_map.json, ../class_map.csv
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const XLSX_DIR = path.join(ROOT, 'labels_val', 'xlsx_unz');
const CODE_DIR = path.join(ROOT, 'labels_val', 'unz', 'xml');

function decodeXml(s) {
  return s.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&amp;/g, '&');
}

// sharedStrings: <si> 마다 <t>…</t> 조각을 이어 붙인다(rich text 대응)
const ssXml = fs.readFileSync(path.join(XLSX_DIR, 'xl', 'sharedStrings.xml'), 'utf8');
const shared = [];
for (const si of ssXml.matchAll(/<si>([\s\S]*?)<\/si>/g)) {
  const parts = [...si[1].matchAll(/<t[^>]*>([\s\S]*?)<\/t>/g)].map((m) => decodeXml(m[1]));
  shared.push(parts.join(''));
}

// sheet1: 행별 셀 값. t="s" 는 sharedStrings 인덱스, 아니면 숫자.
const sheetXml = fs.readFileSync(path.join(XLSX_DIR, 'xl', 'worksheets', 'sheet1.xml'), 'utf8');
const rows = [];
for (const row of sheetXml.matchAll(/<row [^>]*r="(\d+)"[^>]*>([\s\S]*?)<\/row>/g)) {
  const cells = {};
  for (const c of row[2].matchAll(/<c r="([A-Z]+)\d+"([^>]*)>(?:<f>[\s\S]*?<\/f>)?<v>([^<]*)<\/v><\/c>/g)) {
    const [, col, attrs, v] = c;
    cells[col] = /t="s"/.test(attrs) ? shared[Number(v)] : Number(v);
  }
  rows.push({ r: Number(row[1]), cells });
}
const header = rows.find((r) => r.r === 1).cells;
console.log('헤더:', Object.values(header).join(' | '));

const dataRows = rows
  .filter((r) => r.r >= 2 && typeof r.cells.A === 'string')
  .map((r) => ({
    name: String(r.cells.A).trim(),
    weightG: r.cells.B, kcal: r.cells.C, carb: r.cells.D, sugar: r.cells.E, fat: r.cells.F, protein: r.cells.G,
    sodiumMg: r.cells.J,
  }))
  .filter((r) => r.name && r.name !== '-'); // 코드가 없는 빈 행 제거

const codes = fs.readdirSync(CODE_DIR).filter((d) => /^\d{8}$/.test(d)).sort();
if (codes.length !== dataRows.length) {
  throw new Error(`코드 ${codes.length}개 vs 영양DB 이름 ${dataRows.length}개 — 1:1 가정이 깨졌다`);
}

const map = codes.map((code, i) => ({ index: i, code, category: code.slice(0, 2), ...dataRows[i] }));

// 대분류가 바뀌는 지점의 앞뒤 이름을 찍어 정렬 가정을 눈으로 검증한다
console.log('\n대분류 경계 검증:');
for (let i = 1; i < map.length; i++) {
  if (map[i].category !== map[i - 1].category) {
    console.log(`  ${map[i - 1].code} ${map[i - 1].name}  →  ${map[i].code} ${map[i].name}`);
  }
}
console.log('\n마지막 5개:', map.slice(-5).map((m) => `${m.code} ${m.name}`).join(', '));

fs.writeFileSync(path.join(ROOT, 'class_map.json'), JSON.stringify(map, null, 1), 'utf8');
const csv = ['index,code,category,name,weight_g,kcal,carb_g,sugar_g,fat_g,protein_g,sodium_mg']
  .concat(map.map((m) => [m.index, m.code, m.category, m.name, m.weightG, m.kcal, m.carb, m.sugar, m.fat, m.protein, m.sodiumMg].join(',')))
  .join('\n');
fs.writeFileSync(path.join(ROOT, 'class_map.csv'), '﻿' + csv, 'utf8');
console.log(`\n저장: class_map.json / class_map.csv (${map.length}종)`);
