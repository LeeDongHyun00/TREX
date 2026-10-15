/*
 * The download button for the closing screen: a glossy pill in the app's
 * main green sitting in a darker base, like a key you can press, with a white
 * download arrow and a "다운로드" label. downloadButton() builds the button
 * without its label and is saved as button.glb; the label is drawn into a
 * canvas, so labelButton() adds it in a browser (load the Korean font first).
 * Tapping it should open the TREX repository, TREX_URL.
 */
import * as THREE from 'three';
import { polygonize, rgb } from './sdf.js';
import { COLORS, deepen, mesh, painted, toy } from './look.js';

export const TREX_URL = 'https://github.com/LeeDongHyun00/TREX';

const WIDTH = 2.0;
const HEIGHT = 0.62;
const FONT = '"Noto Sans KR", "Apple SD Gothic Neo", "Malgun Gothic", sans-serif';
const smooth = (t) => t * t * (3 - 2 * t);
const clamp01 = (v) => Math.min(1, Math.max(0, v));

// A stadium (rounded-end bar) of width w and height h, depth d, with edges
// rounded by r, facing +Z. Shaded from a lighter top to the given colour.
function pill(w, h, d, r, color, light) {
  const low = rgb(color), high = rgb(light);
  const field = (x, y, z, o) => {
    const q = Math.abs(x) - (w / 2 - h / 2);
    const flat = (q > 0 ? Math.hypot(q, y) : Math.abs(y)) - (h / 2 - r);
    const dz = Math.abs(z) - (d / 2 - r);
    o.d = Math.hypot(Math.max(flat, 0), Math.max(dz, 0)) + Math.min(Math.max(flat, dz), 0) - r;
    const t = smooth(clamp01(0.5 + y / h + z / (d * 2)));
    o.r = low[0] + (high[0] - low[0]) * t;
    o.g = low[1] + (high[1] - low[1]) * t;
    o.b = low[2] + (high[2] - low[2]) * t;
  };
  field.bound = { x: 0, y: 0, z: 0, r: w / 2 + 0.05 };
  const e = [w / 2 + 0.03, h / 2 + 0.03, d / 2 + 0.03];
  // Built ahead of time, so the grid can be fine; the build simplifies
  // what the flat stretches do not need.
  return polygonize(field, { min: e.map((v) => -v), max: e, step: 0.0125, occlusion: 0.15 });
}

// The download glyph: an arrow pointing down into a tray.
function glyph() {
  const shapes = [];
  const arrow = new THREE.Shape();
  arrow.moveTo(-0.04, 0.2);
  arrow.lineTo(0.04, 0.2);
  arrow.lineTo(0.04, 0.03);
  arrow.lineTo(0.11, 0.03);
  arrow.lineTo(0, -0.08);
  arrow.lineTo(-0.11, 0.03);
  arrow.lineTo(-0.04, 0.03);
  arrow.closePath();
  shapes.push(arrow);
  const tray = new THREE.Shape();
  tray.moveTo(-0.17, -0.06);
  tray.lineTo(-0.12, -0.06);
  tray.lineTo(-0.12, -0.12);
  tray.lineTo(0.12, -0.12);
  tray.lineTo(0.12, -0.06);
  tray.lineTo(0.17, -0.06);
  tray.lineTo(0.17, -0.17);
  tray.lineTo(-0.17, -0.17);
  tray.closePath();
  shapes.push(tray);
  const geometry = new THREE.ExtrudeGeometry(shapes, { depth: 0.02, bevelEnabled: true, bevelThickness: 0.012, bevelSize: 0.01, bevelSegments: 3, curveSegments: 4 });
  geometry.computeVertexNormals();
  return geometry;
}

function labelTexture(text) {
  const canvas = document.createElement('canvas');
  canvas.width = 512;
  canvas.height = 192;
  const c = canvas.getContext('2d');
  c.fillStyle = '#ffffff';
  c.font = `700 112px ${FONT}`;
  c.textAlign = 'left';
  c.textBaseline = 'middle';
  c.fillText(text, 8, 100);
  const texture = new THREE.CanvasTexture(canvas);
  texture.colorSpace = THREE.SRGBColorSpace;
  texture.anisotropy = 4;
  return texture;
}

export function downloadButton() {
  const group = new THREE.Group();
  group.name = 'download-button';
  // The base is the key's socket; the cap is the part that moves.
  const base = mesh(pill(WIDTH + 0.12, HEIGHT + 0.12, 0.16, 0.06, deepen(COLORS.calories, 0.2).getStyle(), deepen(COLORS.calories, 0.12).getStyle()), painted({ roughness: 0.45, clearcoat: 0.4 }), 'button-base');
  base.position.z = -0.08;
  const cap = new THREE.Group();
  cap.name = 'button-cap';
  // The face reads as the app's main green (#759848) under the studio light.
  cap.add(mesh(pill(WIDTH, HEIGHT, 0.2, 0.08, deepen(COLORS.calories, 0.12).getStyle(), deepen(COLORS.calories, 0.02).getStyle()), painted({ roughness: 0.28, clearcoat: 1, clearcoatRoughness: 0.08 }), 'button-face'));
  const icon = mesh(glyph(), toy('#ffffff', { roughness: 0.3, clearcoat: 0.6 }), 'button-icon');
  icon.position.set(-0.6, 0.0, 0.1);
  cap.add(icon);
  group.add(base, cap);
  return group;
}

// Adds the label to a button from downloadButton() or button.glb.
export function labelButton(button, label = '다운로드') {
  const text = new THREE.Mesh(
    new THREE.PlaneGeometry(1.2, 0.45),
    new THREE.MeshBasicMaterial({ map: labelTexture(label), transparent: true, alphaTest: 0.05, toneMapped: false }),
  );
  text.name = 'button-label';
  text.position.set(0.22, -0.01, 0.102);
  button.getObjectByName('button-cap').add(text);
  return button;
}

// Pushes the cap into its base, amount 0 (up) to 1 (fully pressed).
export function pressButton(button, amount) {
  const cap = button.getObjectByName('button-cap');
  if (cap) cap.position.z = -0.06 * clamp01(amount);
}
