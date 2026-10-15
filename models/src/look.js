/*
 * Shared look for every model: one palette, one glossy vinyl material and a
 * few shape helpers, so the set reads as one family of toys.
 */
import * as THREE from 'three';

export const COLORS = {
  ink: '#262b30',
  ivory: '#fbf6e9',
  // Nutrition, from the TREX app theme (TrexTheme.kt).
  calories: '#759848',
  carbs: '#a8c47c',
  protein: '#c7e26b',
  fat: '#d78b28',
};

// Soft vinyl under a clear lacquer: diffuse enough to keep its colour on a
// pastel background, glossy enough to catch the room's highlights.
export function toy(color, options = {}) {
  return new THREE.MeshPhysicalMaterial({
    color,
    roughness: 0.42,
    metalness: 0,
    clearcoat: 0.7,
    clearcoatRoughness: 0.16,
    ...options,
  });
}

// The same material reading its colour from the geometry.
export function painted(options = {}) {
  return toy('#ffffff', { vertexColors: true, ...options });
}

export function mesh(geometry, material, name) {
  const m = new THREE.Mesh(geometry, material);
  m.castShadow = true;
  m.receiveShadow = true;
  if (name) m.name = name;
  return m;
}

// Writes a vertex colour into each vertex from fn(position, normal) -> colour.
export function shade(geometry, fn) {
  const pos = geometry.attributes.position;
  const nor = geometry.attributes.normal;
  const colors = new Float32Array(pos.count * 3);
  const p = new THREE.Vector3();
  const n = new THREE.Vector3();
  const c = new THREE.Color();
  for (let i = 0; i < pos.count; i++) {
    p.fromBufferAttribute(pos, i);
    n.fromBufferAttribute(nor, i);
    c.set(fn(p, n));
    colors[3 * i] = c.r;
    colors[3 * i + 1] = c.g;
    colors[3 * i + 2] = c.b;
  }
  geometry.setAttribute('color', new THREE.BufferAttribute(colors, 3));
  return geometry;
}

// Linear mix of two colours, t in [0, 1].
export function mix(a, b, t) {
  return new THREE.Color(a).lerp(new THREE.Color(b), Math.min(1, Math.max(0, t)));
}

/*
 * A flat UI colour made a little deeper and richer, so that under bright
 * studio light the lit side of a glossy part still reads as the UI colour.
 */
export function deepen(color, amount) {
  const c = new THREE.Color(color);
  const { h, s, l } = c.getHSL({}, THREE.SRGBColorSpace);
  return c.setHSL(h, Math.min(1, s + amount * 0.6), Math.max(0, l - amount), THREE.SRGBColorSpace);
}

/*
 * Averaged vertex normals for a solid of revolution. At a pole every face is
 * degenerate and the average vanishes, so those vertices point along the axis.
 */
function smoothed(geometry) {
  geometry.computeVertexNormals();
  geometry.computeBoundingBox();
  const middle = (geometry.boundingBox.min.y + geometry.boundingBox.max.y) / 2;
  const n = geometry.attributes.normal, p = geometry.attributes.position;
  for (let i = 0; i < n.count; i++) {
    if (Math.hypot(n.getX(i), n.getY(i), n.getZ(i)) < 1e-6) n.setXYZ(i, 0, p.getY(i) > middle ? 1 : -1, 0);
  }
  return geometry;
}

// A smooth solid of revolution about +Y from [radius, height] control points.
export function lathe(points, segments = 48, steps = 64) {
  const curve = new THREE.SplineCurve(points.map(([r, y]) => new THREE.Vector2(r, y)));
  const profile = curve.getPoints(steps).map((v) => new THREE.Vector2(Math.max(0, v.x), v.y));
  return smoothed(new THREE.LatheGeometry(profile, segments));
}

// A raindrop or oil drop: round at the bottom, drawn to a soft point at the top.
export function teardrop(height, radius, segments = 48) {
  const points = [];
  for (let i = 0; i <= 40; i++) {
    const t = i / 40; // 0 at the bottom, 1 at the tip
    const angle = t * Math.PI;
    // A sphere's profile, pinched toward the tip.
    const r = radius * Math.sin(angle) * Math.pow(1 - t * 0.55, 1.6);
    const y = -radius + (height * (1 - Math.cos(angle))) / 2;
    points.push(new THREE.Vector2(Math.max(r, 0), y));
  }
  return smoothed(new THREE.LatheGeometry(points, segments));
}

// A rounded five-point star with a bevelled edge.
export function star(outer, inner, depth, points = 5) {
  const shape = new THREE.Shape();
  for (let i = 0; i <= points * 2; i++) {
    const a = Math.PI / 2 + (i * Math.PI) / points;
    const r = i % 2 === 0 ? outer : inner;
    const x = Math.cos(a) * r, y = Math.sin(a) * r;
    if (i === 0) shape.moveTo(x, y);
    else shape.lineTo(x, y);
  }
  const geometry = new THREE.ExtrudeGeometry(shape, {
    depth,
    bevelEnabled: true,
    bevelThickness: depth * 0.9,
    bevelSize: outer * 0.16,
    bevelSegments: 8,
    curveSegments: 4,
  });
  geometry.center();
  geometry.computeVertexNormals();
  return geometry;
}

// Uniformly scales and recentres a group so its bounding box fits `size`.
export function fit(group, size = 2) {
  const box = new THREE.Box3().setFromObject(group);
  const dim = box.getSize(new THREE.Vector3());
  const center = box.getCenter(new THREE.Vector3());
  const s = size / Math.max(dim.x, dim.y, dim.z);
  const wrapper = new THREE.Group();
  group.position.sub(center);
  wrapper.add(group);
  wrapper.scale.setScalar(s);
  return wrapper;
}
