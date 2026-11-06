// Run with Node and Playwright installed. No user data or app state is changed.
const { chromium } = require('playwright');
const { pathToFileURL } = require('node:url');
const path = require('node:path');
const assert = require('node:assert/strict');

(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await browser.newPage();
    const errors = [];
    page.on('pageerror', error => errors.push(String(error)));
    const url = pathToFileURL(path.resolve(__dirname, '../app/src/main/assets/muscle_load/index.html')).href;
    for (const mode of ['', '?mode=session']) {
      await page.goto(url + mode);
      await page.waitForFunction(() => document.body.dataset.ready === 'true');
      // Reproduce the device's zero-height percentage roots, then exercise resize recovery.
      await page.evaluate(() => {
        document.documentElement.style.height = '0px';
        document.body.style.height = '0px';
        document.getElementById('stage').style.height = '100%';
        window.dispatchEvent(new Event('resize'));
      });
      for (const [width, height] of [[320, 366], [390, 400], [768, 320]]) {
        await page.setViewportSize({ width, height });
        await page.waitForFunction(() => Math.abs(document.getElementById('stage').clientHeight - innerHeight) < 1);
        const sizes = await page.locator('svg').evaluateAll(nodes => nodes.map(e => {
          const r = e.getBoundingClientRect();
          return { x: r.x, y: r.y, width: r.width, height: r.height };
        }));
        assert.equal(sizes.length, 2);
        for (const r of sizes) {
          assert(r.width > 100 && r.height > 100, JSON.stringify(r));
          assert(r.x >= 0 && r.y >= 0 && r.x + r.width <= width + 1 && r.y + r.height <= height + 1);
        }
      }
      const fills = await page.evaluate(session => {
        const left = document.querySelector('[data-muscle="BICEPS_L"]');
        const right = document.querySelector('[data-muscle="BICEPS_R"]');
        const neutral = left.getAttribute('fill');
        if (session) {
          window.setUsedMuscles(['BICEPS_L']);
          window.setFatigue({BICEPS_R: 100}, 'BICEPS');
        } else window.setFatigue({BICEPS_L: 100}, 'BICEPS');
        return [neutral, left.getAttribute('fill'), right.getAttribute('fill')];
      }, Boolean(mode));
      assert.notEqual(fills[0], fills[1]);
      assert.equal(fills[0], fills[2]);
    }
    assert.deepEqual(errors, []);
    console.log('PASS: both modes, zero-height recovery, three viewport sizes, muscle colors and session isolation');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
