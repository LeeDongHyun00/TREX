/* 실제 로컬 자산을 PC에서 검사한다. 검증 값은 앱 기록에 저장하지 않는다. */
const { chromium } = require('C:/Users/hp276/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const fs = require('fs'), path = require('path'), assert = require('assert');
const root = path.resolve(__dirname, '../..'), assets = path.join(root, 'app/src/main/assets/muscle_load');
const fatigue = process.argv.includes('--fatigue');
const out = path.join(root, process.env.TREX_VERIFY_OUTPUT || 'outputs/session-muscle-map', fatigue ? 'fatigue' : 'session'); fs.mkdirSync(out, { recursive: true });
(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--enable-unsafe-swiftshader'] });
  const page = await browser.newPage({ viewport: { width: 320, height: 400 }, hasTouch: true });
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  await page.route('https://appassets.androidplatform.net/muscle_load/*', route => {
    const file = path.basename(new URL(route.request().url()).pathname);
    return route.fulfill({ body: fs.readFileSync(path.join(assets, file)), contentType: file.endsWith('.html') ? 'text/html' : 'application/javascript' });
  });
  await page.goto('https://appassets.androidplatform.net/muscle_load/index.html' + (fatigue ? '' : '?mode=session'));
  await page.waitForSelector('body[data-ready=true]');
  assert.equal(await page.locator('nav').isVisible(), false);
  assert.equal(await page.locator('body').innerText(), '');
  const canvas = page.locator('canvas');
  // 키보드 포커스 테두리는 제외하고 실제 모델 픽셀만 비교한다.
  const capture = async (options = {}) => { const b = await canvas.boundingBox(); return page.screenshot({ ...options, clip: { x: b.x + 4, y: b.y + 4, width: b.width - 8, height: b.height - 8 } }); };
  const neutral = await capture();
  await page.evaluate(fatigue => fatigue ? window.setFatigue({ QUADS_L: 45, QUADS_R: 20, GLUTES_L: 30, GLUTES_R: 15 }, '') : window.setUsedMuscles(['QUADS_L', 'QUADS_R', 'GLUTES_L', 'GLUTES_R']), fatigue);
  const front = await capture({ path: path.join(out, 'front-fixture.png') });
  assert(!neutral.equals(front), '사용 근육 색이 반영되지 않음');
  await page.evaluate(fatigue => fatigue ? window.setUsedMuscles(['BICEPS_L']) : window.setFatigue({ QUADS_L: 0, QUADS_R: 0 }, ''), fatigue);
  assert(front.equals(await capture()), '완료 근육도가 누적 피로 입력에 영향받음');
  await page.mouse.move(240, 210); await page.mouse.down(); await page.mouse.move(80, 210, { steps: 16 }); await page.mouse.up();
  const back = await capture({ path: path.join(out, 'back-drag-fixture.png') });
  assert(!front.equals(back), '드래그 회전 실패');
  await canvas.focus(); await page.keyboard.press('Home');
  assert(front.equals(await capture()), '정면 복귀 실패');
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: 220, y: 200 }] });
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: 150, y: 255 }] });
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  assert(!front.equals(await capture({ path: path.join(out, 'oblique-touch-fixture.png') })), '터치 회전 실패');
  await page.evaluate(fatigue => fatigue ? window.setFatigue({}, '') : window.setUsedMuscles([]), fatigue);
  await canvas.focus(); await page.keyboard.press('Home');
  assert(neutral.equals(await capture()), '강조 해제 시 이전 세션 색이 남음');
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth || document.documentElement.scrollHeight > innerHeight), false);
  assert.deepEqual(errors, []);
  fs.writeFileSync(path.join(out, 'viewer-verification.json'), JSON.stringify({ errors, buttonsHidden: true, visibleCopy: false, highlightAndClear: true, otherModeInputIgnored: true, mode: fatigue ? 'fatigue' : 'session', mouseAndTouchRotation: true, homeReset: true, overflow: false, scope: 'PC Chromium 실제 앱 자산. Android 터치/실휴대폰 UI 검증 아님.' }, null, 2));
  await browser.close(); console.log('Session viewer checks passed');
})().catch(error => { console.error(error); process.exit(1); });
