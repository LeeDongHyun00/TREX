// aihubshell 은 대용량 파일을 1GiB 씩 <이름>.part<바이트오프셋> 으로 쪼개 보낸다(예: 11.zip.part0, .part1073741824 …).
// 접미사는 순번이 아니라 오프셋이므로 반드시 숫자로 정렬해 이어 붙여야 한다. 사전순으로 붙이면 파일이 깨진다.
// 사용: node merge_parts.js <폴더>   → 하위의 모든 조각을 원본으로 합치고 조각을 지운다.
const fs = require('fs');
const path = require('path');

const root = process.argv[2];
if (!root) { console.error('사용: node merge_parts.js <폴더>'); process.exit(1); }

function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out); else out.push(p);
  }
  return out;
}

const groups = new Map();
for (const p of walk(root)) {
  const m = /^(.*)\.part(\d+)$/.exec(p);
  if (!m) continue;
  if (!groups.has(m[1])) groups.set(m[1], []);
  groups.get(m[1]).push({ path: p, offset: Number(m[2]) });
}

if (groups.size === 0) { console.log('병합할 조각이 없다'); process.exit(0); }

const CHUNK = 64 * 1024 * 1024;
for (const [base, list] of groups) {
  list.sort((a, b) => a.offset - b.offset);
  // 조각 크기가 오프셋과 맞는지 확인한다 — 빠진 조각이 있으면 합치면 안 된다.
  let expected = 0;
  for (const part of list) {
    if (part.offset !== expected) {
      throw new Error(`${path.basename(base)}: 조각이 빠졌다 — ${expected} 위치가 없다(다음은 ${part.offset})`);
    }
    expected += fs.statSync(part.path).size;
  }
  console.log(`병합: ${path.basename(base)} ← 조각 ${list.length}개, ${(expected / 1073741824).toFixed(1)}GB`);
  const fd = fs.openSync(base, 'w');
  const buf = Buffer.allocUnsafe(CHUNK);
  for (const part of list) {
    const src = fs.openSync(part.path, 'r');
    let n;
    while ((n = fs.readSync(src, buf, 0, CHUNK, null)) > 0) fs.writeSync(fd, buf, 0, n);
    fs.closeSync(src);
  }
  fs.closeSync(fd);
  const got = fs.statSync(base).size;
  if (got !== expected) throw new Error(`${path.basename(base)}: 크기 불일치 ${got} != ${expected}`);
  for (const part of list) fs.unlinkSync(part.path);
  console.log(`  완료: ${got.toLocaleString()} bytes, 조각 삭제`);
}
