/*
 * The TREX coach: a chibi T-Rex in moulded vinyl. The body is one blended
 * signed distance shape (head, torso, legs, arms and tail melted together,
 * with the belly and spots painted on and the brows, lids, mouth and nostrils
 * sculpted in). Eyes, teeth, claws and back spikes are separate glossy parts
 * placed on that surface. Feet rest on y = 0; it faces +Z, about two units tall.
 *
 * Every part carries two morph targets for the workout scene: "squat", a
 * good squat, and "slouch", the same squat with the chest dropped forward,
 * the fault the coach corrects. paintLoad() recolours the coach the way the
 * app's muscle load screen paints its body.
 */
import * as THREE from 'three';
import { above, blend, capsule, carve, clip, ellipsoid, polygonize, rgb, roundCone, sphere, tint } from './sdf.js';
import { COLORS, deepen, lathe, mesh, painted, toy } from './look.js';

const SKIN = '#4f822d';
const BROW = '#406d24';
const BELLY = '#d8df9e';
const SPOT = '#457529';
const SPIKE = '#355d21';
const MOUTH = '#4a1f1d';
const NOSTRIL = '#3f5f28';

const smooth = (t) => t * t * (3 - 2 * t);
const clamp01 = (v) => Math.min(1, Math.max(0, v));
const both = (fn) => [fn(1), fn(-1)];

const sample = { d: 0, r: 0, g: 0, b: 0 };

// Marches from far outside along -out until it reaches the surface.
function surface(field, target, out) {
  const len = Math.hypot(out[0], out[1], out[2]);
  const d = [-out[0] / len, -out[1] / len, -out[2] / len];
  const p = [target[0] - d[0] * 1.5, target[1] - d[1] * 1.5, target[2] - d[2] * 1.5];
  for (let i = 0; i < 300; i++) {
    field(p[0], p[1], p[2], sample);
    if (Math.abs(sample.d) < 1e-5) break;
    const step = sample.d * 0.8;
    p[0] += d[0] * step; p[1] += d[1] * step; p[2] += d[2] * step;
  }
  return p;
}

function normalAt(field, p) {
  const h = 1e-3;
  const at = (x, y, z) => { field(x, y, z, sample); return sample.d; };
  const n = new THREE.Vector3(
    at(p[0] + h, p[1], p[2]) - at(p[0] - h, p[1], p[2]),
    at(p[0], p[1] + h, p[2]) - at(p[0], p[1] - h, p[2]),
    at(p[0], p[1], p[2] + h) - at(p[0], p[1], p[2] - h),
  );
  return n.normalize();
}

const add = (p, v, s = 1) => [p[0] + v.x * s, p[1] + v.y * s, p[2] + v.z * s];

/*
 * The plain sculpt: every mass of the body in skin colour, before any paint
 * or detail. Numbers are in body units with the feet on y = 0.
 */
function sculpt() {
  // A pear: broad at the hips, narrowing up through the chest into the neck.
  const trunk = blend(0.16,
    ellipsoid([0, 0.7, -0.02], [0.5, 0.46, 0.45], SKIN, [0.12, 0, 0]),
    ellipsoid([0, 1.02, 0.09], [0.36, 0.3, 0.32], SKIN),
    roundCone([0, 1.1, 0.08], [0, 1.38, 0.16], 0.32, 0.3, SKIN),
    roundCone([0, 0.62, -0.26], [0.08, 0.4, -0.7], 0.31, 0.2, SKIN),
  );
  const tail = blend(0.08,
    roundCone([0.08, 0.42, -0.7], [0.34, 0.22, -1.0], 0.2, 0.12, SKIN),
    roundCone([0.34, 0.22, -1.0], [0.64, 0.13, -1.1], 0.12, 0.05, SKIN),
  );
  // A big head with a long snout, a bump over the nostrils and a heavy jaw.
  const head = blend(0.12,
    ellipsoid([0, 1.56, 0.14], [0.48, 0.42, 0.46], SKIN),
    roundCone([0, 1.48, 0.36], [0, 1.43, 0.86], 0.3, 0.21, SKIN),
    ellipsoid([0, 1.59, 0.84], [0.15, 0.08, 0.14], SKIN),
    ellipsoid([0, 1.27, 0.44], [0.31, 0.14, 0.44], SKIN),
    ...both((s) => ellipsoid([0.29 * s, 1.39, 0.28], [0.18, 0.18, 0.21], SKIN)),
  );
  const legs = both((s) => blend(0.05,
    blend(0.1,
      ellipsoid([0.3 * s, 0.48, 0.04], [0.26, 0.3, 0.32], SKIN, [0.2, 0, 0.1 * s]),
      roundCone([0.31 * s, 0.38, 0], [0.33 * s, 0.13, 0.04], 0.17, 0.13, SKIN),
    ),
    ellipsoid([0.34 * s, 0.08, 0.14], [0.18, 0.1, 0.27], SKIN),
    ...[-0.1, 0, 0.1].map((dx) => sphere([0.34 * s + dx, 0.07, 0.37 - Math.abs(dx) * 0.3], 0.072, SKIN)),
  ));
  const arms = both((s) => blend(0.03,
    roundCone([0.32 * s, 0.98, 0.26], [0.4 * s, 0.86, 0.38], 0.085, 0.07, SKIN),
    roundCone([0.4 * s, 0.86, 0.38], [0.34 * s, 0.8, 0.52], 0.07, 0.06, SKIN),
    sphere([0.33 * s, 0.79, 0.55], 0.066, SKIN),
  ));
  const body = blend(0.05, blend(0.12, blend(0.14, trunk, tail, head), ...legs), ...arms);
  return above(0.015, body, 0);
}

// Belly, throat and a few darker spots on the head and back.
function paint(field) {
  const skin = rgb(SKIN), belly = rgb(BELLY), spot = rgb(SPOT);
  const spots = [
    [0.12, 1.92, 0.05, 0.07], [-0.16, 1.9, -0.02, 0.06], [0.02, 1.86, -0.18, 0.05],
    [0.24, 1.78, -0.16, 0.05], [-0.3, 1.0, -0.3, 0.07], [0.32, 0.86, -0.32, 0.06],
    [-0.08, 0.96, -0.42, 0.05], [0.16, 0.6, -0.62, 0.06], [-0.1, 0.62, -0.56, 0.05],
  ];
  return tint(field, (x, y, z, o) => {
    // Belly: a soft oval down the front, from under the jaw to the hips.
    const e = (x / 0.32) ** 2 + ((y - 0.9) / 0.6) ** 2 + ((z - 0.5) / 0.46) ** 2;
    let m = smooth(clamp01((1.02 - e) / 0.1)) * smooth(clamp01((1.33 - y) / 0.05));
    // Throat: the underside of the lower jaw.
    const t = (x / 0.25) ** 2 + ((y - 1.15) / 0.12) ** 2 + ((z - 0.48) / 0.4) ** 2;
    m = Math.max(m, smooth(clamp01((1.02 - t) / 0.12)));
    let r = skin[0] + (belly[0] - skin[0]) * m;
    let g = skin[1] + (belly[1] - skin[1]) * m;
    let b = skin[2] + (belly[2] - skin[2]) * m;
    for (const [sx, sy, sz, sr] of spots) {
      const d = Math.hypot(x - sx, y - sy, z - sz);
      if (d < sr * 1.6) {
        const w = smooth(clamp01((sr * 1.6 - d) / (sr * 0.5))) * (1 - m);
        r += (spot[0] - r) * w; g += (spot[1] - g) * w; b += (spot[2] - b) * w;
      }
    }
    o.r = r; o.g = g; o.b = b;
  });
}

// A rounded cone standing on +Y, base at the origin.
function hornGeometry(height, radius) {
  return lathe([[radius, 0], [radius * 0.95, height * 0.25], [radius * 0.62, height * 0.62], [radius * 0.24, height * 0.92], [0, height]], 24, 24);
}

// Points a part's +Y axis along dir and puts its base at p.
function stand(part, p, dir) {
  part.position.set(p[0], p[1], p[2]);
  part.quaternion.setFromUnitVectors(new THREE.Vector3(0, 1, 0), dir.clone().normalize());
  return part;
}

/*
 * An eye: a white ball with a dark iris and pupil as caps on its front, two
 * highlights, looking along `look`.
 */
function eye(radius, look) {
  const group = new THREE.Group();
  group.name = 'eye';
  const gloss = { roughness: 0.16, clearcoat: 1, clearcoatRoughness: 0.05 };
  group.add(mesh(new THREE.SphereGeometry(radius, 48, 36), toy('#fbfaf4', gloss), 'eye-white'));
  const cap = (scale, angle, color, name) => {
    const geometry = new THREE.SphereGeometry(radius * scale, 48, 12, 0, Math.PI * 2, 0, angle);
    const part = mesh(geometry, toy(color, gloss), name);
    part.quaternion.setFromUnitVectors(new THREE.Vector3(0, 1, 0), look);
    group.add(part);
  };
  cap(1.006, 0.62, '#3b2415', 'eye-iris');
  cap(1.012, 0.36, '#0d0907', 'eye-pupil');
  const shine = toy('#ffffff', { emissive: '#ffffff', emissiveIntensity: 0.6, roughness: 0.2 });
  const glint = (offset, size) => {
    const dir = look.clone().add(offset).normalize();
    const dot = mesh(new THREE.SphereGeometry(radius * size, 16, 12), shine, 'eye-glint');
    dot.position.copy(dir.multiplyScalar(radius * 1.02));
    dot.scale.z = 0.5;
    dot.lookAt(dot.position.clone().multiplyScalar(2));
    group.add(dot);
  };
  glint(new THREE.Vector3(-0.32, 0.36, 0), 0.2);
  glint(new THREE.Vector3(0.26, -0.3, 0), 0.09);
  return group;
}

/*
 * The squat as a deformation of body space. Feet stay planted; from the knees
 * up the body drops, the hips go back and the trunk leans forward about the
 * hips, with the knees pushed forward and a little apart. With slouch, the
 * chest and head fold further forward about the upper back.
 */
const HIP = [0, 0.6, -0.04];
const UPPER_BACK = [0, 0.98, -0.1];

function turnX(p, pivot, angle) {
  const y = p[1] - pivot[1], z = p[2] - pivot[2];
  const c = Math.cos(angle), s = Math.sin(angle);
  return [p[0], pivot[1] + y * c - z * s, pivot[2] + y * s + z * c];
}

function squatMove(p) {
  const q = turnX(p, HIP, 0.3);
  return [q[0], q[1] - 0.24, q[2] - 0.07];
}

function posed(p, slouch) {
  const [x, y, z] = p;
  let q = squatMove(p);
  if (slouch) {
    const fold = smooth(clamp01((y - 0.84) / 0.3));
    const bent = turnX(q, squatMove(UPPER_BACK), 0.34);
    q = [q[0] + (bent[0] - q[0]) * fold, q[1] + (bent[1] - q[1]) * fold, q[2] + (bent[2] - q[2]) * fold];
  }
  // The upper-body move fades out down the legs, so the thighs and shins bend.
  const w = smooth(clamp01((y - 0.1) / 0.5));
  const leg = smooth(clamp01((Math.abs(x) - 0.12) / 0.08)) * smooth(clamp01((z + 0.42) / 0.14));
  const knee = Math.sin(Math.PI * clamp01((y - 0.06) / 0.52)) * leg;
  return [
    x + (q[0] - x) * w + Math.sign(x) * 0.035 * knee,
    y + (q[1] - y) * w,
    z + (q[2] - z) * w + 0.11 * knee,
  ];
}

/*
 * The walk, as moves that are straight lines from the standing coach, so a
 * negative influence makes the opposite pose. "stride" carries the left foot
 * (+x) forward and the right foot back, bending from the hips, and swings
 * the tail toward the leg in front; "liftLeft" and "liftRight" raise a foot
 * as it passes under the body. The short arms stay as they are.
 */
const STRIDE = 0.18;
const LIFT = 0.12;
const TAIL_SWING = 0.24;
// The upright axis the tail swings about, at its root.
const TAIL_ROOT = -0.36;

// How much a point belongs to the leg on side s (1 for the left, at +x).
const legOf = (x, z, s) => smooth(clamp01((x * s - 0.12) / 0.08)) * smooth(clamp01((z + 0.42) / 0.14));

function strided([x, y, z]) {
  // All of the foot, less of the shin and little of the thigh.
  const swing = STRIDE * (1 - smooth(clamp01((y - 0.12) / 0.44)));
  // Turning about an upright axis by a small angle a moves a point by
  // a * (z, -x); the left hip coming forward turns the tail to the left.
  const a = -TAIL_SWING * smooth(clamp01((TAIL_ROOT - z) / 0.6));
  return [x + a * (z - TAIL_ROOT), y, z + swing * (legOf(x, z, 1) - legOf(x, z, -1)) - a * x];
}

function lifted([x, y, z], s) {
  // The foot all the way, fading out up the shin to the knee.
  const up = LIFT * legOf(x, z, s) * (1 - smooth(clamp01((y - 0.16) / 0.32)));
  return [x, y + up, z + 0.3 * up];
}

/*
 * Adds a morph target per pose to every mesh under root. A pose moves a point
 * of the coach's own space (feet on y = 0, facing +Z); each target holds
 * where the mesh's vertices go, and where their normals turn as the surface
 * stretches, measured along two tangents. relative: as offsets from the
 * mesh's shape, the way a .glb holds its targets; targets already there are
 * turned into offsets too.
 */
function bake(root, poses, { relative = false } = {}) {
  root.updateMatrixWorld(true);
  const toRoot = root.matrixWorld.clone().invert();
  const v = new THREE.Vector3(), n = new THREE.Vector3(), t1 = new THREE.Vector3(), t2 = new THREE.Vector3();
  const a = new THREE.Vector3(), b = new THREE.Vector3(), c = new THREE.Vector3(), out = new THREE.Vector3();
  const eps = 1e-3;
  const done = new Set();
  root.traverse((o) => {
    if (!o.isMesh) return;
    // A geometry shared by two parts would need a different move for each.
    if (done.has(o.geometry)) throw new Error(`${o.name} shares its geometry`);
    done.add(o.geometry);
    const toWorld = new THREE.Matrix4().multiplyMatrices(toRoot, o.matrixWorld), toLocal = toWorld.clone().invert();
    const normalWorld = new THREE.Matrix3().getNormalMatrix(toWorld);
    const normalLocal = new THREE.Matrix3().setFromMatrix4(toWorld).transpose();
    const { position, normal } = o.geometry.attributes;
    const targets = poses.map(([name, pose]) => {
      const pos = new Float32Array(position.count * 3), nor = new Float32Array(position.count * 3);
      const move = (q, target) => target.fromArray(pose(q.toArray()));
      for (let i = 0; i < position.count; i++) {
        v.fromBufferAttribute(position, i).applyMatrix4(toWorld);
        move(v, a);
        // A point the pose leaves alone keeps its normal too (the poses ease
        // in), and an offset target is already zero there: most of the coach
        // stays still as it walks, so this saves most of the work.
        if (relative && a.equals(v)) continue;
        n.fromBufferAttribute(normal, i).applyMatrix3(normalWorld).normalize();
        t1.set(Math.abs(n.x) < 0.9 ? 1 : 0, Math.abs(n.x) < 0.9 ? 0 : 1, 0).cross(n).normalize();
        t2.crossVectors(n, t1);
        move(c.copy(v).addScaledVector(t1, eps), b);
        b.sub(a);
        move(c.copy(v).addScaledVector(t2, eps), c);
        c.sub(a);
        out.crossVectors(b, c).normalize();
        // Keep the side the surface faced before.
        move(t1.copy(v).addScaledVector(n, eps), t2);
        if (out.dot(t2.sub(a)) < 0) out.negate();
        out.applyMatrix3(normalLocal).normalize();
        a.applyMatrix4(toLocal);
        if (relative) {
          a.sub(v.fromBufferAttribute(position, i));
          out.sub(n.fromBufferAttribute(normal, i));
        }
        pos[3 * i] = a.x; pos[3 * i + 1] = a.y; pos[3 * i + 2] = a.z;
        nor[3 * i] = out.x; nor[3 * i + 1] = out.y; nor[3 * i + 2] = out.z;
      }
      const p = new THREE.BufferAttribute(pos, 3);
      const q = new THREE.BufferAttribute(nor, 3);
      p.name = name;
      q.name = name;
      return [p, q];
    });
    const morphs = o.geometry.morphAttributes;
    if (relative && !o.geometry.morphTargetsRelative) {
      for (const [key, list] of Object.entries(morphs)) {
        const base = o.geometry.attributes[key].array;
        for (const target of list) for (let k = 0; k < target.array.length; k++) target.array[k] -= base[k];
      }
      o.geometry.morphTargetsRelative = true;
    }
    // A loaded mesh keeps its target names on the mesh, not the targets;
    // name them so updateMorphTargets() keeps them.
    for (const [name, k] of Object.entries(o.morphTargetDictionary || {})) morphs.position[k].name ||= name;
    morphs.position = [...(morphs.position || []), ...targets.map(([p]) => p)];
    morphs.normal = [...(morphs.normal || []), ...targets.map(([, q]) => q)];
    o.updateMorphTargets();
  });
}

// The "squat" and "slouch" targets the .glb carries.
function addPoses(root) {
  bake(root, [['squat', (p) => posed(p, false)], ['slouch', (p) => posed(p, true)]]);
}

/*
 * The walk ("stride", "liftLeft", "liftRight"), carried in the .glb too so
 * the page need not work it out while it loads. It adds about a sixth to the
 * packed file: it moves a third of the coach, and the rest, offsets of zero,
 * packs into next to nothing.
 */
function addSteps(coach) {
  bake(coach, [['stride', strided], ['liftLeft', (p) => lifted(p, 1)], ['liftRight', (p) => lifted(p, -1)]], { relative: true });
}

// poses: false leaves out the morph targets, for a coach that never squats.
export function dino({ poses = true } = {}) {
  const group = new THREE.Group();
  group.name = 'dino';
  const base = sculpt();

  // Eyes sit half sunk into the brow, looking a little down at the viewer.
  const eyeRadius = 0.122;
  const eyes = both((s) => {
    const p = surface(base, [0.21 * s, 1.74, 0.14], [0.1 * s, 0.05, 1]);
    const n = normalAt(base, p);
    return { s, center: add(p, n, eyeRadius * 0.12), look: new THREE.Vector3(0.12 * s, -0.12, 1).normalize() };
  });

  let field = paint(base);

  // Brows: firm ridges slanting down toward the snout, for the coach's
  // determined look.
  const brows = eyes.map(({ s, center }) => {
    const inner = surface(base, [center[0] - 0.08 * s, center[1] + 0.14, center[2] - 0.1], [0, 0.35, 1]);
    const outer = surface(base, [center[0] + 0.1 * s, center[1] + 0.17, center[2] - 0.16], [0.4 * s, 0.4, 1]);
    return capsule(inner, outer, 0.045, BROW);
  });
  field = blend(0.035, field, ...brows);

  // Upper lids: skin shells over the top of each eye, cut level with a slant.
  const lids = eyes.map(({ s, center }) => {
    const shell = sphere(center, eyeRadius * 1.14, SKIN);
    const normal = [-0.42 * s, 1, 0.12];
    const cut = [center[0], center[1] + eyeRadius * 0.18, center[2]];
    return clip(0.012, shell, cut, normal);
  });
  field = blend(0.02, field, ...lids);

  // The mouth: a groove from cheek to cheek around the front of the snout,
  // lifting a little at the corners and narrowing there so it fades out
  // instead of ending in a cut.
  const mouth = [];
  for (let i = 0; i <= 16; i++) {
    const u = -1 + (i / 16) * 2;
    const phi = u * 1.3;
    const y = 1.36 + 0.055 * u * u * u * u + 0.02 * u * u;
    mouth.push(surface(base, [0, y, 0.42], [Math.sin(phi), 0, Math.cos(phi)]));
  }
  for (let i = 0; i < mouth.length - 1; i++) {
    const a = mouth[i], b = mouth[i + 1];
    const u = Math.abs(-1 + ((i + 0.5) / 16) * 2);
    field = carve(0.012, field, capsule(a, b, 0.02 * (1 - 0.5 * u * u * u)), MOUTH);
  }

  // Nostrils on the bump at the end of the snout.
  for (const s of [1, -1]) {
    const p = surface(base, [0.075 * s, 1.6, 0.9], [0.25 * s, 0.6, 1]);
    field = carve(0.01, field, sphere(p, 0.026), NOSTRIL);
  }

  const geometry = polygonize(field, { min: [-0.64, -0.02, -1.24], max: [0.78, 2.04, 1.12], step: 0.0125, occlusion: 0.3 });
  const skin = mesh(geometry, painted({ roughness: 0.46, clearcoat: 0.55, clearcoatRoughness: 0.22, sheen: 0.3, sheenColor: '#e6f5c8', sheenRoughness: 0.6 }), 'dino-body');
  group.add(skin);

  for (const { center, look } of eyes) {
    const e = eye(eyeRadius, look);
    e.position.set(center[0], center[1], center[2]);
    group.add(e);
  }

  const ivory = toy(COLORS.ivory, { roughness: 0.28, clearcoat: 0.8, clearcoatRoughness: 0.1 });

  // Teeth along the front of the upper jaw, hanging over the lip.
  for (let i = 3; i <= 13; i++) {
    const p = mouth[i];
    const n = normalAt(base, p);
    const size = i % 2 ? 1 : 0.82;
    const tooth = mesh(hornGeometry(0.075 * size, 0.03 * size), ivory, 'tooth');
    const tip = new THREE.Vector3(n.x * 0.35, -1, n.z * 0.35);
    group.add(stand(tooth, add(p, n, -0.004).map((v, k) => (k === 1 ? v + 0.032 : v)), tip));
  }

  // Claws: three per foot, two per hand.
  for (const s of [1, -1]) {
    for (const dx of [-0.1, 0, 0.1]) {
      const p = surface(base, [0.34 * s + dx, 0.06, 0.37], [dx * 2, -0.3, 1]);
      group.add(stand(mesh(hornGeometry(0.07, 0.04), ivory, 'claw'), add(p, new THREE.Vector3(0, 0, -1), 0.02), new THREE.Vector3(dx * 1.5, -0.5, 1)));
    }
    for (const dx of [-0.03, 0.03]) {
      const p = surface(base, [0.33 * s + dx, 0.78, 0.55], [dx * 4, -0.2, 1]);
      group.add(stand(mesh(hornGeometry(0.05, 0.022), ivory, 'claw'), add(p, new THREE.Vector3(0, 0, -1), 0.012), new THREE.Vector3(dx * 4, -0.4, 1)));
    }
  }

  // Spikes down the back, from the crown to the tip of the tail.
  const spikeMaterial = toy(SPIKE, { roughness: 0.35, clearcoat: 0.8, clearcoatRoughness: 0.12 });
  const ridge = [
    [[0, 1.75, 0.08], [0, 1, 0.2], 0.12],
    [[0, 1.72, -0.06], [0, 0.85, -0.5], 0.15],
    [[0, 1.52, -0.12], [0, 0.45, -1], 0.16],
    [[0, 1.26, -0.12], [0, 0.25, -1], 0.16],
    [[0, 1.0, -0.14], [0, 0.15, -1], 0.15],
    [[0, 0.74, -0.3], [0, 0.35, -1], 0.14],
    [[0.06, 0.5, -0.62], [0.05, 0.75, -0.7], 0.12],
    [[0.2, 0.33, -0.86], [0.1, 1, -0.4], 0.1],
    [[0.38, 0.24, -1.02], [0.1, 1, -0.2], 0.08],
    [[0.54, 0.17, -1.08], [0.1, 1, 0], 0.06],
  ];
  const plate = new THREE.Vector3();
  for (let i = 0; i < ridge.length; i++) {
    const [target, out, size] = ridge[i];
    const p = surface(base, target, out);
    const n = normalAt(base, p);
    const dir = n.clone().add(new THREE.Vector3(0, 0, -0.3)).normalize();
    const spike = stand(mesh(hornGeometry(size, size * 0.6), spikeMaterial, 'spike'), add(p, n, -size * 0.2), dir);
    // Flatten across the spine so each spike reads as a soft plate. The
    // spine runs from this anchor toward the next one.
    const next = ridge[Math.min(i + 1, ridge.length - 1)][0];
    const prev = ridge[Math.max(i - 1, 0)][0];
    plate.set(next[0] - prev[0], next[1] - prev[1], next[2] - prev[2]).normalize();
    const side = new THREE.Vector3().crossVectors(dir, plate).normalize();
    const along = new THREE.Vector3().crossVectors(side, dir).normalize();
    spike.quaternion.setFromRotationMatrix(new THREE.Matrix4().makeBasis(side, dir, along));
    spike.scale.set(0.55, 1, 1);
    group.add(spike);
  }
  if (poses) {
    addPoses(group);
    addSteps(group);
  }
  return group;
}

/*
 * Muscle load, painted the way the app's muscle screen does: the body turns
 * grey and each muscle group shades toward lime at 50 points and green at
 * 100. Groups are the app's (trainingload/MuscleLoad.kt); each is a soft
 * region of the coach's body. Scores are record-based estimates, not
 * measurements, as the app says on that screen.
 */
// The app's flat colours, deepened like the rings so the lit side of the
// glossy body still reads as them.
const LOAD_GREY = new THREE.Color('#d6d8d1');
const LOAD_LIME = deepen('#c7e26b', 0.12);
const LOAD_GREEN = deepen('#759848', 0.06);

const band = (v, from, to, soft = 0.05) => smooth(clamp01((v - from) / soft)) * (1 - smooth(clamp01((v - to) / soft)));
const segment = (x, y, z, a, b, r) => {
  const abx = b[0] - a[0], aby = b[1] - a[1], abz = b[2] - a[2];
  const t = clamp01(((x - a[0]) * abx + (y - a[1]) * aby + (z - a[2]) * abz) / (abx * abx + aby * aby + abz * abz));
  const d = Math.hypot(x - a[0] - abx * t, y - a[1] - aby * t, z - a[2] - abz * t);
  return 1 - smooth(clamp01((d - r) / 0.04));
};
const UPPER_ARM = (s) => [[0.32 * s, 0.98, 0.26], [0.4 * s, 0.86, 0.38]];
const FOREARM = (s) => [[0.4 * s, 0.86, 0.38], [0.34 * s, 0.8, 0.52]];

export const MUSCLE_REGIONS = {
  QUADS: (x, y, z) => band(Math.abs(x), 0.14, 0.56) * band(y, 0.2, 0.74) * smooth(clamp01((z + 0.04) / 0.14)),
  HAMSTRINGS: (x, y, z) => band(Math.abs(x), 0.16, 0.56) * band(y, 0.2, 0.7) * (1 - smooth(clamp01((z + 0.02) / 0.12))),
  GLUTES: (x, y, z) => band(Math.abs(x), 0, 0.46) * band(y, 0.42, 0.86) * (1 - smooth(clamp01((z + 0.14) / 0.1))),
  ADDUCTORS: (x, y) => band(Math.abs(x), 0.06, 0.22, 0.04) * band(y, 0.24, 0.6),
  ABDUCTORS: (x, y) => smooth(clamp01((Math.abs(x) - 0.4) / 0.06)) * band(y, 0.44, 0.76),
  CALVES: (x, y, z) => smooth(clamp01((Math.abs(x) - 0.15) / 0.05)) * band(y, 0.12, 0.36) * (1 - smooth(clamp01((z - 0.0) / 0.1))),
  HIP_FLEXORS: (x, y, z) => band(Math.abs(x), 0.08, 0.3) * band(y, 0.56, 0.74) * smooth(clamp01((z - 0.18) / 0.08)),
  ABS: (x, y, z) => band(Math.abs(x), -1, 0.2) * band(y, 0.46, 0.96) * smooth(clamp01((z - 0.22) / 0.08)),
  OBLIQUES: (x, y, z) => band(Math.abs(x), 0.24, 0.52) * band(y, 0.5, 0.96) * smooth(clamp01((z + 0.16) / 0.1)),
  PECS: (x, y, z) => band(Math.abs(x), -1, 0.3) * band(y, 0.96, 1.2) * smooth(clamp01((z - 0.18) / 0.08)),
  SERRATUS: (x, y, z) => band(Math.abs(x), 0.25, 0.42) * band(y, 0.84, 1.04) * band(z, -0.02, 0.22),
  ERECTORS: (x, y, z) => band(Math.abs(x), -1, 0.16) * band(y, 0.64, 1.02) * (1 - smooth(clamp01((z + 0.2) / 0.08))),
  LATS: (x, y, z) => band(Math.abs(x), 0.18, 0.46) * band(y, 0.8, 1.14) * (1 - smooth(clamp01((z + 0.06) / 0.08))),
  TRAPS: (x, y, z) => band(Math.abs(x), -1, 0.26) * band(y, 1.12, 1.36) * (1 - smooth(clamp01(z / 0.08))),
  DELTOIDS: (x, y, z) => 1 - smooth(clamp01((Math.hypot(Math.abs(x) - 0.33, y - 1.0, z - 0.22) - 0.08) / 0.05)),
  BICEPS: (x, y, z) => segment(Math.abs(x), y, z, ...UPPER_ARM(1), 0.06) * smooth(clamp01((y - 0.9 + (z - 0.32)) / 0.06)),
  TRICEPS: (x, y, z) => segment(Math.abs(x), y, z, ...UPPER_ARM(1), 0.06) * (1 - smooth(clamp01((y - 0.9 + (z - 0.32)) / 0.06))),
  FOREARMS: (x, y, z) => segment(Math.abs(x), y, z, ...FOREARM(1), 0.06),
};

// The scale on the app's muscle screen: grey at 0, lime at 50, green at 100.
export function loadColor(score, target = new THREE.Color()) {
  const t = clamp01(score / 100);
  return t < 0.5 ? target.copy(LOAD_GREY).lerp(LOAD_LIME, t * 2) : target.copy(LOAD_LIME).lerp(LOAD_GREEN, (t - 0.5) * 2);
}

const unpainted = new WeakMap();

/*
 * Repaints a coach built here or loaded from its .glb. scores maps muscle
 * groups to 0-100, e.g. { QUADS: 39, GLUTES: 31 }; pass null to restore the
 * coach's own colours.
 */
export function paintLoad(coach, scores) {
  const skinLum = (() => {
    const c = new THREE.Color(SKIN);
    return 0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b;
  })();
  const shade = new THREE.Color(), hot = new THREE.Color();
  coach.traverse((o) => {
    if (!o.isMesh) return;
    if (o.name.startsWith('spike')) {
      if (!unpainted.has(o)) unpainted.set(o, o.material);
      o.material = scores ? unpainted.get(o).clone() : unpainted.get(o);
      if (scores) o.material.color.copy(LOAD_GREY).multiplyScalar(0.86);
      return;
    }
    if (!o.name.startsWith('dino-body')) return;
    const color = o.geometry.attributes.color, position = o.geometry.attributes.position;
    if (!unpainted.has(o.geometry)) unpainted.set(o.geometry, color.array.slice());
    const original = unpainted.get(o.geometry);
    if (!scores) {
      color.array.set(original);
      color.needsUpdate = true;
      return;
    }
    const groups = Object.entries(scores).filter(([key]) => MUSCLE_REGIONS[key]);
    for (let i = 0; i < color.count; i++) {
      const x = position.getX(i), y = position.getY(i), z = position.getZ(i);
      // Keep the moulding's light and shadow: the belly stays lighter and
      // creases stay darker, now in grey.
      const r = original[3 * i], g = original[3 * i + 1], b = original[3 * i + 2];
      const tone = Math.min(1.08, Math.max(0.55, (0.2126 * r + 0.7152 * g + 0.0722 * b) / skinLum)) * 0.9;
      let load = 0;
      for (const [key, score] of groups) load = Math.max(load, score * MUSCLE_REGIONS[key](x, y, z));
      loadColor(load, hot);
      shade.copy(hot).multiplyScalar(Math.min(1, tone));
      color.setXYZ(i, shade.r, shade.g, shade.b);
    }
    color.needsUpdate = true;
  });
}

// The coach after the evening's three sets of ten squats, as the night scene
// shows it. Scores follow the squat's muscle roles in the app.
export const AFTER_SQUATS = { QUADS: 39, GLUTES: 31, ADDUCTORS: 13, HAMSTRINGS: 9, CALVES: 9, ERECTORS: 6, ABS: 4 };

export function dinoLoad() {
  // The night scene only shows the coach standing, so it needs no poses.
  const coach = dino({ poses: false });
  coach.name = 'dino-load';
  paintLoad(coach, AFTER_SQUATS);
  return coach;
}
