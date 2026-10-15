/*
 * Signed distance modelling for the soft, moulded shapes: clouds, the moon and
 * the dinosaur. Primitives are blended with smooth unions so they read as one
 * cast piece, colour blends across the seams the same way, and the result is
 * meshed with surface nets. Normals come from the field's gradient, so the
 * shading stays smooth at a modest triangle count.
 *
 * A shape is a function (x, y, z, out) that writes the distance into out.d
 * and a linear RGB colour into out.r/g/b. Each carries a bounding sphere so a
 * blend can skip parts that cannot reach the point being sampled.
 */
import * as THREE from 'three';

const clamp01 = (v) => (v < 0 ? 0 : v > 1 ? 1 : v);

// Linear RGB triple from any colour three.js understands (hex strings are sRGB).
export function rgb(color) {
  const c = new THREE.Color(color);
  return [c.r, c.g, c.b];
}

function shape(fn, center, radius) {
  fn.bound = { x: center[0], y: center[1], z: center[2], r: radius };
  return fn;
}

// Lower bound on a shape's distance, from its bounding sphere.
function reach(s, x, y, z) {
  const b = s.bound;
  const dx = x - b.x, dy = y - b.y, dz = z - b.z;
  return Math.sqrt(dx * dx + dy * dy + dz * dz) - b.r;
}

function paint(o, col) {
  o.r = col[0];
  o.g = col[1];
  o.b = col[2];
}

export function sphere(center, radius, color) {
  const [cx, cy, cz] = center;
  const col = rgb(color);
  return shape((x, y, z, o) => {
    const dx = x - cx, dy = y - cy, dz = z - cz;
    o.d = Math.sqrt(dx * dx + dy * dy + dz * dz) - radius;
    paint(o, col);
  }, center, radius);
}

// Rotation matrix (row-major) from XYZ Euler angles, used to bring points into
// a primitive's own frame.
function frame(euler) {
  const m = new THREE.Matrix4().makeRotationFromEuler(new THREE.Euler(euler[0], euler[1], euler[2])).invert();
  return m.elements; // column-major
}

export function ellipsoid(center, radii, color, euler = [0, 0, 0]) {
  const [cx, cy, cz] = center;
  const [rx, ry, rz] = radii;
  const e = frame(euler);
  const col = rgb(color);
  const small = Math.min(rx, ry, rz);
  return shape((x, y, z, o) => {
    const px = x - cx, py = y - cy, pz = z - cz;
    const lx = e[0] * px + e[4] * py + e[8] * pz;
    const ly = e[1] * px + e[5] * py + e[9] * pz;
    const lz = e[2] * px + e[6] * py + e[10] * pz;
    // Quilez's ellipsoid bound: exact on the surface, a good estimate near it.
    const ax = lx / rx, ay = ly / ry, az = lz / rz;
    const k0 = Math.sqrt(ax * ax + ay * ay + az * az);
    const bx = lx / (rx * rx), by = ly / (ry * ry), bz = lz / (rz * rz);
    const k1 = Math.sqrt(bx * bx + by * by + bz * bz);
    o.d = k1 < 1e-9 ? -small : (k0 * (k0 - 1)) / k1;
    paint(o, col);
  }, center, Math.max(rx, ry, rz));
}

// Cone between two spheres of different radii (Quilez's exact round cone).
export function roundCone(a, b, ra, rb, color) {
  const [ax, ay, az] = a;
  const bax = b[0] - ax, bay = b[1] - ay, baz = b[2] - az;
  const l2 = bax * bax + bay * bay + baz * baz;
  const rr = ra - rb;
  const a2 = l2 - rr * rr;
  const il2 = 1 / l2;
  const col = rgb(color);
  const mid = [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2];
  return shape((x, y, z, o) => {
    const pax = x - ax, pay = y - ay, paz = z - az;
    const yy = pax * bax + pay * bay + paz * baz;
    const zz = yy - l2;
    const qx = pax * l2 - bax * yy, qy = pay * l2 - bay * yy, qz = paz * l2 - baz * yy;
    const x2 = qx * qx + qy * qy + qz * qz;
    const y2 = yy * yy * l2;
    const z2 = zz * zz * l2;
    const k = Math.sign(rr) * rr * rr * x2;
    if (Math.sign(zz) * a2 * z2 > k) o.d = Math.sqrt(x2 + z2) * il2 - rb;
    else if (Math.sign(yy) * a2 * y2 < k) o.d = Math.sqrt(x2 + y2) * il2 - ra;
    else o.d = (Math.sqrt(x2 * a2 * il2) + yy * rr) * il2 - ra;
    paint(o, col);
  }, mid, Math.sqrt(l2) / 2 + Math.max(ra, rb));
}

export function capsule(a, b, radius, color) {
  return roundCone(a, b, radius, radius, color);
}

// Smooth union: parts melt into each other over a blend radius k, and so do
// their colours.
export function blend(k, ...parts) {
  const tmp = { d: 0, r: 0, g: 0, b: 0 };
  let cx = 0, cy = 0, cz = 0;
  for (const p of parts) {
    cx += p.bound.x / parts.length;
    cy += p.bound.y / parts.length;
    cz += p.bound.z / parts.length;
  }
  let r = 0;
  for (const p of parts) {
    r = Math.max(r, Math.hypot(p.bound.x - cx, p.bound.y - cy, p.bound.z - cz) + p.bound.r);
  }
  return shape((x, y, z, o) => {
    parts[0](x, y, z, o);
    for (let i = 1; i < parts.length; i++) {
      const p = parts[i];
      if (reach(p, x, y, z) >= o.d + k) continue;
      p(x, y, z, tmp);
      const h = clamp01(0.5 + (0.5 * (tmp.d - o.d)) / k);
      o.d = tmp.d + (o.d - tmp.d) * h - k * h * (1 - h);
      o.r = tmp.r + (o.r - tmp.r) * h;
      o.g = tmp.g + (o.g - tmp.g) * h;
      o.b = tmp.b + (o.b - tmp.b) * h;
    }
  }, [cx, cy, cz], r + k);
}

// Smooth subtraction. Where the cutter bites, the surface takes `inside` if
// given, so a carved mouth or socket can be darker than the skin around it.
export function carve(k, base, cutter, inside) {
  const tmp = { d: 0, r: 0, g: 0, b: 0 };
  const col = inside ? rgb(inside) : null;
  return shape((x, y, z, o) => {
    base(x, y, z, o);
    if (reach(cutter, x, y, z) >= k - o.d) return;
    cutter(x, y, z, tmp);
    const h = clamp01(0.5 - (0.5 * (o.d + tmp.d)) / k);
    o.d = o.d + (-tmp.d - o.d) * h + k * h * (1 - h);
    if (col) {
      o.r += (col[0] - o.r) * h;
      o.g += (col[1] - o.g) * h;
      o.b += (col[2] - o.b) * h;
    }
  }, [base.bound.x, base.bound.y, base.bound.z], base.bound.r);
}

// Smoothly trims everything below the plane y = level.
export function above(k, base, level) {
  return shape((x, y, z, o) => {
    base(x, y, z, o);
    const plane = level - y;
    const h = clamp01(0.5 - (0.5 * (plane - o.d)) / k);
    o.d = plane + (o.d - plane) * h + k * h * (1 - h);
  }, [base.bound.x, base.bound.y, base.bound.z], base.bound.r);
}

// Smoothly keeps the side of a plane where dot(normal, p - point) >= 0, e.g.
// to trim a sphere into an eyelid.
export function clip(k, base, point, normal) {
  const len = Math.hypot(normal[0], normal[1], normal[2]);
  const nx = normal[0] / len, ny = normal[1] / len, nz = normal[2] / len;
  const d0 = nx * point[0] + ny * point[1] + nz * point[2];
  return shape((x, y, z, o) => {
    base(x, y, z, o);
    const plane = d0 - (nx * x + ny * y + nz * z);
    const h = clamp01(0.5 - (0.5 * (plane - o.d)) / k);
    o.d = plane + (o.d - plane) * h + k * h * (1 - h);
  }, [base.bound.x, base.bound.y, base.bound.z], base.bound.r);
}

// Recolours a shape without changing its form: tint(x, y, z, colour) may
// adjust out.r/g/b, e.g. to shade a cloud's underside.
export function tint(base, fn) {
  return shape((x, y, z, o) => {
    base(x, y, z, o);
    fn(x, y, z, o);
  }, [base.bound.x, base.bound.y, base.bound.z], base.bound.r);
}

/*
 * Meshes a shape with naive surface nets: one vertex per grid cell the surface
 * crosses, at the mean of its edge crossings, joined into quads across every
 * crossed grid edge. Vertices are then pulled onto the surface, normals taken
 * from the gradient, and colours darkened in creases by a short occlusion probe.
 */
export function polygonize(s, { min, max, step, occlusion = 0.35 }) {
  const nx = Math.ceil((max[0] - min[0]) / step) + 1;
  const ny = Math.ceil((max[1] - min[1]) / step) + 1;
  const nz = Math.ceil((max[2] - min[2]) / step) + 1;
  const field = new Float32Array(nx * ny * nz);
  const o = { d: 0, r: 0, g: 0, b: 0 };
  const at = (i, j, k) => i + nx * (j + ny * k);
  // Sample in blocks: a block whose centre is clearly farther from the surface
  // than its own size cannot contain it, so its samples only need the sign.
  // The margin covers blends and ellipsoids being bounds, not exact distances.
  const B = 4;
  const clear = Math.sqrt(3) * (B / 2) * step * 1.5 + step;
  for (let bk = 0; bk < nz; bk += B) {
    for (let bj = 0; bj < ny; bj += B) {
      for (let bi = 0; bi < nx; bi += B) {
        const ei = Math.min(bi + B, nx), ej = Math.min(bj + B, ny), ek = Math.min(bk + B, nz);
        s(min[0] + ((bi + ei - 1) / 2) * step, min[1] + ((bj + ej - 1) / 2) * step, min[2] + ((bk + ek - 1) / 2) * step, o);
        const far = Math.abs(o.d) > clear ? o.d : 0;
        for (let k = bk; k < ek; k++) {
          for (let j = bj; j < ej; j++) {
            for (let i = bi; i < ei; i++) {
              if (far) {
                field[at(i, j, k)] = far;
              } else {
                s(min[0] + i * step, min[1] + j * step, min[2] + k * step, o);
                field[at(i, j, k)] = o.d;
              }
            }
          }
        }
      }
    }
  }

  const cells = new Int32Array((nx - 1) * (ny - 1) * (nz - 1)).fill(-1);
  const cellAt = (i, j, k) => i + (nx - 1) * (j + (ny - 1) * k);
  const corner = [
    [0, 0, 0], [1, 0, 0], [0, 1, 0], [1, 1, 0],
    [0, 0, 1], [1, 0, 1], [0, 1, 1], [1, 1, 1],
  ];
  const edges = [
    [0, 1], [2, 3], [4, 5], [6, 7],
    [0, 2], [1, 3], [4, 6], [5, 7],
    [0, 4], [1, 5], [2, 6], [3, 7],
  ];
  const v = new Float32Array(8);
  const verts = [];
  for (let k = 0; k < nz - 1; k++) {
    for (let j = 0; j < ny - 1; j++) {
      for (let i = 0; i < nx - 1; i++) {
        let inside = 0;
        for (let c = 0; c < 8; c++) {
          v[c] = field[at(i + corner[c][0], j + corner[c][1], k + corner[c][2])];
          if (v[c] < 0) inside++;
        }
        if (inside === 0 || inside === 8) continue;
        let sx = 0, sy = 0, sz = 0, n = 0;
        for (const [a, b] of edges) {
          if (v[a] < 0 === v[b] < 0) continue;
          const t = v[a] / (v[a] - v[b]);
          sx += corner[a][0] + (corner[b][0] - corner[a][0]) * t;
          sy += corner[a][1] + (corner[b][1] - corner[a][1]) * t;
          sz += corner[a][2] + (corner[b][2] - corner[a][2]) * t;
          n++;
        }
        cells[cellAt(i, j, k)] = verts.length / 3;
        verts.push(min[0] + (i + sx / n) * step, min[1] + (j + sy / n) * step, min[2] + (k + sz / n) * step);
      }
    }
  }

  const quads = [];
  const quad = (a, b, c, d) => {
    if (a < 0 || b < 0 || c < 0 || d < 0) return;
    quads.push(a, b, c, d);
  };
  for (let k = 1; k < nz - 1; k++) {
    for (let j = 1; j < ny - 1; j++) {
      for (let i = 1; i < nx - 1; i++) {
        const here = field[at(i, j, k)] < 0;
        if (here !== field[at(i + 1, j, k)] < 0) {
          quad(cells[cellAt(i, j - 1, k - 1)], cells[cellAt(i, j, k - 1)], cells[cellAt(i, j, k)], cells[cellAt(i, j - 1, k)]);
        }
        if (here !== field[at(i, j + 1, k)] < 0) {
          quad(cells[cellAt(i - 1, j, k - 1)], cells[cellAt(i, j, k - 1)], cells[cellAt(i, j, k)], cells[cellAt(i - 1, j, k)]);
        }
        if (here !== field[at(i, j, k + 1)] < 0) {
          quad(cells[cellAt(i - 1, j - 1, k)], cells[cellAt(i, j - 1, k)], cells[cellAt(i, j, k)], cells[cellAt(i - 1, j, k)]);
        }
      }
    }
  }

  // Pull each vertex onto the surface and read its normal, colour and shade.
  const count = verts.length / 3;
  const positions = new Float32Array(verts);
  const normals = new Float32Array(count * 3);
  const colors = new Float32Array(count * 3);
  const h = step * 0.5;
  const grad = (x, y, z, out) => {
    s(x + h, y, z, o); const gx1 = o.d; s(x - h, y, z, o); const gx0 = o.d;
    s(x, y + h, z, o); const gy1 = o.d; s(x, y - h, z, o); const gy0 = o.d;
    s(x, y, z + h, o); const gz1 = o.d; s(x, y, z - h, o); const gz0 = o.d;
    out[0] = gx1 - gx0; out[1] = gy1 - gy0; out[2] = gz1 - gz0;
    const len = Math.hypot(out[0], out[1], out[2]) || 1;
    out[0] /= len; out[1] /= len; out[2] /= len;
  };
  const g = [0, 0, 0];
  for (let n = 0; n < count; n++) {
    let x = positions[3 * n], y = positions[3 * n + 1], z = positions[3 * n + 2];
    for (let iter = 0; iter < 3; iter++) {
      s(x, y, z, o);
      const d = o.d;
      grad(x, y, z, g);
      x -= g[0] * d; y -= g[1] * d; z -= g[2] * d;
    }
    grad(x, y, z, g);
    positions[3 * n] = x; positions[3 * n + 1] = y; positions[3 * n + 2] = z;
    normals[3 * n] = g[0]; normals[3 * n + 1] = g[1]; normals[3 * n + 2] = g[2];
    // Occlusion: how much nearer the surface is than open space would be a
    // short way out along the normal.
    let occ = 0, w = 1;
    for (let q = 1; q <= 5; q++) {
      const dist = step * 0.8 + step * 1.6 * q;
      s(x + g[0] * dist, y + g[1] * dist, z + g[2] * dist, o);
      occ += (dist - Math.max(0, o.d)) * w;
      w *= 0.7;
    }
    const shade = 1 - occlusion * clamp01((occ / step) * 0.18);
    s(x, y, z, o);
    colors[3 * n] = o.r * shade; colors[3 * n + 1] = o.g * shade; colors[3 * n + 2] = o.b * shade;
  }

  // Split quads along the shorter diagonal and wind each triangle to face
  // along the field's gradient.
  const index = [];
  const tri = (a, b, c) => {
    const ax = positions[3 * a], ay = positions[3 * a + 1], az = positions[3 * a + 2];
    const ux = positions[3 * b] - ax, uy = positions[3 * b + 1] - ay, uz = positions[3 * b + 2] - az;
    const vx = positions[3 * c] - ax, vy = positions[3 * c + 1] - ay, vz = positions[3 * c + 2] - az;
    const fx = uy * vz - uz * vy, fy = uz * vx - ux * vz, fz = ux * vy - uy * vx;
    const nxs = normals[3 * a] + normals[3 * b] + normals[3 * c];
    const nys = normals[3 * a + 1] + normals[3 * b + 1] + normals[3 * c + 1];
    const nzs = normals[3 * a + 2] + normals[3 * b + 2] + normals[3 * c + 2];
    if (fx * nxs + fy * nys + fz * nzs >= 0) index.push(a, b, c);
    else index.push(a, c, b);
  };
  const dist2 = (a, b) => {
    const dx = positions[3 * a] - positions[3 * b], dy = positions[3 * a + 1] - positions[3 * b + 1], dz = positions[3 * a + 2] - positions[3 * b + 2];
    return dx * dx + dy * dy + dz * dz;
  };
  for (let q = 0; q < quads.length; q += 4) {
    const [a, b, c, d] = [quads[q], quads[q + 1], quads[q + 2], quads[q + 3]];
    if (dist2(a, c) < dist2(b, d)) { tri(a, b, c); tri(a, c, d); }
    else { tri(a, b, d); tri(b, c, d); }
  }

  const geometry = new THREE.BufferGeometry();
  geometry.setAttribute('position', new THREE.BufferAttribute(positions, 3));
  geometry.setAttribute('normal', new THREE.BufferAttribute(normals, 3));
  geometry.setAttribute('color', new THREE.BufferAttribute(colors, 3));
  geometry.setIndex(index);
  geometry.computeBoundingSphere();
  return geometry;
}
