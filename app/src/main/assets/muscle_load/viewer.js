/* Android·iOS 공용 2D 근육도. 왼쪽 뒷면 / 오른쪽 앞면, 회전·WebGL 없음. */
(() => {
  const stage = document.getElementById('stage');
  const sessionMode = new URLSearchParams(location.search).get('mode') === 'session';
  const ns = 'http://www.w3.org/2000/svg';
  const regions = [];
  let selected = '', state = {}, used = new Set();
  function color(value) {
    const t = Math.max(0, Math.min(1, Number.isFinite(value) ? value / 100 : 0));
    const stops = [[222, 222, 217], [199, 226, 107], [117, 152, 72]];
    const a = t <= .5 ? stops[0] : stops[1], b = t <= .5 ? stops[1] : stops[2];
    const f = t <= .5 ? t * 2 : (t - .5) * 2;
    return `rgb(${a.map((v, i) => Math.round(v + (b[i] - v) * f)).join(',')})`;
  }
  function paint() {
    for (const {path, key} of regions) {
      const value = sessionMode ? (used.has(key) ? 100 : 0) : state[key];
      path.setAttribute('fill', color(value));
      const focused = selected && key.slice(0, -2) === selected;
      path.setAttribute('stroke', focused ? '#f1ffc7' : '#94988b');
      path.setAttribute('stroke-width', focused ? '2.5' : '.65');
    }
  }
  window.setFatigue = (values, focus) => {
    if (sessionMode) return;
    state = values || {}; selected = focus || ''; paint();
  };
  window.setUsedMuscles = keys => {
    if (!sessionMode) return;
    used = new Set(Array.isArray(keys) ? keys : []); paint();
  };
  try {
    const atlas = window.TREX_ATLAS_2D;
    stage.replaceChildren();
    for (const [view, title] of [['back', '뒷면'], ['front', '앞면']]) {
      const figure = document.createElement('figure');
      figure.dataset.view = view;
      const caption = document.createElement('figcaption');
      caption.textContent = title;
      const svg = document.createElementNS(ns, 'svg');
      svg.setAttribute('viewBox', atlas.viewBox);
      svg.setAttribute('role', 'img');
      svg.setAttribute('aria-label', `${title} 근육도`);
      for (const shape of atlas[view]) {
        const path = document.createElementNS(ns, 'path');
        path.setAttribute('d', shape.path);
        path.setAttribute('fill-rule', 'evenodd');
        path.setAttribute('stroke-linejoin', 'round');
        path.setAttribute('fill', '#d1d2cc');
        if (shape.key) {
          path.dataset.muscle = shape.key;
          regions.push({path, key: shape.key});
        }
        svg.appendChild(path);
      }
      figure.append(caption, svg); stage.appendChild(figure);
    }
    paint(); document.body.dataset.ready = 'true';
  } catch (error) {
    stage.textContent = '근육도를 불러오지 못했습니다.';
    document.body.dataset.error = String(error);
  }
})();
