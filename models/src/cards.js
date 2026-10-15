/*
 * Recommendation cards for the night scene: a glossy rounded card per
 * exercise, with its name, the muscles it works and the action, as the app's
 * "추천 운동" tab lists them. Labels are drawn into a canvas, so cards are
 * built at runtime in a browser rather than shipped as .glb; load the page's
 * Korean font before building them, or the canvas falls back to another face.
 */
import * as THREE from 'three';
import { RoundedBoxGeometry } from 'three/addons/geometries/RoundedBoxGeometry.js';
import { COLORS, mesh, toy } from './look.js';

const WIDTH = 1.6;
const HEIGHT = 0.74;
const DEPTH = 0.07;
const RADIUS = 0.12;
const FONT = '"Noto Sans KR", "Apple SD Gothic Neo", "Malgun Gothic", sans-serif';

function labelTexture({ name, muscles, action, accent }) {
  const scale = 512 / WIDTH;
  const canvas = document.createElement('canvas');
  canvas.width = 512;
  canvas.height = Math.round(HEIGHT * scale);
  const c = canvas.getContext('2d');
  c.fillStyle = '#ffffff';
  c.fillRect(0, 0, canvas.width, canvas.height);
  const left = 34;
  c.fillStyle = COLORS.ink;
  c.font = `700 64px ${FONT}`;
  c.textBaseline = 'alphabetic';
  c.fillText(name, left, 86);
  c.fillStyle = '#5e6754';
  c.font = `500 36px ${FONT}`;
  c.fillText(muscles, left, 142);
  // The action, worded and coloured like the app's text button.
  c.fillStyle = accent;
  c.font = `700 34px ${FONT}`;
  c.fillText(action, left, 204);
  const texture = new THREE.CanvasTexture(canvas);
  texture.colorSpace = THREE.SRGBColorSpace;
  texture.anisotropy = 4;
  return texture;
}

export function routineCard({ name, muscles, action = '운동 목록에 추가', accent = COLORS.calories } = {}) {
  const group = new THREE.Group();
  group.name = `card-${name}`;
  const body = mesh(new RoundedBoxGeometry(WIDTH, HEIGHT, DEPTH, 6, RADIUS), toy('#ffffff', { roughness: 0.3, clearcoat: 1, clearcoatRoughness: 0.08 }), 'card');
  group.add(body);
  // The label sits a hair in front of the face, inset from the rounded edge.
  if (typeof document !== 'undefined' && name) {
    const face = new THREE.Mesh(
      new THREE.PlaneGeometry(WIDTH - RADIUS * 0.6, HEIGHT - RADIUS * 0.6),
      new THREE.MeshStandardMaterial({ map: labelTexture({ name, muscles, action, accent }), roughness: 0.5 }),
    );
    face.name = 'card-label';
    face.position.z = DEPTH / 2 + 0.001;
    group.add(face);
  }
  return group;
}

// The night scene's three picks after a day of squats: work that overlaps
// least with the legs, as the app's recommendation rule would choose.
export const NIGHT_PICKS = [
  { name: '푸쉬업', muscles: '가슴 · 팔 뒤쪽' },
  { name: '플랭크', muscles: '복직근 · 옆구리' },
  { name: '크런치', muscles: '복직근 · 옆구리' },
];

// The three cards fanned out, the first in front.
export function routineFan(picks = NIGHT_PICKS) {
  const group = new THREE.Group();
  group.name = 'routine-cards';
  picks.forEach((pick, i) => {
    const card = routineCard(pick);
    card.position.set(i * 0.12, -i * 0.84, -i * 0.12);
    card.rotation.set(-0.05, -0.18, (i - 1) * -0.05);
    group.add(card);
  });
  return group;
}
