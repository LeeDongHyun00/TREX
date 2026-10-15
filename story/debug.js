/*
 * A frame meter over the story, shown with ?debug in the page's address, to
 * check on a phone how it runs: how evenly frames come, what the last frame
 * drawn cost, what the GPU holds, and how many shaders compiled after the
 * story was ready, which should stay at none (see warmUp() in story.js).
 */

// A frame this late shows as a stutter; a gap this long is the page having
// been hidden rather than a stutter.
const HITCH = 50;
const HIDDEN = 1000;
// The gaps between frames are taken over this many ms of frames.
const WINDOW = 2000;

const percentile = (sorted, p) => sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * p))] || 0;

export function meter(renderer, root) {
  const panel = document.createElement('pre');
  panel.setAttribute('aria-hidden', 'true');
  Object.assign(panel.style, {
    position: 'fixed',
    zIndex: '2147483647',
    left: 'max(6px, env(safe-area-inset-left))',
    top: 'max(6px, env(safe-area-inset-top))',
    margin: '0',
    padding: '6px 8px',
    borderRadius: '6px',
    background: 'rgba(12, 16, 12, 0.8)',
    color: '#c7e26b',
    font: '10px/1.45 ui-monospace, Menlo, Consolas, monospace',
    whiteSpace: 'pre',
    pointerEvents: 'none',
  });
  document.body.append(panel);

  // A frame's renders all run in one go, so a burst of them, closed once the
  // code drawing the frame has finished, is one frame's drawing.
  const info = renderer.info;
  const render = renderer.render.bind(renderer);
  let burst = null, last = null, drawn = 0;
  renderer.render = (scene, camera) => {
    if (!burst) {
      burst = { calls: 0, triangles: 0, ms: 0 };
      queueMicrotask(() => {
        last = burst;
        burst = null;
        drawn += 1;
      });
    }
    const start = performance.now();
    render(scene, camera);
    burst.ms += performance.now() - start;
    burst.calls += info.render.calls;
    burst.triangles += info.render.triangles;
  };

  let ready = null;
  let hitches = 0, tasks = 0, longest = 0;
  root.addEventListener('story:ready', () => {
    ready = { at: performance.now(), programs: info.programs.length };
  }, { once: true });
  try {
    new PerformanceObserver((list) => {
      if (!ready) return;
      for (const entry of list.getEntries()) {
        tasks += 1;
        longest = Math.max(longest, entry.duration);
      }
    }).observe({ type: 'longtask' });
  } catch {
    // Not every browser reports long tasks.
  }

  const frames = []; // [time, gap since the frame before]
  let previous = performance.now(), shown = 0;
  function tick(now) {
    const gap = now - previous;
    previous = now;
    if (gap < HIDDEN) {
      frames.push([now, gap]);
      if (ready && gap > HITCH) hitches += 1;
    }
    while (frames.length && frames[0][0] < now - WINDOW) frames.shift();
    if (now - shown > 250) {
      shown = now;
      panel.textContent = report(now);
    }
    requestAnimationFrame(tick);
  }
  requestAnimationFrame(tick);

  function report(now) {
    const gaps = frames.map(([, gap]) => gap).sort((a, b) => a - b);
    const fps = frames.filter(([time]) => time > now - 1000).length;
    const canvas = renderer.domElement;
    const ratio = renderer.getPixelRatio();
    const late = ready ? info.programs.length - ready.programs : 0;
    const ms = (v) => (v < 100 ? v.toFixed(1) : Math.round(v));
    return [
      `${fps} fps · gaps ${ms(percentile(gaps, 0.5))} / ${ms(percentile(gaps, 0.95))} / ${ms(gaps[gaps.length - 1] || 0)} ms`,
      '  median / 95% / worst, last 2 s',
      ready ? `since ready: ${hitches} frames over ${HITCH} ms` : 'loading…',
      ready ? `  ${tasks} long tasks, longest ${Math.round(longest)} ms` : '',
      last ? `drawn: ${last.calls} calls · ${(last.triangles / 1000).toFixed(1)}k tris · ${last.ms.toFixed(1)} ms` : 'nothing drawn yet',
      `  ${drawn} frames drawn`,
      `GPU: ${info.programs.length} shaders · ${info.memory.geometries} geom · ${info.memory.textures} tex`,
      ready ? `shaders since ready: ${late} · ready at ${(ready.at / 1000).toFixed(1)} s` : '',
      `canvas ${canvas.width}×${canvas.height} (${Math.round(canvas.width / ratio)}×${Math.round(canvas.height / ratio)} at ${ratio}x)`,
    ].filter(Boolean).join('\n');
  }
}
