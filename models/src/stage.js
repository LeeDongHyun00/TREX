/*
 * The lighting the models are tuned under. It mirrors the TREX motion study's
 * rig (a painted studio for reflections, a warm key from the upper left, a
 * cool fill and a rim from behind) so colours chosen here hold up there.
 */
import * as THREE from 'three';

const studios = new WeakMap();

// A soft grey studio: bright overhead, darker toward the floor, with a few
// white softboxes for the glossy coats to pick up. Made once per renderer.
function studio(renderer) {
  if (studios.has(renderer)) return studios.get(renderer);
  const canvas = document.createElement('canvas');
  canvas.width = 1024;
  canvas.height = 512;
  const c = canvas.getContext('2d');
  const sky = c.createLinearGradient(0, 0, 0, 512);
  sky.addColorStop(0, '#ffffff');
  sky.addColorStop(0.5, '#c5cbd1');
  sky.addColorStop(1, '#657082');
  c.fillStyle = sky;
  c.fillRect(0, 0, 1024, 512);
  c.fillStyle = '#ffffff';
  c.beginPath();
  c.roundRect(125, 75, 145, 245, 40);
  c.roundRect(610, 110, 240, 80, 30);
  c.fill();
  c.fillStyle = '#e8edf5';
  c.beginPath();
  c.roundRect(880, 240, 100, 160, 25);
  c.fill();
  const texture = new THREE.CanvasTexture(canvas);
  texture.mapping = THREE.EquirectangularReflectionMapping;
  texture.colorSpace = THREE.SRGBColorSpace;
  const pmrem = new THREE.PMREMGenerator(renderer);
  const env = pmrem.fromEquirectangular(texture).texture;
  texture.dispose();
  pmrem.dispose();
  studios.set(renderer, env);
  return env;
}

// shadows: false skips the key light's shadow map, e.g. when many small
// scenes share one renderer.
export function lightStage(renderer, scene, { shadows = true } = {}) {
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 0.98;
  renderer.shadowMap.enabled = shadows;
  renderer.shadowMap.type = THREE.PCFSoftShadowMap;
  scene.environment = studio(renderer);

  scene.add(new THREE.HemisphereLight(0xffffff, 0xa2b5cd, 1.1));
  const key = new THREE.DirectionalLight(0xfff4dd, 2.2);
  key.position.set(-4, 8, 7);
  key.castShadow = shadows;
  key.shadow.mapSize.set(1024, 1024);
  key.shadow.radius = 6;
  key.shadow.bias = -0.0004;
  Object.assign(key.shadow.camera, { left: -3, right: 3, top: 3, bottom: -3, near: 0.5, far: 30 });
  scene.add(key);
  const fill = new THREE.DirectionalLight(0xd8e8ff, 0.8);
  fill.position.set(4, 3, 5);
  scene.add(fill);
  const rim = new THREE.DirectionalLight(0xffffff, 1);
  rim.position.set(1, 5, -6);
  scene.add(rim);
  return { key, fill, rim };
}
