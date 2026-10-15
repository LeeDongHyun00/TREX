/*
 * Nutrient rings: calories, carbs, protein and fat as round 3D progress bars.
 * Each is a soft track, a glossy arc that starts at twelve o'clock and fills
 * clockwise, and the nutrient's icon floating in the middle. fillRing(ring, v)
 * sets the fill for v in [0, 1], cheap enough to call every frame, on a ring
 * built here or the same ring loaded from its .glb.
 */
import * as THREE from 'three';
import { blend, capsule, ellipsoid, polygonize, rgb, roundCone, sphere, tint } from './sdf.js';
import { COLORS, deepen, mesh, mix, painted, shade, teardrop, toy } from './look.js';

const RADIUS = 0.78; // centre line of the ring
const TUBE = 0.14; // radius of the arc's cross-section
const RADIAL = 28;
const SEGMENTS = 240;

const clamp01 = (v) => Math.min(1, Math.max(0, v));
const smooth = (t) => t * t * (3 - 2 * t);

// Where the arc's centre line is after a fraction t of a full turn.
const angleAt = (t) => Math.PI / 2 - t * Math.PI * 2;

// Lays the arc's i-th ring of vertices on the circle at fraction t.
function writeRing(geometry, i, t, color) {
  const { position, normal, color: tone } = geometry.attributes;
  const a = angleAt(t);
  const cx = Math.cos(a), cy = Math.sin(a);
  for (let j = 0; j <= RADIAL; j++) {
    const b = (j / RADIAL) * Math.PI * 2;
    const nx = cx * Math.cos(b), ny = cy * Math.cos(b), nz = Math.sin(b);
    const k = i * (RADIAL + 1) + j;
    position.setXYZ(k, cx * RADIUS + nx * TUBE, cy * RADIUS + ny * TUBE, nz * TUBE);
    normal.setXYZ(k, nx, ny, nz);
    tone.setXYZ(k, color.r, color.g, color.b);
  }
}

// Triangles for the first n segments, in order from twelve o'clock.
function arcIndex(n) {
  const index = new Uint16Array(n * RADIAL * 6);
  let q = 0;
  for (let i = 0; i < n; i++) {
    for (let j = 0; j < RADIAL; j++) {
      const a = i * (RADIAL + 1) + j, b = a + RADIAL + 1;
      index[q++] = a; index[q++] = a + 1; index[q++] = b;
      index[q++] = b; index[q++] = a + 1; index[q++] = b + 1;
    }
  }
  return new THREE.BufferAttribute(index, 1);
}

// Arcs laid out here, as opposed to loaded.
const whole = new WeakSet();

/*
 * A tube once around the ring, built segment by segment from twelve o'clock
 * so a draw range shows any leading part of it. Colour deepens along the way.
 */
function arcGeometry(from, to) {
  const count = (SEGMENTS + 1) * (RADIAL + 1);
  const geometry = new THREE.BufferGeometry();
  geometry.setAttribute('position', new THREE.BufferAttribute(new Float32Array(count * 3), 3));
  geometry.setAttribute('normal', new THREE.BufferAttribute(new Float32Array(count * 3), 3));
  geometry.setAttribute('color', new THREE.BufferAttribute(new Float32Array(count * 3), 3));
  const c = new THREE.Color();
  for (let i = 0; i <= SEGMENTS; i++) {
    writeRing(geometry, i, i / SEGMENTS, c.copy(from).lerp(to, i / SEGMENTS));
  }
  geometry.setIndex(arcIndex(SEGMENTS));
  geometry.computeBoundingSphere();
  whole.add(geometry);
  return geometry;
}

const arcs = new WeakMap();
const tone = new THREE.Color();

/*
 * Fills a ring to v in [0, 1]: shows the arc's leading segments, pulls its
 * last ring of vertices to exactly v so it ends flush under the end cap, and
 * moves and recolours the cap. A ring loaded from a packed .glb keeps only
 * the arc's vertices its saved value shows, in another order, so the first
 * call lays the whole arc out afresh, in the colours the arc carries in its
 * userData.
 */
export function fillRing(ring, v) {
  let arc, end;
  ring.traverse((o) => {
    if (o.isMesh && o.name.endsWith('-arc')) arc = o;
    if (o.isMesh && o.name.endsWith('-end')) end = o;
  });
  let state = arcs.get(arc);
  if (!state) {
    const [r0, g0, b0, r1, g1, b1] = arc.userData.gradient;
    state = { from: new THREE.Color(r0, g0, b0), to: new THREE.Color(r1, g1, b1), moved: -1 };
    if (!whole.has(arc.geometry)) arc.geometry = arcGeometry(state.from, state.to);
    arcs.set(arc, state);
  }
  const geometry = arc.geometry;
  v = clamp01(v);
  if (state.moved >= 0) {
    const t = state.moved / SEGMENTS;
    writeRing(geometry, state.moved, t, tone.copy(state.from).lerp(state.to, t));
  }
  const last = Math.ceil(v * SEGMENTS - 1e-6);
  tone.copy(state.from).lerp(state.to, v);
  if (last > 0) writeRing(geometry, last, v, tone);
  state.moved = last > 0 ? last : -1;
  geometry.attributes.position.needsUpdate = true;
  geometry.attributes.normal.needsUpdate = true;
  geometry.attributes.color.needsUpdate = true;
  geometry.setDrawRange(0, last * RADIAL * 6);
  const a = angleAt(v);
  end.position.set(Math.cos(a) * RADIUS, Math.sin(a) * RADIUS, 0);
  end.material.color.copy(tone);
  ring.userData.value = v;
}

function gradient(field, low, high, from, to, axis = (x, y) => y) {
  const a = rgb(low), b = rgb(high);
  return tint(field, (x, y, z, o) => {
    const t = smooth(clamp01((axis(x, y, z) - from) / (to - from)));
    o.r = a[0] + (b[0] - a[0]) * t;
    o.g = a[1] + (b[1] - a[1]) * t;
    o.b = a[2] + (b[2] - a[2]) * t;
  });
}

const iconMaterial = () => painted({ roughness: 0.3, clearcoat: 1, clearcoatRoughness: 0.1 });

// A flame with two side tongues and a brighter core bulging from its front.
function flame() {
  const c = '#f0401a';
  let body = blend(0.08,
    sphere([0, -0.13, 0], 0.2, c),
    roundCone([0, -0.13, 0], [0.02, 0.06, 0], 0.2, 0.13, c),
    roundCone([0.02, 0.06, 0], [-0.07, 0.31, 0], 0.13, 0.02, c),
    roundCone([0.09, -0.05, 0], [0.2, 0.13, 0], 0.1, 0.02, c),
    roundCone([-0.09, -0.07, 0], [-0.18, 0.05, 0], 0.09, 0.02, c),
  );
  body = gradient(body, '#ea3417', '#ff9f2e', -0.32, 0.3);
  const core = blend(0.05,
    sphere([0, -0.16, 0.13], 0.11, '#ffd23f'),
    roundCone([0, -0.16, 0.13], [0.01, 0.05, 0.11], 0.11, 0.02, '#ffd23f'),
  );
  const geometry = polygonize(blend(0.035, body, core), { min: [-0.3, -0.38, -0.28], max: [0.3, 0.38, 0.3], step: 0.009, occlusion: 0.2 });
  const icon = mesh(geometry, iconMaterial(), 'flame');
  icon.scale.z = 0.75;
  return icon;
}

// A roast drumstick: a plump, browned end narrowing into a clean bone.
function drumstick() {
  const meat = '#c55a26', bone = '#fbf3df';
  let flesh = blend(0.1,
    ellipsoid([0.08, -0.09, 0], [0.25, 0.2, 0.18], meat, [0, 0, 0.75]),
    roundCone([0.08, -0.09, 0], [-0.08, 0.08, 0], 0.17, 0.075, meat),
  );
  flesh = gradient(flesh, '#a8431d', '#e8904a', -0.25, 0.2, (x, y) => (y - x) * 0.7 + 0.08);
  const knuckle = blend(0.025,
    capsule([-0.07, 0.07, 0], [-0.19, 0.19, 0], 0.045, bone),
    sphere([-0.245, 0.185, 0], 0.062, bone),
    sphere([-0.185, 0.245, 0], 0.062, bone),
  );
  const geometry = polygonize(blend(0.03, flesh, knuckle), { min: [-0.36, -0.36, -0.24], max: [0.36, 0.36, 0.24], step: 0.009, occlusion: 0.25 });
  return mesh(geometry, iconMaterial(), 'drumstick');
}

// A drop of oil, deep amber at the bottom and bright gold at the tip.
function droplet() {
  const geometry = teardrop(0.58, 0.215, 64);
  shade(geometry, (p) => mix('#f08a12', '#ffd54f', (p.y + 0.215) / 0.58));
  const icon = mesh(geometry, painted({ roughness: 0.1, clearcoat: 1, clearcoatRoughness: 0.04 }), 'droplet');
  icon.position.y = -0.11;
  return icon;
}

// An ear of wheat: paired grains up a slim stem, one grain on top, one leaf.
function wheat() {
  const gold = '#eab64a', stem = '#c99a45';
  const parts = [roundCone([0.02, -0.34, 0], [0, 0.22, 0], 0.022, 0.016, stem)];
  for (let k = 0; k < 4; k++) {
    const y = -0.12 + k * 0.105;
    parts.push(ellipsoid([-0.062, y, 0], [0.048, 0.085, 0.045], gold, [0, 0, 0.55]));
    parts.push(ellipsoid([0.062, y + 0.035, 0], [0.048, 0.085, 0.045], gold, [0, 0, -0.55]));
  }
  parts.push(ellipsoid([0, 0.31, 0], [0.046, 0.085, 0.044], gold));
  parts.push(ellipsoid([0.1, -0.24, 0], [0.032, 0.13, 0.018], stem, [0, 0, -0.65]));
  const field = gradient(blend(0.02, ...parts), '#c98a2c', '#f6cd5e', -0.3, 0.38);
  const geometry = polygonize(field, { min: [-0.22, -0.4, -0.09], max: [0.24, 0.42, 0.09], step: 0.007, occlusion: 0.25 });
  return mesh(geometry, iconMaterial(), 'wheat');
}

function buildRing(name, color, icon, { value = 0.7 } = {}) {
  const group = new THREE.Group();
  group.name = name;
  // Pale nutrients need more depth than dark ones to stand off the track.
  const extra = Math.max(0, new THREE.Color(color).getHSL({}, THREE.SRGBColorSpace).l - 0.5) * 0.45;
  const start = deepen(color, 0.04 + extra);
  const full = deepen(color, 0.14 + extra);

  // The track is the app's neutral ring grey with a hint of the nutrient.
  const track = mesh(new THREE.TorusGeometry(RADIUS, TUBE * 0.78, 24, 160), toy(mix('#e9eee5', color, 0.12), { roughness: 0.62, clearcoat: 0.1, clearcoatRoughness: 0.5 }), `${name}-track`);
  track.position.z = -0.015;
  group.add(track);

  const gloss = { roughness: 0.3, clearcoat: 0.6, clearcoatRoughness: 0.14 };
  const arc = mesh(arcGeometry(start, full), painted(gloss), `${name}-arc`);
  // Its colours from twelve o'clock round to full, for fillRing() to lay the
  // arc out again after loading.
  arc.userData.gradient = [...start.toArray(), ...full.toArray()];
  group.add(arc);

  const capGeometry = new THREE.SphereGeometry(TUBE, 32, 24);
  const head = mesh(capGeometry, toy(start, gloss), `${name}-start`);
  head.position.set(0, RADIUS, 0);
  const tip = mesh(capGeometry, toy(full, gloss), `${name}-end`);
  group.add(head, tip);

  const badge = icon();
  badge.scale.multiplyScalar(1.22);
  group.add(badge);

  group.setValue = (v) => fillRing(group, v);
  fillRing(group, value);
  return group;
}

export const calories = (options) => buildRing('calories', COLORS.calories, flame, { value: 0.72, ...options });
export const carbs = (options) => buildRing('carbs', COLORS.carbs, wheat, { value: 0.58, ...options });
export const protein = (options) => buildRing('protein', COLORS.protein, drumstick, { value: 0.46, ...options });
export const fat = (options) => buildRing('fat', COLORS.fat, droplet, { value: 0.84, ...options });
