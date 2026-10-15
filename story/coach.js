/*
 * The coach's part in the story: posing it with its morph targets, walking
 * it, painting its muscle load in, framing it for a camera, and finding its
 * chest for the phone's posture correction.
 */
import * as THREE from 'three';
import { AFTER_SQUATS, paintLoad } from '../models/src/dino.js';

const clamp = (v) => Math.min(1, Math.max(0, v));
const smooth = (t) => t * t * (3 - 2 * t);

// Returns pose(...values) for a coach loaded from dino.glb, setting the
// morph targets named in `names` (every mesh carries all of them) in order:
// the squat and the slouch by default.
export function poser(model, names = ['squat', 'slouch']) {
  const meshes = [];
  model.traverse((o) => {
    if (o.morphTargetDictionary && names[0] in o.morphTargetDictionary) meshes.push(o);
  });
  return (...values) => {
    for (const m of meshes) names.forEach((name, k) => (m.morphTargetInfluences[m.morphTargetDictionary[name]] = values[k]));
  };
}

// Body units the coach covers per pair of steps, so its feet keep to the
// ground (each foot goes 0.18 forward and back, see addSteps in dino.js).
const PACE = 0.72;

/*
 * Returns step(distance, amount) for a coach from dino.glb, which carries its
 * walk (see addSteps in dino.js):
 * distance is how far it has walked, in body units, and sets where it is in
 * its steps; amount (0 to 1) how much it is stepping, so it can come to a
 * stand. It also returns how high the body bobs and how far it rocks onto
 * the standing foot, for the caller to move it by.
 */
export function stepper(model) {
  const pose = poser(model, ['stride', 'liftLeft', 'liftRight']);
  return (distance, amount) => {
    const phase = (distance / PACE) * Math.PI * 2;
    const c = Math.cos(phase);
    pose(amount * Math.sin(phase), amount * Math.max(0, c), amount * Math.max(0, -c));
    // Highest with one foot up; rocked onto the other foot, the right (-x)
    // while the left is up.
    return { bob: amount * Math.abs(c) * 0.035, rock: amount * c * 0.05 };
  };
}

// Adds a model to a scene, turned by yaw and pitch and centred, and returns a
// camera (28° tall) that frames it, filling `fill` of the view's height or
// width, whichever runs out first.
export function frameOn(scene, model, aspect, { yaw = 0, pitch = 0, fill = 1 } = {}) {
  const holder = new THREE.Group();
  holder.rotation.set(pitch, yaw, 0);
  holder.add(model);
  scene.add(holder);
  holder.updateMatrixWorld(true);
  const box = new THREE.Box3().setFromObject(holder, true);
  const size = box.getSize(new THREE.Vector3());
  holder.position.sub(box.getCenter(new THREE.Vector3()));
  const camera = new THREE.PerspectiveCamera(28, aspect, 0.05, 50);
  const half = Math.tan((14 * Math.PI) / 180);
  camera.position.z = Math.max(size.y / 2 / half, size.x / 2 / (half * aspect)) / fill + size.z / 2;
  return camera;
}

/*
 * Gives a coach its own colours and returns sweep(level), which repaints it
 * from its own green (0) to its muscle load after the evening's squats (1)
 * with a band rising from the feet, the way a scan would show it.
 */
export function loadSweep(coach) {
  const bodies = [];
  const spikes = [];
  coach.traverse((o) => {
    if (!o.isMesh) return;
    if (o.name.startsWith('dino-body')) {
      // A geometry of its own for the colours; the shape stays shared.
      const own = new THREE.BufferGeometry();
      for (const [name, attribute] of Object.entries(o.geometry.attributes)) own.setAttribute(name, name === 'color' ? attribute.clone() : attribute);
      own.setIndex(o.geometry.index);
      own.morphAttributes = o.geometry.morphAttributes;
      own.morphTargetsRelative = o.geometry.morphTargetsRelative;
      o.geometry = own;
      bodies.push({ mesh: o, from: own.attributes.color.array.slice() });
    } else if (o.name.startsWith('spike')) {
      spikes.push({ mesh: o, from: o.material.color.clone() });
    }
  });
  paintLoad(coach, AFTER_SQUATS);
  for (const body of bodies) body.to = body.mesh.geometry.attributes.color.array.slice();
  for (const spike of spikes) spike.to = spike.mesh.material.color.clone();
  let low = Infinity, high = -Infinity;
  for (const { mesh } of bodies) {
    const position = mesh.geometry.attributes.position;
    for (let i = 0; i < position.count; i++) {
      low = Math.min(low, position.getY(i));
      high = Math.max(high, position.getY(i));
    }
  }
  const band = (high - low) * 0.12;
  return (level) => {
    const line = low - band + (high - low + 2 * band) * level;
    for (const { mesh, from, to } of bodies) {
      const color = mesh.geometry.attributes.color, position = mesh.geometry.attributes.position, out = color.array;
      for (let i = 0; i < color.count; i++) {
        const k = smooth(clamp((line - position.getY(i)) / band));
        for (let n = 3 * i; n < 3 * i + 3; n++) out[n] = from[n] + (to[n] - from[n]) * k;
      }
      color.needsUpdate = true;
    }
    for (const { mesh, from, to } of spikes) mesh.material.color.copy(from).lerp(to, level);
  };
}

/*
 * Where a camera sees the coach's chest, as posed, in world space: the
 * highest point down the front of the body's middle line, from under the jaw
 * to the waist, that nothing hides from the camera (the lowest, if all of it
 * is hidden). A slouch drops the head in front of the top of the chest.
 */
export function chestOf(coach, camera) {
  let body = null;
  coach.traverse((o) => {
    if (o.isMesh && o.name.startsWith('dino-body')) body = o;
  });
  coach.updateWorldMatrix(true, true);
  const position = body.geometry.attributes.position;
  // The front-most vertex of the middle line in each band of height.
  const front = new Map();
  for (let i = 0; i < position.count; i++) {
    const x = position.getX(i), y = position.getY(i), z = position.getZ(i);
    if (Math.abs(x) > 0.05 || y < 0.8 || y > 1.08 || z < 0.2) continue;
    const band = Math.round(y / 0.02);
    if (!front.has(band) || z > position.getZ(front.get(band))) front.set(band, i);
  }
  const eye = camera.getWorldPosition(new THREE.Vector3());
  const ray = new THREE.Raycaster();
  const point = new THREE.Vector3();
  for (const band of [...front.keys()].sort((a, b) => b - a)) {
    body.getVertexPosition(front.get(band), point).applyMatrix4(body.matrixWorld);
    const distance = eye.distanceTo(point);
    ray.set(eye, point.clone().sub(eye).divideScalar(distance));
    const [hit] = ray.intersectObject(coach, true);
    if (!hit || hit.distance > distance - 0.02) break;
  }
  return point;
}
