/* 로컬 근육도. 완료 화면은 사용 부위, 피로도 화면은 누적 추정 점수를 별도로 받는다. */
(() => {
  const stage = document.getElementById('stage');
  const sessionMode = new URLSearchParams(location.search).get('mode') === 'session';
  let draw = () => {}, meshes = [];
  let selected = '', state = {}, used = new Set();
  window.setFatigue = (values, focus) => { if (sessionMode) return; state = values; selected = focus || ''; paint(); };
  window.setUsedMuscles = keys => { if (!sessionMode) return; used = new Set(Array.isArray(keys) ? keys : []); paint(); };
  function paint() {
    for (const m of meshes) {
      const key = m.userData.muscle + '_' + m.userData.side;
      const value = sessionMode ? (used.has(key) ? 100 : 0) : state[key];
      const base = new THREE.Color(m.userData.skin ? '#d1d2cc' : '#deded9');
      if (Number.isFinite(value) && value > 0) {
        // TREX 라임→그린. 회색 0점에서 부드럽게 연결하며 완료 화면은 최대 강조.
        const t = Math.max(0, Math.min(1, value / 100));
        const lime = new THREE.Color('#c7e26b'), green = new THREE.Color('#759848');
        if (t <= .5) base.lerp(lime, t * 2);
        else base.copy(lime).lerp(green, (t - .5) * 2);
      }
      m.material.color.copy(base);
      m.material.emissive.set(selected && selected === m.userData.muscle ? '#25332b' : '#000000');
    }
    draw();
  }
  function failure() {
    stage.innerHTML = '<div id="notice">근육도를 불러오지 못했습니다.</div>';
  }
  try {
    const T = THREE, renderer = new T.WebGLRenderer({ antialias: true, alpha: false, powerPreference: 'low-power' });
    renderer.setPixelRatio(Math.min(devicePixelRatio, 1.5));
    renderer.setClearColor('#222527');
    renderer.outputColorSpace = T.SRGBColorSpace;
    renderer.toneMapping = T.ACESFilmicToneMapping;
    renderer.toneMappingExposure = 1.2;
    const canvas = renderer.domElement;
    stage.replaceChildren(canvas);
    const scene = new T.Scene(), camera = new T.OrthographicCamera(-1, 1, 1, -1, .1, 20);
    camera.position.set(0, .9, 5); camera.lookAt(0, .9, 0);
    scene.add(new T.HemisphereLight(0xffffff, 0x555a60, 1.3));
    for (const [x, y, z, k] of [[-3, 4, 4, 1.6], [3, 2, 1, .5], [1, 3, -4, 1]]) {
      const light = new T.DirectionalLight(0xffffff, k); light.position.set(x, y, z); scene.add(light);
    }
    const front = new T.Group(); scene.add(front);
    function bytes(s) { return Uint8Array.from(atob(s), c => c.charCodeAt(0)); }
    for (const p of TREX_ATLAS.parts) {
      const pos = new Int16Array(bytes(p.p).buffer), geometry = new T.BufferGeometry();
      geometry.setAttribute('position', new T.BufferAttribute(Float32Array.from(pos, v => v / TREX_ATLAS.scale), 3));
      geometry.setIndex(new T.BufferAttribute(new Uint16Array(bytes(p.i).buffer), 1)); geometry.computeVertexNormals();
      const mesh = new T.Mesh(geometry, new T.MeshStandardMaterial({ color: '#deded9', roughness: .62, metalness: .02 }));
      mesh.userData = { muscle: p.muscle, side: p.side, skin: ['head_surface', 'eyes', 'hand_skin', 'foot_skin', 'connective'].includes(p.group) };
      front.add(mesh); meshes.push(mesh);
    }
    let pendingFrame = 0, lastWidth = 0, lastHeight = 0;
    draw = () => {
      if (pendingFrame || lastWidth <= 0 || lastHeight <= 0) return;
      pendingFrame = requestAnimationFrame(() => { pendingFrame = 0; renderer.render(scene, camera); });
    };
    function size() {
      // Android 스크롤 안 WebView에서는 100vh가 0으로 계산될 수 있어 CSS 픽셀로 확정한다.
      stage.style.height = Math.max(1, window.innerHeight || 400) + 'px';
      const w = stage.clientWidth, h = stage.clientHeight;
      if (w <= 0 || h <= 0) return;
      const aspect = w / h;
      if (w !== lastWidth || h !== lastHeight) renderer.setSize(w, h, false);
      lastWidth = w; lastHeight = h;
      const span = Math.max(1.92, .72 / aspect);
      camera.left = -span * aspect / 2; camera.right = span * aspect / 2;
      camera.top = span / 2; camera.bottom = -span / 2; camera.updateProjectionMatrix();
      draw();
    }
    canvas.tabIndex = 0;
    canvas.setAttribute('role', 'img');
    canvas.setAttribute('aria-label', (sessionMode ? '이번 운동 사용 근육' : '기록 기반 근육 피로도') + '. 드래그 또는 방향키로 회전, Home 키로 정면');
    let yaw = 0, pitch = 0, pointer = null, lastX = 0, lastY = 0;
    function orbit() {
      pitch = Math.max(-.8, Math.min(.8, pitch));
      yaw = ((yaw % (Math.PI * 2)) + Math.PI * 2) % (Math.PI * 2);
      camera.position.set(5 * Math.sin(yaw) * Math.cos(pitch), .9 + 5 * Math.sin(pitch), 5 * Math.cos(yaw) * Math.cos(pitch));
      camera.lookAt(0, .9, 0); draw();
    }
    canvas.addEventListener('pointerdown', e => {
      if (pointer !== null || (e.pointerType === 'mouse' && e.button !== 0)) return;
      pointer = e.pointerId; lastX = e.clientX; lastY = e.clientY;
      canvas.setPointerCapture(pointer); canvas.style.cursor = 'grabbing'; e.preventDefault();
    });
    canvas.addEventListener('pointermove', e => {
      if (e.pointerId !== pointer) return;
      yaw -= (e.clientX - lastX) / stage.clientWidth * Math.PI * 2;
      pitch += (e.clientY - lastY) / stage.clientHeight * Math.PI;
      lastX = e.clientX; lastY = e.clientY; orbit(); e.preventDefault();
    });
    const release = e => {
      if (e.pointerId !== pointer) return;
      pointer = null; canvas.style.cursor = 'grab';
      if (canvas.hasPointerCapture(e.pointerId)) canvas.releasePointerCapture(e.pointerId);
    };
    ['pointerup', 'pointercancel', 'lostpointercapture'].forEach(event => canvas.addEventListener(event, release));
    canvas.addEventListener('keydown', e => {
      if (e.key === 'ArrowLeft') yaw += .15;
      else if (e.key === 'ArrowRight') yaw -= .15;
      else if (e.key === 'ArrowUp') pitch += .1;
      else if (e.key === 'ArrowDown') pitch -= .1;
      else if (e.key === 'Home') { yaw = 0; pitch = 0; }
      else return;
      e.preventDefault(); orbit();
    });
    new ResizeObserver(size).observe(stage);
    window.addEventListener('resize', size);
    window.addEventListener('pageshow', size);
    document.addEventListener('visibilitychange', () => { if (!document.hidden) size(); });
    canvas.addEventListener('webglcontextlost', e => { e.preventDefault(); failure(); });
    size(); paint(); document.body.dataset.ready = 'true';
  } catch (e) { failure(); document.body.dataset.error = String(e); }
})();
