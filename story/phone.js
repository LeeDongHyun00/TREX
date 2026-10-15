/*
 * The small phones of the evening and night scenes, built in 3D so they can
 * stand turned toward the coach, and the TREX screens drawn on them. Screens
 * are canvases laid out in design pixels on a 110 x 236 screen; load the
 * Korean web font (SCREEN_TEXT) before drawing them.
 */
import * as THREE from 'three';
import { RoundedBoxGeometry } from 'three/addons/geometries/RoundedBoxGeometry.js';
import { toy } from '../models/src/look.js';
import { NIGHT_PICKS } from '../models/src/cards.js';

export const FONT = '"Noto Sans KR", "Apple SD Gothic Neo", "Malgun Gothic", sans-serif';
export const PHONE = { width: 120, height: 246, depth: 9, radius: 22, bezel: 5 };
const SCREEN = { width: PHONE.width - 2 * PHONE.bezel, height: PHONE.height - 2 * PHONE.bezel };
// Where the camera's picture sits on the coach screen, and the posture cue.
export const LIVE = { x: 9, y: 20, w: 92, h: 116 };
export const WARN = { x: 9, y: 196, w: 92, h: 30 };
// The body map on the muscle screen.
export const MAP = { x: 9, y: 48, w: 92, h: 96 };

// Every string the screens draw, so its Korean subsets can be loaded first.
export const SCREEN_TEXT = [
  'COACH', '기본 스쿼트', '1 / 3 세트', '0123456789', '남음 · / 10', '가슴을 들고', '몸통을 세우세룡',
  '근육 피로도', '기록 기반 추정 · 0–100점', '근육 상태', '추천 운동', '앞 허벅지', '엉덩이', '점', '추가',
  ...NIGHT_PICKS.map(({ name }) => name),
].join('');

// A rounded rectangle centred on the origin.
export function roundedRect(w, h, r) {
  const shape = new THREE.Shape();
  const x = w / 2, y = h / 2;
  shape.moveTo(-x + r, -y);
  shape.lineTo(x - r, -y);
  shape.absarc(x - r, -y + r, r, -Math.PI / 2, 0);
  shape.lineTo(x, y - r);
  shape.absarc(x - r, y - r, r, 0, Math.PI / 2);
  shape.lineTo(-x + r, y);
  shape.absarc(-x + r, y - r, r, Math.PI / 2, Math.PI);
  shape.lineTo(-x, -y + r);
  shape.absarc(-x + r, -y + r, r, Math.PI, Math.PI * 1.5);
  return shape;
}

// A flat rounded rectangle whose texture spans it edge to edge.
function panel(w, h, r) {
  const geometry = new THREE.ShapeGeometry(roundedRect(w, h, r), 10);
  const { position, uv } = geometry.attributes;
  for (let i = 0; i < uv.count; i++) uv.setXY(i, position.getX(i) / w + 0.5, position.getY(i) / h + 0.5);
  return geometry;
}

// A canvas texture drawn in design pixels at four times the resolution;
// redraw(...args) calls draw(context, ...args) on a cleared canvas.
export function canvasTexture(width, height, draw, scale = 4) {
  const canvas = document.createElement('canvas');
  canvas.width = Math.round(width * scale);
  canvas.height = Math.round(height * scale);
  const c = canvas.getContext('2d');
  const texture = new THREE.CanvasTexture(canvas);
  texture.colorSpace = THREE.SRGBColorSpace;
  texture.anisotropy = 4;
  const redraw = (...args) => {
    c.setTransform(scale, 0, 0, scale, 0, 0);
    c.clearRect(0, 0, width, height);
    draw(c, ...args);
    texture.needsUpdate = true;
  };
  return { texture, redraw };
}

// A whole screen drawn once, by draw(context, ...args), as a texture: a phone
// switches between screens drawn ahead (see miniPhone) rather than redrawing
// one, which would upload it again in the middle of the story.
export function screenTexture(draw, ...args) {
  const { texture, redraw } = canvasTexture(SCREEN.width, SCREEN.height, draw);
  redraw(...args);
  return texture;
}

// Screen pixels show as drawn, outside the scene's lighting.
export function flat(texture, options = {}) {
  return new THREE.MeshBasicMaterial({ map: texture, toneMapped: false, ...options });
}

/*
 * A phone in design pixels, centred on its origin and facing +Z, showing
 * `screen` (a texture of the whole screen). add(material, x, y, w, h, r)
 * lays a rounded panel over part of the screen, from the screen's top left,
 * each in front of the last: a live picture, an overlay, a fading cue.
 * glass is the screen's material: set its map to show another screen.
 */
export function miniPhone(screen) {
  const { width: w, height: h, depth: d, radius: r, bezel: b } = PHONE;
  const group = new THREE.Group();
  const edge = 2.6;
  const body = new THREE.ExtrudeGeometry(roundedRect(w - 2 * edge, h - 2 * edge, r - edge), {
    depth: d - 2 * edge, bevelEnabled: true, bevelThickness: edge, bevelSize: edge, bevelSegments: 4, curveSegments: 12,
  });
  body.translate(0, 0, -(d - 2 * edge) / 2);
  group.add(new THREE.Mesh(body, toy('#1f2429', { roughness: 0.3, metalness: 0.35, clearcoat: 0.8, clearcoatRoughness: 0.1 })));
  // Volume keys on the edge that turns toward the viewer.
  const steel = toy('#4a535c', { roughness: 0.3, metalness: 0.6, clearcoat: 0.4 });
  for (const [y, length] of [[62, 30], [30, 18]]) {
    const key = new THREE.Mesh(new RoundedBoxGeometry(2.4, length, 3.4, 2, 1), steel);
    key.position.set(w / 2 + 0.7, y, 0);
    group.add(key);
  }
  const glass = new THREE.Mesh(panel(SCREEN.width, SCREEN.height, r - b), flat(screen));
  glass.position.z = d / 2 + 0.3;
  group.add(glass);
  let layer = 0;
  const add = (material, x, y, pw, ph, pr) => {
    const m = new THREE.Mesh(panel(pw, ph, pr), material);
    layer += 1;
    m.position.set(x + pw / 2 - SCREEN.width / 2, SCREEN.height / 2 - (y + ph / 2), d / 2 + 0.3 + 0.35 * layer);
    m.renderOrder = layer;
    group.add(m);
    return m;
  };
  return { group, add, glass: glass.material };
}

function island(c, color) {
  c.fillStyle = color;
  c.beginPath();
  c.roundRect(40, 6, 30, 9, 4.5);
  c.fill();
}

// The coach screen: the camera's picture (drawn over it) and the set's count.
export function coachScreen(c, done) {
  c.fillStyle = '#f5f7f1';
  c.fillRect(0, 0, SCREEN.width, SCREEN.height);
  island(c, '#1f2429');
  c.fillStyle = '#14181c';
  c.beginPath();
  c.roundRect(LIVE.x, LIVE.y, LIVE.w, LIVE.h, 10);
  c.fill();
  c.fillStyle = '#1f2618';
  c.textBaseline = 'alphabetic';
  c.font = `500 8.4px ${FONT}`;
  c.textAlign = 'left';
  c.fillText('기본 스쿼트', 9, 151);
  c.textAlign = 'right';
  c.fillText('1 / 3 세트', 101, 151);
  c.font = `800 26px ${FONT}`;
  c.textAlign = 'left';
  c.fillText(String(10 - done), 8, 179);
  c.font = `500 8.4px ${FONT}`;
  c.textAlign = 'right';
  c.fillText(`남음 · ${done} / 10`, 101, 178);
  // A dot per repetition of the set.
  for (let i = 0; i < 10; i++) {
    c.fillStyle = i < done ? '#759848' : '#dde3d4';
    c.beginPath();
    c.arc(12 + i * 9.6, 188, 2.6, 0, Math.PI * 2);
    c.fill();
  }
}

// The posture cue, as the app shows it under the count.
export function warnPanel(c) {
  c.fillStyle = '#ffe3e3';
  c.beginPath();
  c.roundRect(0, 0, WARN.w, WARN.h, 7);
  c.fill();
  c.fillStyle = '#c65454';
  c.font = `700 8.4px ${FONT}`;
  c.textAlign = 'center';
  c.textBaseline = 'alphabetic';
  c.fillText('가슴을 들고', WARN.w / 2, 12.5);
  c.fillText('몸통을 세우세룡', WARN.w / 2, 24);
}

export function coachChip(c) {
  c.fillStyle = '#759848';
  c.beginPath();
  c.roundRect(6, 6, 31, 11, 4);
  c.fill();
  c.fillStyle = '#ffffff';
  c.font = `800 7px ${FONT}`;
  c.textAlign = 'center';
  c.textBaseline = 'middle';
  c.fillText('COACH', 21.5, 11.8);
}

// The correction over the camera's picture: a ring on the faulty joint at
// (x, y) and an arrow from it that swings out round the body and up, the way
// the trunk should rise, in the app's red with a white keyline so it reads
// over the picture.
export function correction(c, [x, y]) {
  const stroke = (draw, pairs) => {
    for (const [color, width] of pairs) {
      c.beginPath();
      draw();
      c.strokeStyle = color;
      c.lineWidth = width;
      c.stroke();
    }
  };
  const bend = [x + 32, y - 4];
  const turn = [x + 40, y - 28];
  const end = [x + 30, y - 46];
  c.lineCap = 'round';
  c.lineJoin = 'round';
  stroke(() => {
    c.moveTo(x + 6, y - 1);
    c.bezierCurveTo(...bend, ...turn, ...end);
  }, [['rgba(255, 255, 255, 0.9)', 5.4], ['#ff5a5a', 3.2]]);
  stroke(() => c.arc(x, y, 4.5, 0, Math.PI * 2), [['rgba(255, 255, 255, 0.9)', 3.2], ['#ff5a5a', 2.2]]);
  // The head points on along the curve's last stretch.
  const dx = end[0] - turn[0], dy = end[1] - turn[1], length = Math.hypot(dx, dy);
  const ux = dx / length, uy = dy / length;
  c.beginPath();
  c.moveTo(end[0] + ux * 8, end[1] + uy * 8);
  c.lineTo(end[0] - uy * 6.5 - ux * 2, end[1] + ux * 6.5 - uy * 2);
  c.lineTo(end[0] + uy * 6.5 - ux * 2, end[1] - ux * 6.5 - uy * 2);
  c.closePath();
  c.fillStyle = '#ff5a5a';
  c.fill();
  c.strokeStyle = '#ffffff';
  c.lineWidth = 1.4;
  c.stroke();
}

// The muscle screen, on its muscle state tab or, with picks, on the
// recommendations.
export function fatigueScreen(c, picks) {
  c.fillStyle = '#222527';
  c.fillRect(0, 0, SCREEN.width, SCREEN.height);
  island(c, '#0e1012');
  c.textBaseline = 'alphabetic';
  c.textAlign = 'left';
  c.fillStyle = '#eef1e8';
  c.font = `700 10.5px ${FONT}`;
  c.fillText('근육 피로도', 9, 33);
  c.fillStyle = '#a8b0a0';
  c.font = `500 7px ${FONT}`;
  c.fillText('기록 기반 추정 · 0–100점', 9, 43);
  c.fillStyle = '#2b2f31';
  c.beginPath();
  c.roundRect(MAP.x, MAP.y, MAP.w, MAP.h, 10);
  c.fill();
  [['근육 상태', 9], ['추천 운동', 57]].forEach(([label, x], i) => {
    const on = picks ? i === 1 : i === 0;
    c.fillStyle = on ? '#759848' : '#34393c';
    c.beginPath();
    c.roundRect(x, 152, 44, 15, 7.5);
    c.fill();
    c.fillStyle = on ? '#ffffff' : '#b9c0b2';
    c.font = `700 7px ${FONT}`;
    c.textAlign = 'center';
    c.fillText(label, x + 22, 162.4);
  });
  c.textAlign = 'left';
  if (picks) {
    NIGHT_PICKS.forEach(({ name }, i) => {
      const y = 176 + i * 19;
      c.fillStyle = '#2b2f31';
      c.beginPath();
      c.roundRect(9, y, 92, 15, 5);
      c.fill();
      c.fillStyle = '#eef1e8';
      c.font = `700 7.4px ${FONT}`;
      c.fillText(name, 15, y + 10.4);
      c.fillStyle = '#c7e26b';
      c.font = `700 6.6px ${FONT}`;
      c.textAlign = 'right';
      c.fillText('추가', 96, y + 10.2);
      c.textAlign = 'left';
    });
    return;
  }
  // The muscles under the most load after the evening's squats.
  [['앞 허벅지', 39], ['엉덩이', 31]].forEach(([name, score], i) => {
    const y = 180 + i * 20;
    c.fillStyle = '#eef1e8';
    c.font = `500 7.2px ${FONT}`;
    c.fillText(name, 9, y);
    c.textAlign = 'right';
    c.fillStyle = '#c7e26b';
    c.font = `700 7.2px ${FONT}`;
    c.fillText(`${score}점`, 101, y);
    c.textAlign = 'left';
    c.fillStyle = '#34393c';
    c.beginPath();
    c.roundRect(9, y + 4, 92, 4, 2);
    c.fill();
    c.fillStyle = '#c7e26b';
    c.beginPath();
    c.roundRect(9, y + 4, (92 * score) / 100, 4, 2);
    c.fill();
  });
}

// The label on the body map's most loaded muscle.
export function mapChip(c) {
  c.fillStyle = 'rgba(20, 24, 26, 0.72)';
  c.beginPath();
  c.roundRect(19, 79, 54, 13, 6.5);
  c.fill();
  c.fillStyle = '#c7e26b';
  c.font = `700 6.8px ${FONT}`;
  c.textAlign = 'center';
  c.textBaseline = 'middle';
  c.fillText('앞 허벅지 39점', 46, 85.8);
}
