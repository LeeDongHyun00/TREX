/*
 * The TREX day told on the carousel. A WebGL layer over the ring draws the
 * story's 3D models in step with it: each model rides on its card's face or
 * on the card's band of the far wall, and each card's beat plays out by
 * itself once the ring comes to rest at the card (see data-play and beat() in
 * carousel.js), while the drag turns the ring from one card to the next.
 *
 *   opening  a dumbbell, a barbell and a water bottle float on the white
 *            screen, and the coach says the line
 *   morning  the weather bursts out of the sun on the phone and goes back
 *   lunch    the phone photographs the burger and looks at the photo; the
 *            nutrient rings come out, fill and go back
 *   evening  the coach squats; a phone on the wall, turned to it, counts and
 *            corrects
 *   night    the coach's muscle load shows and tomorrow's picks come out of
 *            the phone on the wall and go back
 *   ending   the download button rises; it opens the TREX repository
 *
 * The coach walks between its scenes rather than turning away with a card:
 * off to the left as the ring turns on, in from the right as its next scene
 * comes round, and from the evening on into the night without leaving (see
 * walker()).
 *
 * The layer uses the ring's own camera: the scene is in CSS pixels, seen from
 * the ring's perspective distance with its field of view. Positions are the
 * storyboard's, drawn on a 390 x 844 screen, and scale with the stage's
 * width. A screen shorter than that for its width squeezes the scene: what is
 * not on a phone's screen moves up and shrinks to fit, as the captions do
 * (see index.html).
 */
import * as THREE from 'three';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';
import { MeshoptDecoder } from 'three/addons/libs/meshopt_decoder.module.js';
import { lightStage } from '../models/src/stage.js';
import { fillRing } from '../models/src/nutrition.js';
import { NIGHT_PICKS, routineCard } from '../models/src/cards.js';
import { labelButton, pressButton } from '../models/src/button.js';
import { chestOf, frameOn, loadSweep, poser, stepper } from './coach.js';
import {
  LIVE, MAP, PHONE, SCREEN_TEXT, WARN,
  canvasTexture, coachChip, coachScreen, correction, fatigueScreen, flat, mapChip, miniPhone, roundedRect, screenTexture,
  warnPanel,
} from './phone.js';

// The storyboard's front face: 91% of the 390 px screen wide, top at 44%.
const FACE = { left: 17.55, top: 371.36, width: 354.9 };
const DESIGN_WIDTH = 390;
const DESIGN_HEIGHT = 844;
const MODELS = new URL('../models/glb/', import.meta.url);

// The app's default daily goals and what the lunch burger adds to them.
const GOALS = { calories: 2110, carbs: 278, protein: 117, fat: 59 };
const BURGER = { calories: 618, carbs: 48, protein: 30, fat: 34 };

const reduced = matchMedia('(prefers-reduced-motion: reduce)');
const clamp = (v, lo = 0, hi = 1) => Math.min(hi, Math.max(lo, v));
const smooth = (t) => t * t * (3 - 2 * t);
const span = (t, a, b) => smooth(clamp((t - a) / (b - a)));
const easeOut = (t) => 1 - (1 - t) ** 3;
// Runs a little past 1 before settling, for things that pop out.
const backOut = (t) => 1 + 2.2 * (t - 1) ** 3 + 1.2 * (t - 1) ** 2;
const lerp = (a, b, t) => a + (b - a) * t;
const rad = (deg) => (deg * Math.PI) / 180;
// An angle folded into [-pi, pi].
const wrap = (a) => a - 2 * Math.PI * Math.round(a / (2 * Math.PI));
const report = (error) => console.error(error);

// The models are packed (see models/build.mjs) and come in through the
// page's prefetch (prefetch.js) when it has one.
const loader = new GLTFLoader().setMeshoptDecoder(MeshoptDecoder);
let fetched = null;
const loads = new Map();
function load(name) {
  if (!loads.has(name)) {
    const gltf = fetched
      ? fetched.get(name).then((buffer) => loader.parseAsync(buffer, ''))
      : loader.loadAsync(new URL(`${name}.glb`, MODELS).href);
    loads.set(name, gltf.then((model) => plainColours(model.scene)));
  }
  return loads.get(name);
}

// Packed models keep their colours as 16-bit integers with an alpha; the code
// that repaints the coach works on them as plain RGB floats.
function plainColours(model) {
  model.traverse((o) => {
    const color = o.isMesh && o.geometry.attributes.color;
    if (!color || (color.array instanceof Float32Array && color.itemSize === 3)) return;
    const rgb = new Float32Array(color.count * 3);
    for (let i = 0; i < color.count; i++) {
      rgb[3 * i] = color.getX(i);
      rgb[3 * i + 1] = color.getY(i);
      rgb[3 * i + 2] = color.getZ(i);
    }
    o.geometry.setAttribute('color', new THREE.BufferAttribute(rgb, 3));
  });
  return model;
}

/*
 * Wraps a model so that, turned by yaw, pitch and roll, its middle sits on the
 * wrapper's origin; userData.size is the turned model's box. The wrapper's
 * own rotation is left free for motion about that middle.
 */
function fit(model, { yaw = 0, pitch = 0.08, roll = 0 } = {}) {
  const turn = new THREE.Group();
  turn.rotation.set(pitch, yaw, roll);
  turn.add(model);
  turn.updateMatrixWorld(true);
  // Measured from the vertices: a box from geometry bounds would also take
  // in the coach's morph targets.
  const box = new THREE.Box3().setFromObject(turn, true);
  const shift = new THREE.Group();
  shift.position.copy(box.getCenter(new THREE.Vector3())).negate();
  shift.add(turn);
  const wrapper = new THREE.Group();
  wrapper.add(shift);
  wrapper.userData.size = box.getSize(new THREE.Vector3());
  wrapper.userData.scale = 0;
  // The model turns about its own origin in `turn`, which sits at `shift`.
  Object.assign(wrapper.userData, { turn, shift });
  return wrapper;
}

// A phone standing in a scene, as a wrapper that place() can size.
function stand(phone, pitch, yaw) {
  const wrapper = new THREE.Group();
  wrapper.add(phone.group);
  wrapper.rotation.set(pitch, yaw, 0);
  wrapper.userData.size = new THREE.Vector3(PHONE.width, PHONE.height, PHONE.depth);
  return wrapper;
}

async function fonts() {
  const text = SCREEN_TEXT + '다운로드' + NIGHT_PICKS.map(({ muscles }) => muscles).join('') + '운동 목록에 추가';
  try {
    await Promise.all(['500', '700', '800'].map((weight) => document.fonts.load(`${weight} 16px "Noto Sans KR"`, text)));
  } catch {
    // The canvases fall back to the system's Korean face.
  }
}

// models: the page's prefetch (StoryModels.fetch), if it has one.
export async function play(carousel, { models = null } = {}) {
  fetched = models;
  const root = carousel.root;
  const canvas = document.createElement('canvas');
  canvas.className = 'story__layer';
  canvas.setAttribute('aria-hidden', 'true');
  root.append(canvas);
  let renderer;
  try {
    renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true });
  } catch (error) {
    canvas.remove();
    throw error;
  }
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
  renderer.setClearColor(0x000000, 0);
  const scene = new THREE.Scene();
  lightStage(renderer, scene, { shadows: false });
  const camera = new THREE.PerspectiveCamera(carousel.options.fov, 1, 100, 10000);
  const fontsIn = fonts();
  // Setting up a scene once its models are in, each in a task of its own,
  // so the loading screen can draw between them rather than wait for them all.
  const jobs = [];
  const pause = () => new Promise((resolve) => setTimeout(resolve));
  const job = (promise, fn) => jobs.push(promise.then(async (value) => {
    await pause();
    return fn(value);
  }).catch(report));
  // ?debug in the page's address shows a frame meter (see debug.js).
  if (new URLSearchParams(location.search).has('debug')) {
    import('./debug.js').then(({ meter }) => meter(renderer, root)).catch(report);
  }

  // Compiles the shaders an object needs in the scene before it is first
  // drawn, off the page's thread where the browser can
  // (KHR_parallel_shader_compile), so they compile while the rest of the
  // models download. The same for a scene drawn into a render target, whose
  // shaders differ from the canvas's.
  const compile = (object) => renderer.compileAsync(object, camera, scene);
  function compileInto(target, into, eye) {
    renderer.setRenderTarget(target);
    const done = renderer.compileAsync(into, eye);
    renderer.setRenderTarget(null);
    return done;
  }
  // Done once under the loading screen, after the rest of warmUp().
  const warmups = [];

  // The ring and, on it, a group per card: its face on the near side and its
  // band of the far wall opposite, as carousel.css places them.
  const ring = new THREE.Group();
  scene.add(ring);
  // What stays put on the stage as the ring turns: the walking coach.
  const stage = new THREE.Group();
  scene.add(stage);
  const cards = carousel.cards.map((li, i) => {
    const card = new THREE.Group();
    const face = new THREE.Group();
    const wall = new THREE.Group();
    card.rotation.y = rad(i * carousel.step);
    card.add(face, wall);
    ring.add(card);
    return { li, i, card, face, wall, beats: [], idle: false };
  });
  const scenes = Object.fromEntries(cards.map((c) => [c.li.dataset.scene, c]));

  // s: stage px per storyboard px; squeeze: how much shorter the stage is than
  // the storyboard for its width (1 if it is not).
  const view = { width: 0, height: 0, p: 0, r: 0, s: 1, squeeze: 1, faceLeft: 0, faceTop: 0, faceY: 0 };
  const layouts = [];
  const before = []; // run before each render, to fill in the pictures on phones
  const later = (fn) => {
    layouts.push(fn);
    if (view.width) fn();
  };

  // Where a storyboard point shows on the stage, in px from its top left,
  // measured from the front face. A point on the phone's screen keeps to the
  // screen, which never squeezes.
  const onStage = (x, y, screen = false) => [
    view.faceLeft + (x - FACE.left) * view.s,
    view.faceTop + (y - FACE.top) * view.s * (screen ? 1 : view.squeeze),
  ];

  // A storyboard point on a card's face, `lift` design px in front of it, in
  // the face group's coordinates. Nearer things look bigger, so the point is
  // drawn in toward the middle to show where the storyboard has it, and k
  // says how much smaller things there must be made to keep their size.
  function onFace(x, y, lift = 0, { screen = false } = {}) {
    const [sx, sy] = onStage(x, y, screen);
    const z = lift * view.s * view.squeeze;
    const k = (view.p - z) / view.p;
    return { x: (sx - view.width / 2) * k, y: (view.height / 2 - sy) * k - view.faceY, z, k };
  }

  // A stage point (px from the top left) on a card's band of the far wall,
  // in the card's coordinates, as it shows with the card at the front; k says
  // how much bigger things there must be made to keep their size.
  function onWall(sx, sy) {
    const { p, r } = view;
    const target = sx - view.width / 2;
    let lo = -1.3, hi = 1.3;
    for (let n = 0; n < 40; n++) {
      const mid = (lo + hi) / 2;
      if ((r * Math.sin(mid) * p) / (p + r + r * Math.cos(mid)) < target) lo = mid;
      else hi = mid;
    }
    const phi = (lo + hi) / 2;
    const k = (p + r + r * Math.cos(phi)) / p;
    return { x: r * Math.sin(phi), y: (view.height / 2 - sy) * k, z: -r * Math.cos(phi), k };
  }

  // A storyboard point deep inside a card, `deep` of the way from its face to
  // the ring's middle, in the card's coordinates. It shows at that point while
  // the card rests, and moves less than the face as the ring turns.
  function inside(x, y, deep) {
    const [sx, sy] = onStage(x, y);
    const depth = deep * view.r;
    const k = (view.p + depth) / view.p;
    return { x: (sx - view.width / 2) * k, y: (view.height / 2 - sy) * k, z: view.r - depth, k };
  }

  // Puts a wrapper at a spot, `size` design px across (or tall, by 'y').
  function place(wrapper, spot, size, by = 'x') {
    const unit = by === 'y' ? wrapper.userData.size.y : wrapper.userData.size.x;
    wrapper.position.set(spot.x, spot.y, spot.z);
    wrapper.userData.scale = (size * view.s * view.squeeze * spot.k) / unit;
    wrapper.scale.setScalar(wrapper.userData.scale);
  }

  // Models that arrive while their card shows grow in rather than blink in;
  // under the loading screen, where nobody sees them, they are just there.
  const APPEAR = 450;
  const appearing = new Set();
  function arrive(c, ...wrappers) {
    if (Math.abs(carousel.offset(c.i)) >= 90 || reduced.matches || root.inert) return;
    for (const wrapper of wrappers) {
      wrapper.userData.born = performance.now();
      appearing.add(wrapper);
    }
    wake();
  }
  const grown = (wrapper, now) => {
    if (!appearing.has(wrapper)) return 1;
    const t = (now - wrapper.userData.born) / APPEAR;
    if (t >= 1) appearing.delete(wrapper);
    return backOut(clamp(t));
  };
  // Scales a wrapper by `amount` of its laid-out size, hiding it at 0.
  function show(wrapper, amount, now) {
    const scale = wrapper.userData.scale * amount * grown(wrapper, now);
    wrapper.visible = scale > 1e-4;
    wrapper.scale.setScalar(Math.max(scale, 1e-4));
  }

  /*
   * The coach walks between its scenes. As the ring turns on, it walks off
   * to the left over the first half of the turn, and the coach of the next
   * scene walks in from the right over the second half, so one coach shows
   * at a time; turned back, both walk the other way. Two scenes the coach
   * goes straight on between (join()) hand it over instead: it walks slowly
   * from one scene's place to the other's over the whole turn, and arrives
   * as the ring does. It walks on the stage, and only the turn decides where
   * it is. Setting off, it turns from the way it faces in its scene to the
   * way it walks, and arriving it turns back; it turns round to face the
   * camera when the drag reverses. Its steps follow the ground it covers,
   * and it comes to a stand when the ring stops.
   */
  const WALK = 45; // degrees of the turn a walk off or on takes
  const walkers = [];
  // spot(): where its middle stands in its scene, on the stage, with the k
  // its scene gives (see onFace and onWall).
  function walker(c, model, { yaw, pitch, height, spot, talk = false }) {
    const wrapper = fit(model, { yaw, pitch });
    stage.add(wrapper);
    const { turn, shift } = wrapper.userData;
    const step = stepper(model);
    // gait: how it is walking, shared with a coach it hands over to.
    const self = { c, wrapper, model, yaw, height, rest: null, next: null, prev: null, moving: false, gait: { face: yaw, amount: 0, walked: 0, from: null } };
    let way = -1, last = null, time = 0, movedAt = -Infinity, reach = 0;
    const between = new THREE.Vector3();
    later(() => {
      const at = spot();
      place(wrapper, at, height, 'y');
      const scale = wrapper.userData.scale;
      // Its feet: the upright axis it turns about, on the stage and as the
      // stage x it shows at; and its size there.
      const feet = wrapper.position.clone().addScaledVector(shift.position, scale);
      self.rest = { feet, x: view.width / 2 + feet.x / at.k, k: at.k, scale };
      // How far past an edge its feet must go for its tail to be off too.
      reach = 0.62 * height * view.s * view.squeeze;
      self.gait.from = null;
    });
    self.update = (now) => {
      const dt = Math.min(100, now - (time || now));
      time = now;
      const at = carousel.offset(c.i);
      if (last !== null && Math.abs(at - last) > 1e-6) {
        way = Math.sign(at - last); // the way the card, and so the coach, goes
        movedAt = now;
      }
      last = at;
      self.moving = false;
      if (!self.rest) return;
      const g = self.gait;
      const partner = at < 0 ? self.next : at > 0 ? self.prev : null;
      let along, goal, x, k, feet, scale, ground, still;
      if (partner && partner.rest) {
        // Handed over: from the earlier scene's place (p = 0) to the later's
        // (1) over the whole turn, shown by the coach of the nearer scene.
        if (Math.abs(at) >= carousel.step / 2) {
          show(wrapper, 0, now);
          return;
        }
        const [from, to] = at < 0 ? [self, partner] : [partner, self];
        const p = at < 0 ? -at / carousel.step : 1 - at / carousel.step;
        const q = clamp((p - 0.1) / 0.8);
        // It grows toward the later place, so it walks that way partly
        // toward the camera, and its size and place follow its steps.
        const dx = to.rest.x - from.rest.x;
        const dz = 2 * (to.height - from.height) * view.s * view.squeeze;
        const forth = way < 0 ? 1 : -1; // p grows as the ring goes on
        const turning = smooth(clamp(p / 0.1)) * smooth(clamp((1 - p) / 0.1));
        const base = p < 0.5 ? from.yaw : to.yaw;
        goal = base + turning * wrap(Math.atan2(forth * dx, forth * dz) - base);
        along = [lerp(from.rest.x, to.rest.x, q), q * dz];
        k = lerp(from.rest.k, to.rest.k, q);
        x = (along[0] - view.width / 2) * k;
        feet = between.copy(from.rest.feet).lerp(to.rest.feet, q);
        scale = lerp(from.rest.scale, to.rest.scale, q);
        ground = p > 0.005 && p < 0.995;
        still = 1 - turning;
      } else {
        const u = clamp(Math.abs(at) / WALK);
        if (u >= 1) {
          show(wrapper, 0, now);
          return;
        }
        // From its scene's way to the way it walks over the first fifth.
        goal = self.yaw + smooth(clamp(u / 0.2)) * wrap((way * Math.PI) / 2 - self.yaw);
        along = [lerp(self.rest.x, at < 0 ? -reach : view.width + reach, clamp((u - 0.1) / 0.9)), 0];
        k = self.rest.k;
        x = (along[0] - view.width / 2) * k;
        feet = self.rest.feet;
        scale = self.rest.scale;
        ground = u > 0.01;
        still = 1 - smooth(clamp(u / 0.2));
      }
      let d = wrap(goal - g.face);
      if (Math.abs(d) > 0.85 * Math.PI) {
        // Turning right round: by way of the camera.
        const through = -Math.sign(wrap(g.face)) || 1;
        if (Math.sign(d) !== through) d += through * 2 * Math.PI;
      }
      const turned = reduced.matches ? d : d * (1 - Math.exp(-dt / 70));
      g.face += turned;
      // Body units covered, and a little stepping on the spot as it turns.
      const covered = g.from ? Math.hypot(along[0] - g.from[0], along[1] - g.from[1]) : 0;
      g.walked += (covered * k) / scale + Math.abs(turned) * 0.3;
      g.from = along;
      const stepping = !reduced.matches && ground && now - movedAt < 120;
      g.amount += ((stepping ? 1 : 0) - g.amount) * (1 - Math.exp(-dt / 110));
      if (!stepping && g.amount < 0.003) g.amount = 0;
      const { bob, rock } = step(g.walked, g.amount);
      wrapper.userData.scale = scale;
      show(wrapper, 1, now);
      const off = shift.position;
      wrapper.position.set(x - off.x * scale, feet.y - off.y * scale + bob * scale, feet.z - off.z * scale);
      // A coach that talks sways while it stands.
      const w = talk && !reduced.matches ? (now / 900) : 0;
      turn.rotation.y = g.face + Math.sin(w * 0.8) * 0.08 * still;
      turn.rotation.z = rock + Math.sin(w) * 0.025 * still;
      self.moving = g.amount > 0 || Math.abs(d - turned) > 1e-3;
    };
    // The model's world matrix as it stands at its place in its scene, facing
    // the way its scene needs it, into out.
    self.restMatrix = (out) => {
      const { feet, scale } = self.rest;
      out.makeScale(scale, scale, scale).setPosition(feet.clone().addScaledVector(shift.position, -scale));
      out.multiply(new THREE.Matrix4().makeTranslation(shift.position));
      out.multiply(new THREE.Matrix4().makeRotationFromEuler(new THREE.Euler(turn.rotation.x, yaw, 0)));
      model.updateMatrix();
      return out.multiply(model.matrix).premultiply(stage.matrixWorld);
    };
    walkers.push(self);
    return self;
  }

  // The coach goes straight on from scene a's place to scene b's, the next.
  function join(a, b) {
    a.next = b;
    b.prev = a;
    b.gait = a.gait;
  }

  // --- Opening and ending: a dumbbell, a barbell and a water bottle. ---
  // Each white screen places one of each at random around its text (and the
  // button) and keeps them floating there.
  const PROPS = { dumbbell: 100, barbell: 158, bottle: 52 };
  // Spots in design px across and stage fractions down; keepOut is a list of
  // boxes in stage fractions, and fallback the spots to use if none fits.
  function scatter(keepOut, fallback) {
    const kinds = Object.keys(PROPS);
    const hits = (a, b, pad) => a.x0 < b.x1 + pad && a.x1 > b.x0 - pad && a.y0 < b.y1 + pad && a.y1 > b.y0 - pad;
    for (let attempt = 0; attempt < 40; attempt++) {
      const placed = [];
      for (const kind of kinds) {
        const w = PROPS[kind];
        const h = (kind === 'bottle' ? w * 2.4 : w * 0.55) / DESIGN_HEIGHT;
        for (let tries = 0; tries < 200; tries++) {
          // Mostly on the screen: at most a fifth of a prop runs off an edge.
          const x = lerp(w * 0.4, DESIGN_WIDTH - w * 0.4, Math.random());
          const y = lerp(0.05 + h * 0.4, 0.95 - h * 0.4, Math.random());
          const rect = { x0: (x - w / 2) / DESIGN_WIDTH, x1: (x + w / 2) / DESIGN_WIDTH, y0: y - h / 2, y1: y + h / 2 };
          if (keepOut.some((k) => hits(rect, k, 0.02)) || placed.some((q) => hits(rect, q.rect, 0.04))) continue;
          placed.push({ kind, x, y, rect });
          break;
        }
      }
      // Above and below the text both get something, and so do both sides.
      const rows = new Set(placed.map((q) => q.y < 0.5));
      const columns = new Set(placed.map((q) => q.x < DESIGN_WIDTH / 2));
      if (placed.length === kinds.length && rows.size === 2 && columns.size === 2) return placed;
    }
    return fallback;
  }
  function props(c, keepOut, fallback) {
    c.idle = true;
    for (const spot of scatter(keepOut, fallback)) {
      job(load(spot.kind), (model) => {
        const wrapper = fit(model.clone(), {
          yaw: (Math.random() - 0.5) * 1.6, pitch: (Math.random() - 0.5) * 0.8, roll: (Math.random() - 0.5) * 1.2,
        });
        c.wall.add(wrapper);
        const home = new THREE.Vector3();
        let bob = 0;
        later(() => {
          const at = onWall(spot.x * view.s, spot.y * view.height);
          place(wrapper, at, PROPS[spot.kind]);
          home.copy(wrapper.position);
          bob = 6 * view.s * view.squeeze * at.k;
        });
        const phase = Math.random() * Math.PI * 2, period = 3200 + Math.random() * 1400;
        c.beats.push((t, at, now) => {
          const w = reduced.matches ? 0 : (now / period) * Math.PI * 2 + phase;
          wrapper.position.set(home.x, home.y + Math.sin(w) * bob, home.z);
          wrapper.rotation.set(Math.sin(w * 0.7) * 0.08, Math.sin(w * 0.5 + 1) * 0.16, Math.sin(w * 0.9 + 2) * 0.1);
          show(wrapper, 1, now);
        });
        arrive(c, wrapper);
        return compile(wrapper);
      });
    }
  }
  // The opening keeps clear of its text and the hint under it (.hint in
  // index.html), and of the coach who says it.
  props(scenes.opening, [{ x0: 0.06, x1: 0.94, y0: 0.4, y1: 0.65 }, { x0: 0.56, x1: 1, y0: 0.65, y1: 0.93 }], [
    { kind: 'dumbbell', x: 300, y: 0.16 }, { kind: 'bottle', x: 70, y: 0.22 }, { kind: 'barbell', x: 105, y: 0.74 },
  ]);
  // The ending keeps clear of its text and of the button and the way it rises.
  props(scenes.ending, [{ x0: 0.08, x1: 0.92, y0: 0.33, y1: 0.55 }, { x0: 0.14, x1: 0.86, y0: 0.62, y1: 0.82 }], [
    { kind: 'dumbbell', x: 300, y: 0.16 }, { kind: 'bottle', x: 70, y: 0.22 }, { kind: 'barbell', x: 230, y: 0.9 },
  ]);

  // The coach, with its squat and its walk (see dino.js).
  const coachIn = load('dino');

  // The coach says the opening line: it stands at the lower right of the text
  // on the white wall, below the hint, turned a little toward the text, and
  // sways as it talks.
  {
    const c = scenes.opening;
    job(coachIn, (model) => {
      const { wrapper } = walker(c, model.clone(), {
        yaw: -0.35,
        pitch: 0.06,
        height: 220,
        talk: true,
        spot: () => {
          const at = onWall(300 * view.s, 0.79 * view.height);
          return { ...at, z: at.z - view.r };
        },
      });
      arrive(c, wrapper);
      return compile(wrapper);
    });
  }

  // --- The times of day, at the top left of each day card's wall band. ---
  for (const [key, name] of [['morning', 'morning'], ['lunch', 'noon'], ['evening', 'evening'], ['night', 'night']]) {
    const c = scenes[key];
    job(load(name), (model) => {
      const wrapper = fit(model);
      c.wall.add(wrapper);
      later(() => place(wrapper, onWall(74 * view.s, 92 * view.s * view.squeeze), 112));
      c.beats.push((t, at, now) => show(wrapper, 1, now));
      arrive(c, wrapper);
      return compile(wrapper);
    });
  }

  // --- The phones of the morning and lunch cards. ---
  // They are HTML under this layer, so a stand-in for each that only fills
  // the depth buffer hides whatever goes behind it.
  for (const c of cards) {
    const phone = c.li.querySelector('.carousel__face.phone');
    if (!phone) continue;
    const blocker = new THREE.Mesh(new THREE.BufferGeometry(), new THREE.MeshBasicMaterial({ colorWrite: false }));
    blocker.renderOrder = -1;
    c.face.add(blocker);
    later(() => {
      blocker.geometry.dispose();
      const radius = parseFloat(getComputedStyle(phone).borderTopLeftRadius) || 0;
      blocker.geometry = new THREE.ShapeGeometry(roundedRect(phone.offsetWidth, phone.offsetHeight, radius), 12);
    });
  }

  // Something kept behind a card's phone that comes out over its top edge to
  // a spot (to), and goes back the same way: p is 0 behind, 1 there. It rises
  // clear of the edge before it comes forward, and comes forward before it
  // settles, so it never passes through the phone.
  function outFromPhone(wrapper, to, scale, p, now) {
    const from = onFace(lerp(195, to.at[0], 0.25), FACE.top + 60, -60);
    const half = (wrapper.userData.size.y * scale * 0.95) / 2;
    const peak = Math.max(to.y, onFace(195, FACE.top).y + half + 2 * view.s);
    const y = p < 0.6 ? lerp(from.y, peak, easeOut(clamp(p / 0.45))) : lerp(peak, to.y, smooth((p - 0.6) / 0.4));
    wrapper.position.set(lerp(from.x, to.x, smooth(clamp(p / 0.8))), y, lerp(from.z, to.z, span(p, 0.3, 0.6)));
    wrapper.rotation.y = (1 - smooth(p)) * 1.2;
    wrapper.userData.scale = scale;
    show(wrapper, p > 0 ? lerp(0.7, 1, span(p, 0, 0.6)) : 0, now);
  }
  const bump = (t, a, b) => Math.sin(Math.PI * clamp((t - a) / (b - a)));

  // Something that bursts out of a point on a phone's screen (from) to a spot
  // in front of the phone (to), over the top, and goes back in the same way:
  // p is 0 inside the screen, 1 there.
  function outOfScreen(wrapper, from, to, scale, p, now) {
    const u = easeOut(p);
    const peak = Math.max(from.y, to.y) + 70 * view.s * view.squeeze;
    const bx = lerp(from.x, to.x, 0.35);
    wrapper.position.set(
      (1 - u) ** 2 * from.x + 2 * u * (1 - u) * bx + u * u * to.x,
      (1 - u) ** 2 * from.y + 2 * u * (1 - u) * peak + u * u * to.y,
      lerp(from.z, to.z, u),
    );
    wrapper.rotation.y = (1 - u) * 2.4;
    wrapper.userData.scale = scale;
    show(wrapper, p > 0 ? 0.12 + 0.88 * backOut(p) : 0, now);
  }

  // --- Morning: the weather bursts out of the sun on the phone's screen. ---
  {
    const c = scenes.morning;
    const SUN = [195, 458];
    const WEATHER = [
      { name: 'sunny', at: [198, 326], size: 100, yaw: 0 },
      { name: 'cloudy', at: [88, 350], size: 96, yaw: 0.25 },
      { name: 'snow', at: [314, 352], size: 82, yaw: -0.3 },
      { name: 'rain', at: [42, 392], size: 70, yaw: 0.35 },
    ];
    WEATHER.forEach(({ name, at, size, yaw }, j) => {
      job(load(name), (model) => {
        const wrapper = fit(model, { yaw });
        c.face.add(wrapper);
        let from, to, scale;
        later(() => {
          from = onFace(...SUN, 6, { screen: true });
          to = onFace(...at, 40);
          place(wrapper, to, size);
          scale = wrapper.userData.scale;
        });
        // Out one after another, then back into the sun.
        c.beats.push((t, _, now) => {
          const out = span(t, 0.04 + 0.08 * j, 0.36 + 0.08 * j);
          const back = span(t, 0.7 + 0.05 * j, 0.85 + 0.05 * j);
          outOfScreen(wrapper, from, to, scale, out * (1 - back), now);
        });
        return compile(wrapper);
      });
    });
  }

  // --- Lunch: a photo of the burger, then the nutrient rings fill. ---
  {
    const c = scenes.lunch;
    const RINGS = [
      { name: 'calories', at: [44, 326] },
      { name: 'carbs', at: [104, 292] },
      { name: 'protein', at: [286, 292] },
      { name: 'fat', at: [346, 326] },
    ];
    // The card's CSS variables, each set only when it changes, to the
    // thousandth.
    const shown = {};
    const set = (name, value) => {
      const v = Math.round(value * 1000) / 1000;
      if (shown[name] === v) return;
      shown[name] = v;
      c.li.style.setProperty(name, v);
    };
    // The camera, the shutter and its flash, then a white page while the
    // app looks at the photo, the result coming up with what it recognised,
    // and the button that logs it.
    c.beats.push((t) => {
      set('--click', bump(t, 0.1, 0.15));
      set('--flash', bump(t, 0.12, 0.18));
      set('--shot', span(t, 0.16, 0.21));
      set('--think', span(t, 0.15, 0.17) * (1 - span(t, 0.4, 0.43)));
      set('--analyze', span(t, 0.2, 0.39));
      set('--reveal', span(t, 0.41, 0.46));
      set('--scan', span(t, 0.45, 0.51));
      set('--press', bump(t, 0.49, 0.55));
    });
    job(load('burger'), (model) => {
      // The burger stands behind the phone in the middle of the screen, deep
      // in the ring: the phone hides it while the card rests, and as the ring
      // turns it moves less than the phone and comes out from behind it.
      const wrapper = fit(model, { pitch: 0.22 });
      c.card.add(wrapper);
      later(() => place(wrapper, inside(195, 452, 0.55), 150));
      c.beats.push((t, _, now) => show(wrapper, 1, now));
      arrive(c, wrapper);
      // The phone's pictures of it, from a little above, drawn into a corner
      // of the layer and copied out before the layer is drawn over it in the
      // same frame.
      const shot = new THREE.Scene();
      lightStage(renderer, shot, { shadows: false });
      const dish = model.clone();
      let shotCamera = null, due = false;
      later(() => {
        due = true;
      });
      const pictures = [
        [c.li.querySelector('.photo__shot'), 0.78],
        [c.li.querySelector('.camera__view'), 0.9],
        [c.li.querySelector('.analyzing__shot'), 0.86],
      ];
      before.push(() => {
        if (!due) return;
        due = false;
        for (const [picture, fill] of pictures) {
          if (!picture || !picture.offsetWidth) continue;
          const w = picture.offsetWidth, h = picture.offsetHeight;
          if (!shotCamera) shotCamera = frameOn(shot, dish, w / h, { pitch: 0.3 });
          // Frame the dish for this picture: fill its height or width.
          const box = new THREE.Box3().setFromObject(dish, true);
          const size = box.getSize(new THREE.Vector3());
          const half = Math.tan(rad(14));
          shotCamera.aspect = w / h;
          shotCamera.position.z = Math.max(size.y / 2 / half, size.x / 2 / (half * shotCamera.aspect)) / fill + size.z / 2;
          shotCamera.updateProjectionMatrix();
          const ratio = renderer.getPixelRatio();
          picture.width = Math.round(w * ratio);
          picture.height = Math.round(h * ratio);
          renderer.setViewport(0, 0, w, h);
          renderer.render(shot, shotCamera);
          picture.getContext('2d').drawImage(renderer.domElement, 0, renderer.domElement.height - picture.height, picture.width, picture.height, 0, 0, picture.width, picture.height);
        }
        renderer.setViewport(0, 0, view.width, view.height);
      });
      // The pictures share the burger's materials and are drawn on the canvas,
      // so the burger's shaders serve them too.
      return compile(wrapper);
    });
    RINGS.forEach(({ name, at }, j) => {
      job(load(name), (model) => {
        const gauge = model.getObjectByName(name);
        const value = BURGER[name] / GOALS[name];
        let filled = 0;
        fillRing(gauge, 0);
        const wrapper = fit(model, { yaw: at[0] < 195 ? 0.3 : -0.3 });
        c.face.add(wrapper);
        let to, scale;
        later(() => {
          to = { ...onFace(...at, 30), at };
          place(wrapper, to, 70);
          scale = wrapper.userData.scale;
        });
        // Out from behind the phone once the burger is logged, filling to its
        // share of the day's goal, and back into the phone with it.
        c.beats.push((t, _, now) => {
          const out = span(t, 0.54 + 0.04 * j, 0.68 + 0.04 * j);
          const back = span(t, 0.84 + 0.035 * j, 0.93 + 0.02 * j);
          outFromPhone(wrapper, to, scale, out * (1 - back), now);
          const fill = Math.round(value * span(t, 0.6 + 0.04 * j, 0.78 + 0.03 * j) * 1000) / 1000;
          if (fill !== filled) {
            fillRing(gauge, fill);
            filled = fill;
          }
        });
        return compile(wrapper);
      });
    });
  }

  // A storyboard point in front of the front face, as the stage has it while
  // the card rests: where a walking coach stands in its scene.
  const beforeFace = (x, y, lift) => {
    const at = onFace(x, y, lift);
    return { ...at, y: at.y + view.faceY };
  };

  // --- Evening: the coach squats in front of a phone turned to it. ---
  const coach = Promise.all([coachIn, fontsIn]).then(([model]) => model);
  // The evening's coach, which walks on into the night.
  let eveningCoach = null;
  {
    const c = scenes.evening;
    job(coach, (model) => {
      const dino = model.clone();
      const pose = poser(dino);
      // Its back to us, turned to the right toward the phone on the wall at
      // the back right.
      eveningCoach = walker(c, dino, { yaw: (3 * Math.PI) / 4, pitch: 0.06, height: 214, spot: () => beforeFace(150, 668, 60) });
      const { wrapper } = eveningCoach;

      // The phone's camera sees the coach from the front: a twin in a scene
      // of its own, drawn into a texture shown on the phone's screen. It
      // looks up a little from below, so the chest shows under the jaw even
      // when a slouch drops the head in front of it. The camera stays where
      // it sees the coach at its evening place; the twin moves as the coach
      // does about that place (see film()).
      const live = new THREE.Scene();
      lightStage(renderer, live, { shadows: false });
      live.background = new THREE.Color('#14181c');
      const twin = dino.clone();
      // Materials of its own: drawn into a texture, a material needs other
      // shaders than on the canvas, and one shared with the coach would
      // change over to them and back every frame.
      twin.traverse((o) => {
        if (o.isMesh) o.material = o.material.clone();
      });
      const twinPose = poser(twin);
      const liveCamera = frameOn(live, twin, LIVE.w / LIVE.h, { pitch: -0.3, fill: 0.96 });
      // As many pixels as the picture covers on the screen (sized below).
      const target = new THREE.WebGLRenderTarget(1, 1, { type: THREE.HalfFloatType, samples: 4 });
      // The phone shows the coach as in a mirror, the way a front camera
      // shows whoever faces it, so the coach crosses the picture the same
      // way it crosses the stage.
      target.texture.repeat.x = -1;
      target.texture.offset.x = 1;
      // The fault shows at the chest, dropped at the bottom of a slouched
      // squat. The mirrored picture keeps the chest left of the middle, so
      // the arrow that swings out to its right and up stays in the picture.
      twinPose(0, 1);
      const chest = chestOf(twin, liveCamera);
      twinPose(0, 0);
      const across = Math.tan(rad(14)) * liveCamera.aspect * (liveCamera.position.z - chest.z);
      liveCamera.position.x = chest.x - 0.22 * across;
      liveCamera.updateMatrixWorld();
      chest.project(liveCamera);
      const twinRest = twin.matrix.clone();
      twin.matrixAutoUpdate = false;

      // Two repetitions: a good one, counted as the coach comes back up, then
      // a slouched one that the phone corrects and does not count. The cue
      // stays until the coach has stood up. The screen for each count is
      // drawn ahead.
      const REPS = [[0.08, 0.4, false], [0.48, 0.86, true]];
      const good = REPS.filter(([, , bad]) => !bad).length;
      const huds = Array.from({ length: good + 1 }, (_, done) => screenTexture(coachScreen, done));
      const phone = miniPhone(huds[0]);
      phone.add(new THREE.MeshBasicMaterial({ map: target.texture }), LIVE.x, LIVE.y, LIVE.w, LIVE.h, 10);
      const chip = canvasTexture(LIVE.w, LIVE.h, coachChip);
      chip.redraw();
      phone.add(flat(chip.texture, { transparent: true, depthWrite: false }), LIVE.x, LIVE.y, LIVE.w, LIVE.h, 10);
      const arrow = canvasTexture(LIVE.w, LIVE.h, correction);
      arrow.redraw([((1 - chest.x) / 2) * LIVE.w, ((1 - chest.y) / 2) * LIVE.h]);
      const arrowMaterial = flat(arrow.texture, { transparent: true, depthWrite: false, opacity: 0 });
      phone.add(arrowMaterial, LIVE.x, LIVE.y, LIVE.w, LIVE.h, 10);
      const warn = canvasTexture(WARN.w, WARN.h, warnPanel);
      warn.redraw();
      const warnMaterial = flat(warn.texture, { transparent: true, depthWrite: false, opacity: 0 });
      phone.add(warnMaterial, WARN.x, WARN.y, WARN.w, WARN.h, 7);
      // It stands on the wall, so it sweeps the other way from the coach as
      // the ring turns, turned well round to the coach and tipped down to it.
      const standing = stand(phone, 0.1, rad(-52));
      c.wall.add(standing);
      later(() => place(standing, onWall(...onStage(296, 503)), PHONE.width));

      const film = () => {
        renderer.setRenderTarget(target);
        renderer.render(live, liveCamera);
        renderer.setRenderTarget(null);
      };
      // The camera films whichever coach is on the stage, the evening's or
      // the night's walking on from it, as it stands to the evening place:
      // the twin takes that coach's place, turn, steps and pose about its
      // own, so the coach walks into the picture, works out and walks off,
      // and the picture is empty while no coach is there. It is drawn again
      // only when that changes.
      const restInverse = new THREE.Matrix4(), rel = new THREE.Matrix4(), filmed = new THREE.Matrix4();
      const at = new THREE.Vector3(), turned = new THREE.Quaternion(), size = new THREE.Vector3(), unit = new THREE.Vector3(1, 1, 1);
      const posed = (body) => {
        const meshes = [];
        body.traverse((o) => o.morphTargetInfluences && meshes.push(o));
        return meshes;
      };
      const twinMeshes = posed(twin), coachMeshes = new Map();
      let filmedCoach, filmedPose = [], stale = true;
      later(() => {
        eveningCoach.restMatrix(restInverse).invert();
        stale = true;
      });
      function follow() {
        const seen = [eveningCoach, eveningCoach.next].find((w) => w && w.wrapper.visible) || null;
        if (seen) {
          seen.model.updateWorldMatrix(true, false);
          // Its place and turn about the evening place; its size is the
          // same dino's, however big the stage draws it there.
          rel.multiplyMatrices(restInverse, seen.model.matrixWorld).decompose(at, turned, size);
          rel.compose(at, turned, unit);
          if (!coachMeshes.has(seen)) coachMeshes.set(seen, posed(seen.model));
        }
        const meshes = seen ? coachMeshes.get(seen) : [];
        const influences = meshes.length ? meshes[0].morphTargetInfluences : [];
        const same = !stale && seen === filmedCoach && (!seen || (rel.equals(filmed) && influences.every((v, k) => v === filmedPose[k])));
        if (same) return false;
        stale = false;
        filmedCoach = seen;
        filmed.copy(rel);
        filmedPose = [...influences];
        twin.visible = Boolean(seen);
        if (seen) {
          twin.matrix.multiplyMatrices(twinRest, rel);
          twin.matrixWorldNeedsUpdate = true;
          twinMeshes.forEach((m, k) => {
            const from = meshes[k].morphTargetInfluences, into = m.morphTargetInfluences;
            for (let n = 0; n < into.length; n++) into[n] = from[n];
          });
        }
        return true;
      }
      // A design px of the phone is view.s * view.squeeze px of the stage.
      later(() => {
        const px = view.s * view.squeeze * renderer.getPixelRatio();
        target.setSize(Math.ceil(LIVE.w * px), Math.ceil(LIVE.h * px));
        stale = true;
      });
      let counted = 0;
      c.beats.push((t, _, now) => {
        let squat = 0, slouch = 0, done = 0;
        for (const [a, b, bad] of REPS) {
          const u = clamp((t - a) / (b - a));
          if (u >= 0.85 && !bad) done += 1;
          if (u <= 0 || u >= 1) continue;
          const depth = (1 - Math.cos(u * Math.PI * 2)) / 2;
          if (bad) slouch = depth;
          else squat = depth;
        }
        const [a, b] = REPS[1];
        const fault = span(t, a + 0.3 * (b - a), a + 0.45 * (b - a)) * (1 - span(t, 0.9, 0.98));
        pose(squat, slouch);
        if (done !== counted) {
          phone.glass.map = huds[done];
          counted = done;
        }
        arrowMaterial.opacity = fault;
        warnMaterial.opacity = fault;
        show(standing, 1, now);
      });
      before.push(() => {
        if (c.card.visible && follow()) film();
      });
      // The coach in the picture, and the screens of every count, on the GPU
      // before the evening comes round.
      warmups.push(() => {
        twin.visible = true;
        twin.matrix.copy(twinRest);
        twin.matrixWorldNeedsUpdate = true;
        film();
        stale = true;
        for (const hud of huds) renderer.initTexture(hud);
      });
      arrive(c, wrapper, standing);
      return Promise.all([compile(wrapper), compile(standing), compileInto(target, live, liveCamera)]);
    });
  }

  // --- Night: the load shows on the coach and tomorrow's picks come out. ---
  {
    const c = scenes.night;
    job(coach, async (model) => {
      const dino = model.clone();
      const sweep = loadSweep(dino);
      // It walks in from the evening's place, and off to the ending, in its
      // own green: the load shows only while the ring rests here.
      const night = walker(c, dino, { yaw: 0.35, pitch: 0.05, height: 262, spot: () => beforeFace(118, 664, 60) });
      if (eveningCoach) join(eveningCoach, night);
      const { wrapper } = night;

      // The app's body map on the phone: the loaded coach from the front,
      // drawn once its shaders have compiled.
      const map = new THREE.Scene();
      lightStage(renderer, map, { shadows: false });
      map.background = new THREE.Color('#2b2f31');
      const mapCamera = frameOn(map, dino.clone(), MAP.w / MAP.h, { yaw: 0.2, fill: 0.86 });
      const target = new THREE.WebGLRenderTarget(MAP.w * 3, MAP.h * 3, { type: THREE.HalfFloatType, samples: 4 });
      await compileInto(target, map, mapCamera);
      sweep(1);
      renderer.setRenderTarget(target);
      renderer.render(map, mapCamera);
      renderer.setRenderTarget(null);
      sweep(0);

      // Its muscle state tab and its recommendations, drawn ahead.
      const screens = [false, true].map((picks) => screenTexture(fatigueScreen, picks));
      const phone = miniPhone(screens[0]);
      phone.add(new THREE.MeshBasicMaterial({ map: target.texture }), MAP.x, MAP.y, MAP.w, MAP.h, 10);
      const chip = canvasTexture(MAP.w, MAP.h, mapChip);
      chip.redraw();
      phone.add(flat(chip.texture, { transparent: true, depthWrite: false }), MAP.x, MAP.y, MAP.w, MAP.h, 10);
      const standing = stand(phone, 0, rad(-22));
      c.wall.add(standing);
      let screenAt = null;
      later(() => {
        place(standing, onWall(...onStage(308, 495)), PHONE.width);
        // The middle of its screen, from the wall into the face's coordinates.
        const middle = onWall(...onStage(308, 470));
        screenAt = { x: middle.x, y: middle.y - view.faceY, z: middle.z - view.r };
      });

      // Tomorrow's picks fly out of the phone on the wall, fan down beside the
      // coach and go back in; then the coach's own colours come back.
      const picks = NIGHT_PICKS.map((pick, j) => {
        const card = fit(routineCard(pick), { yaw: -0.4, pitch: 0.02, roll: (j - 1) * -0.05 });
        c.face.add(card);
        const spot = {};
        later(() => {
          spot.from = screenAt;
          spot.to = onFace(283 + 11 * j, 600 + 76 * j, 50 - 12 * j);
          place(card, spot.to, 145);
          spot.scale = card.userData.scale;
        });
        return { card, spot };
      });

      let listed = false, swept = 0;
      c.beats.push((t, _, now) => {
        const level = Math.round(span(t, 0.02, 0.32) * (1 - span(t, 0.86, 0.98)) * 200) / 200;
        if (level !== swept) {
          sweep(level);
          swept = level;
        }
        const listing = t >= 0.3 && t < 0.88;
        if (listing !== listed) {
          listed = listing;
          phone.glass.map = screens[listed ? 1 : 0];
        }
        picks.forEach(({ card, spot }, j) => {
          const a = span(t, 0.3 + 0.07 * j, 0.52 + 0.07 * j) * (1 - span(t, 0.7 + 0.04 * j, 0.84 + 0.04 * j));
          const u = easeOut(a);
          card.position.set(lerp(spot.from.x, spot.to.x, u), lerp(spot.from.y, spot.to.y, u), lerp(spot.from.z, spot.to.z, u));
          card.userData.scale = spot.scale;
          show(card, a > 0 ? 0.15 + 0.85 * backOut(a) : 0, now);
        });
        show(standing, 1, now);
      });
      warmups.push(() => {
        for (const screen of screens) renderer.initTexture(screen);
      });
      arrive(c, wrapper, standing);
      return Promise.all([wrapper, standing, ...picks.map(({ card }) => card)].map(compile));
    });
  }

  // --- Ending: the download button rises as the white screen comes round. ---
  // The button is drawn here; the link under it in the page takes the tap.
  let pressing = false, press = 0;
  {
    const c = scenes.ending;
    c.idle = true;
    const link = c.li.querySelector('.story__download');
    if (link) {
      link.addEventListener('pointerdown', () => {
        pressing = true;
        wake();
      });
      for (const type of ['pointerup', 'pointerleave', 'pointercancel']) {
        link.addEventListener(type, () => {
          pressing = false;
          wake();
        });
      }
    }
    job(Promise.all([load('button'), fontsIn]), ([button]) => {
      labelButton(button);
      const wrapper = fit(button, { pitch: 0.12 });
      c.face.add(wrapper);
      let home, scale;
      later(() => {
        home = onFace(195, 590, 30);
        place(wrapper, home, 220);
        scale = wrapper.userData.scale;
      });
      c.beats.push((t, at, now) => {
        // It peeks up from below as the screen comes round and rises the
        // rest of the way with the drag.
        const rise = 0.25 * clamp(1 - Math.abs(at) / 16) + 0.75 * backOut(span(t, 0, 0.8));
        const float = reduced.matches ? 0 : Math.sin(now / 700) * 3 * view.s;
        wrapper.position.set(home.x, home.y - (1 - rise) * 70 * view.s * view.squeeze + float, home.z);
        wrapper.userData.scale = scale;
        show(wrapper, 0.55 + 0.45 * rise, now);
        press += ((pressing ? 1 : 0) - press) * 0.35;
        if (Math.abs(press - (pressing ? 1 : 0)) < 0.01) press = pressing ? 1 : 0;
        pressButton(button, press);
      });
      arrive(c, wrapper);
      return compile(wrapper);
    });
  }

  // --- Drawing. ---
  function relayout() {
    const face = carousel.faces.find(Boolean);
    Object.assign(view, {
      width: carousel.width,
      height: carousel.height,
      p: carousel.perspective,
      r: carousel.radius,
      s: face.offsetWidth / FACE.width,
      squeeze: Math.min(1, (carousel.height / carousel.width) / (DESIGN_HEIGHT / DESIGN_WIDTH)),
      faceLeft: face.offsetLeft,
      faceTop: face.offsetTop,
      faceY: carousel.height / 2 - (face.offsetTop + face.offsetHeight / 2),
    });
    renderer.setSize(view.width, view.height, false);
    camera.aspect = view.width / view.height;
    camera.position.set(0, 0, view.p);
    camera.far = view.p + 2 * view.r + 4000;
    camera.updateProjectionMatrix();
    ring.position.z = -view.r;
    for (const c of cards) c.face.position.set(0, view.faceY, view.r);
    for (const fn of layouts) fn();
  }

  function draw() {
    if (!carousel.width) return;
    if (view.width !== carousel.width || view.height !== carousel.height || view.r !== carousel.radius) relayout();
    const now = performance.now();
    ring.rotation.y = rad(carousel.angle);
    for (const c of cards) {
      const at = carousel.offset(c.i);
      c.card.visible = Math.abs(at) < 90;
      if (!c.card.visible) continue;
      c.face.rotation.y = rad(-(1 - carousel.options.turn) * at);
      const t = carousel.beat(c.i);
      for (const fn of c.beats) fn(t, at, now);
    }
    for (const w of walkers) w.update(now);
    for (const fn of before) fn();
    renderer.render(scene, camera);
  }

  // The ring's own frames draw the layer in step with the cards. Between
  // them, floating props, models growing in, the pressed button and a coach
  // coming to a stand keep drawing on their own, unless the ring has drawn
  // since they last did: the ring draws in its own animation frames, so this
  // keeps to one draw a frame however long frames take. While the page keeps
  // the stage inert under its loading screen, nothing draws on its own:
  // warmUp() draws it all once at the end.
  let idle = 0, moved = false;
  const busy = () => !root.inert && (appearing.size > 0 || press !== (pressing ? 1 : 0) || walkers.some((w) => w.moving)
    || (!reduced.matches && cards.some((c) => c.idle && c.card.visible)));
  function wake() {
    if (!idle) idle = requestAnimationFrame(loop);
  }
  function loop() {
    idle = 0;
    if (!moved) draw();
    moved = false;
    if (busy()) wake();
  }
  root.addEventListener('carousel:move', () => {
    moved = true;
    draw();
    if (busy()) wake();
  });
  new MutationObserver(wake).observe(root, { attributes: true, attributeFilter: ['inert'] });
  draw();
  wake();

  // The next frame, or a moment if the page is hidden and frames have stopped.
  const frame = () => new Promise((resolve) => {
    requestAnimationFrame(() => resolve());
    setTimeout(resolve, 100);
  });

  /*
   * Readies everything the story draws while the loading screen still covers
   * it, so that no frame on the way stops to compile a shader or to put a
   * model or a picture on the GPU: the shaders all compile (most have already,
   * as their models came in), then each card and the walking coaches are
   * drawn once with every model in them shown and none left out for being
   * off screen, which uploads their geometry and textures, a frame apart so
   * the loading screen keeps moving. Everything is then put back as it was.
   */
  async function warmUp() {
    await renderer.compileAsync(scene, camera);
    for (const part of [...cards.map((c) => c.card), stage]) {
      await frame();
      const saved = [];
      scene.traverse((o) => saved.push([o, o.visible, o.frustumCulled]));
      for (const c of cards) c.card.visible = c.card === part;
      stage.visible = stage === part;
      part.traverse((o) => {
        o.visible = true;
        o.frustumCulled = false;
      });
      renderer.render(scene, camera);
      for (const [o, visible, culled] of saved) {
        o.visible = visible;
        o.frustumCulled = culled;
      }
    }
    for (const fn of warmups) fn();
  }

  await Promise.allSettled([...jobs, fontsIn]);
  await warmUp().catch(report);
  // The opening as it stands, under the loading screen as it fades.
  draw();
  root.classList.add('is-ready');
  root.dispatchEvent(new CustomEvent('story:ready'));
}
