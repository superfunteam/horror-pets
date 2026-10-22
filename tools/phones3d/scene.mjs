// The phones3d scene: smartphones with gameplay on their screens, lit for a dark horror background and drawn to a
// transparent canvas. scene.html loads it in headless Chrome and render.mjs drives it through window.phones3d:
//   init(width, height)            -> the WebGL renderer's name
//   frame(spec, index, nextScreens) draws one frame and hands its PNG to the server as POST /out/<index>
//   flush()                        waits for the PNGs still being encoded or written
//
// A phone is 16.0 x 7.5 x 0.9 (roughly cm), centered on its position, long axis along X, screen facing +Z. The front
// camera, the camera bar on the back and the buttons sit at the -X end, the USB-C port at the +X end.
import * as THREE from 'three';
import { RoomEnvironment } from 'three/addons/environments/RoomEnvironment.js';
import { RoundedBoxGeometry } from 'three/addons/geometries/RoundedBoxGeometry.js';
import { mergeGeometries, mergeVertices } from 'three/addons/utils/BufferGeometryUtils.js';

const W = 16.0, H = 7.5, D = 0.9, CORNER = 1.1, EDGE = 0.17;
const GLASS = 0.06;  // front and back glass thickness, half of it sunk into the body
const SCREEN_W = 15.2, SCREEN_H = 6.84, SCREEN_CORNER = 0.72;
const FRONT = D / 2 + GLASS / 2;  // the front glass surface
const BACK = -FRONT;
const BUMP = 0.2;  // how far the camera bar stands off the back glass
const BAR_X = -W / 2 + 2.15, BAR_W = 2.0;

const DEFAULT_CAMERA = { fov: 30, position: [0, 0, 60], lookAt: [0, 0, 0] };
const DEFAULT_RIM = [180, 40, 110];
const TEXTURE_CACHE = 8;
const ENVIRONMENT_CACHE = 4;
const IN_FLIGHT = 4;  // PNGs encoding or uploading at once

let renderer, scene, camera, rimLights, materials, maxAnisotropy, pmrem, studio;
const phones = [];
const environments = new Map();  // rim color -> PMREM render target, oldest first
const textures = new Map();  // path -> Promise<Texture>, oldest first
const inFlight = new Set();
let failure = null;

function roundedRect(w, h, r) {
  const s = new THREE.Shape(), x = -w / 2, y = -h / 2;
  s.moveTo(x + r, y);
  s.lineTo(x + w - r, y);
  s.quadraticCurveTo(x + w, y, x + w, y + r);
  s.lineTo(x + w, y + h - r);
  s.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
  s.lineTo(x + r, y + h);
  s.quadraticCurveTo(x, y + h, x, y + h - r);
  s.lineTo(x, y + r);
  s.quadraticCurveTo(x, y, x + r, y);
  return s;
}

// A rounded-rectangle slab w x h x depth centered on the origin, its edges rounded by [edge] all round. Vertices are
// welded so the rounded edges shade smoothly instead of in facets.
function slab(w, h, r, depth, edge, curveSegments = 12) {
  const g = new THREE.ExtrudeGeometry(roundedRect(w - 2 * edge, h - 2 * edge, Math.max(r - edge, 0.01)), {
    depth: depth - 2 * edge, bevelEnabled: true, bevelThickness: edge, bevelSize: edge,
    bevelSegments: 6, curveSegments,
  });
  g.translate(0, 0, -(depth / 2 - edge));
  g.deleteAttribute('normal');
  g.deleteAttribute('uv');
  const welded = mergeVertices(g, 1e-4);
  welded.computeVertexNormals();
  return welded;
}

// A flat rounded rectangle facing +Z with UVs spanning 0..1 over its bounds.
function plate(w, h, r, curveSegments = 12) {
  const g = new THREE.ShapeGeometry(roundedRect(w, h, r), curveSegments);
  const pos = g.attributes.position, uv = g.attributes.uv;
  for (let i = 0; i < pos.count; i++) uv.setXY(i, pos.getX(i) / w + 0.5, pos.getY(i) / h + 0.5);
  return g;
}

function disc(radius, inner = 0) {
  return inner ? new THREE.RingGeometry(inner, radius, 48) : new THREE.CircleGeometry(radius, 48);
}

function mesh(geometry, material, x = 0, y = 0, z = 0) {
  const m = new THREE.Mesh(geometry, material);
  m.position.set(x, y, z);
  return m;
}

function makeMaterials() {
  const physical = (o) => new THREE.MeshPhysicalMaterial(o);
  return {
    frame: physical({ color: 0x34363b, metalness: 1, roughness: 0.3 }),
    visor: physical({ color: 0x5a5d63, metalness: 1, roughness: 0.42 }),
    button: physical({ color: 0x3a3c41, metalness: 1, roughness: 0.26 }),
    frontGlass: physical({ color: 0x060607, roughness: 0.05, clearcoat: 1, clearcoatRoughness: 0.03 }),
    backGlass: physical({ color: 0x0c0d10, roughness: 0.5, specularIntensity: 0.4, clearcoat: 0.15,
      clearcoatRoughness: 0.4 }),
    // Black, additive: lays only the glass's reflections over the screen image.
    // Faint, so a softbox sweeping across the glass never washes out the gameplay under it.
    screenGlass: physical({ color: 0x000000, roughness: 0.06, transparent: true, depthWrite: false,
      blending: THREE.AdditiveBlending, specularIntensity: 0.035 }),
    lensRing: physical({ color: 0x8b8e95, metalness: 1, roughness: 0.18 }),
    lensGlass: physical({ color: 0x020306, roughness: 0.04, clearcoat: 1, clearcoatRoughness: 0.02,
      iridescence: 1, iridescenceIOR: 1.8, iridescenceThicknessRange: [250, 450] }),
    lensCore: physical({ color: 0x0a0e1c, roughness: 0.1, clearcoat: 1 }),
    lensTrim: physical({ color: 0x2a2d36, metalness: 0.6, roughness: 0.3 }),
    flash: physical({ color: 0xb8ad8c, roughness: 0.3, clearcoat: 1 }),
    hole: new THREE.MeshBasicMaterial({ color: 0x000000 }),
    antenna: physical({ color: 0x1a1b1f, roughness: 0.6 }),
  };
}

function frontParts(m, screen) {
  const parts = [
    mesh(slab(W - 2 * EDGE, H - 2 * EDGE, CORNER - EDGE, GLASS, 0.028), m.frontGlass, 0, 0, D / 2),
    mesh(plate(SCREEN_W, SCREEN_H, SCREEN_CORNER, 16), screen, 0, 0, FRONT + 0.003),
    mesh(plate(SCREEN_W, SCREEN_H, SCREEN_CORNER, 16), m.screenGlass, 0, 0, FRONT + 0.006),
  ];
  const punchX = -SCREEN_W / 2 + 0.55;
  parts.push(mesh(disc(0.17), m.hole, punchX, 0, FRONT + 0.009));
  parts.push(mesh(disc(0.07), m.lensCore, punchX, 0, FRONT + 0.012));
  parts[1].renderOrder = 1;
  parts[2].renderOrder = 2;
  return parts;
}

// The back faces -Z, so its flat parts are turned half a turn about Y.
function backParts(m) {
  const flip = (g) => g.rotateY(Math.PI);
  const parts = [mesh(slab(W - 2 * EDGE, H - 2 * EDGE, CORNER - EDGE, GLASS, 0.028), m.backGlass, 0, 0, -D / 2)];

  const barDepth = BUMP + 0.04;
  const bar = slab(BAR_W, H - 2 * EDGE - 0.12, 0.6, barDepth, 0.08, 16);
  parts.push(mesh(bar, m.visor, BAR_X, 0, BACK - barDepth / 2 + 0.04));
  const barTop = BACK - BUMP;
  parts.push(mesh(slab(1.28, 3.7, 0.64, 0.04, 0.015, 16), m.frontGlass, BAR_X, 1.05, barTop));

  for (const y of [1.95, 0.15]) {
    const z = barTop - 0.02;
    const ring = new THREE.CylinderGeometry(0.5, 0.5, 0.05, 48).rotateX(Math.PI / 2);
    parts.push(mesh(ring, m.lensRing, BAR_X, y, z - 0.025));
    parts.push(mesh(flip(disc(0.42)), m.lensGlass, BAR_X, y, z - 0.052));
    parts.push(mesh(flip(disc(0.2, 0.17)), m.lensTrim, BAR_X, y, z - 0.054));
    parts.push(mesh(flip(disc(0.17)), m.lensCore, BAR_X, y, z - 0.054));
  }
  parts.push(mesh(flip(disc(0.24, 0.2)), m.lensTrim, BAR_X, -1.55, barTop - 0.002));
  parts.push(mesh(flip(disc(0.2)), m.flash, BAR_X, -1.55, barTop - 0.002));
  parts.push(mesh(flip(disc(0.05)), m.hole, BAR_X, -2.45, barTop - 0.002));
  return parts;
}

// Power and volume on the +Y long edge near the camera end; antenna lines across the frame; USB-C and speaker
// holes in the +X short edge.
function edgeParts(m) {
  const parts = [
    mesh(new RoundedBoxGeometry(1.1, 0.24, 0.34, 3, 0.08), m.button, -4.0, H / 2 + 0.02, 0),
    mesh(new RoundedBoxGeometry(2.4, 0.24, 0.34, 3, 0.08), m.button, -1.4, H / 2 + 0.02, 0),
  ];
  const band = D - 2 * EDGE;
  for (const x of [-W / 2 + 1.5, W / 2 - 1.5]) {
    for (const side of [1, -1]) {
      const g = new THREE.PlaneGeometry(0.09, band).rotateX(-side * Math.PI / 2);
      parts.push(mesh(g, m.antenna, x, side * (H / 2 + 0.001), 0));
    }
  }
  const onEnd = (g) => g.rotateY(Math.PI / 2);
  parts.push(mesh(onEnd(new THREE.ShapeGeometry(roundedRect(0.27, 0.86, 0.135), 8)), m.hole, W / 2 + 0.002, 0, 0));
  const holes = [];
  for (const side of [1, -1]) {
    for (let i = 0; i < 5; i++) holes.push(onEnd(disc(0.045)).translate(0, side * (1.2 + i * 0.2), 0));
  }
  parts.push(mesh(mergeGeometries(holes), m.hole, W / 2 + 0.002, 0, 0));
  return parts;
}

function makePhone() {
  const screen = new THREE.MeshBasicMaterial({ color: 0xffffff, toneMapped: false });
  const group = new THREE.Group();
  group.add(mesh(slab(W, H, CORNER, D, EDGE, 24), materials.frame));
  group.add(...frontParts(materials, screen), ...backParts(materials), ...edgeParts(materials));
  group.userData.screen = screen;
  scene.add(group);
  return group;
}

// Reflections come from three's RoomEnvironment with its walls darkened, so its light boxes read as studio softboxes
// on black, plus rim-colored strips behind the phones that light their edges at grazing angles.
function makeStudio() {
  const room = new RoomEnvironment();
  room.traverse((o) => {
    if (o.isMesh && o.material.isMeshStandardMaterial) o.material.color.setScalar(0.14);
  });
  const glow = new THREE.MeshBasicMaterial();
  const strip = new THREE.BoxGeometry();
  for (const [x, y, z, sx, sy, sz] of [[-13, 12, -12, 1.2, 20, 0.2], [13, 12, -12, 1.2, 20, 0.2],
    [0, 23, -11, 22, 0.2, 1.5], [0, 2, -13, 22, 1.0, 0.2]]) {
    const m = mesh(strip, glow, x, y, z);
    m.scale.set(sx, sy, sz);
    room.add(m);
  }
  room.userData.glow = glow;
  return room;
}

function environment(rim) {
  const key = rim.join(',');
  let env = environments.get(key);
  if (env) {
    environments.delete(key);
  } else {
    const glow = studio.userData.glow.color;
    glow.setRGB(rim[0] / 255, rim[1] / 255, rim[2] / 255, THREE.SRGBColorSpace).multiplyScalar(25);
    env = pmrem.fromScene(studio, 0.03);
  }
  environments.set(key, env);
  while (environments.size > ENVIRONMENT_CACHE) {
    const [oldest, evicted] = environments.entries().next().value;
    environments.delete(oldest);
    evicted.dispose();
  }
  return env.texture;
}

function makeLights() {
  const key = new THREE.DirectionalLight(0xfff1e6, 1.6);
  key.position.set(-15, 25, 40);
  scene.add(key);
  const rims = [[-35, 10, -15, 8], [35, 5, -15, 8], [0, 30, -15, 4]].map(([x, y, z, intensity]) => {
    const light = new THREE.DirectionalLight(0xffffff, intensity);
    light.position.set(x, y, z);
    scene.add(light);
    return light;
  });
  return rims;
}

export function init(width, height) {
  renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, preserveDrawingBuffer: true,
    powerPreference: 'high-performance' });
  renderer.setPixelRatio(1);
  renderer.setSize(width, height, false);
  renderer.setClearColor(0x000000, 0);
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  maxAnisotropy = renderer.capabilities.getMaxAnisotropy();

  scene = new THREE.Scene();
  pmrem = new THREE.PMREMGenerator(renderer);
  studio = makeStudio();
  camera = new THREE.PerspectiveCamera(30, width / height, 1, 5000);
  materials = makeMaterials();
  rimLights = makeLights();

  const gl = renderer.getContext();
  const info = gl.getExtension('WEBGL_debug_renderer_info');
  return info ? gl.getParameter(info.UNMASKED_RENDERER_WEBGL) : gl.getParameter(gl.RENDERER);
}

// Cover-fit: crop the image's center to the screen's aspect by scaling its UVs, never stretching it.
function coverFit(texture, imageAspect) {
  const screenAspect = SCREEN_W / SCREEN_H;
  if (imageAspect > screenAspect) texture.repeat.set(screenAspect / imageAspect, 1);
  else texture.repeat.set(1, imageAspect / screenAspect);
  texture.offset.set((1 - texture.repeat.x) / 2, (1 - texture.repeat.y) / 2);
}

async function loadTexture(file) {
  const res = await fetch('/fs/' + encodeURIComponent(file));
  if (!res.ok) throw new Error(`cannot read screen ${file}: ${await res.text()}`);
  const bitmap = await createImageBitmap(await res.blob(), {
    imageOrientation: 'flipY', premultiplyAlpha: 'none', colorSpaceConversion: 'none' })
    .catch((e) => { throw new Error(`cannot decode screen ${file}: ${e.message}`); });
  const texture = new THREE.Texture(bitmap);
  texture.flipY = false;  // the bitmap is already flipped; WebGL ignores flipY for ImageBitmaps
  texture.colorSpace = THREE.SRGBColorSpace;
  texture.anisotropy = maxAnisotropy;
  texture.needsUpdate = true;
  coverFit(texture, bitmap.width / bitmap.height);
  return texture;
}

// Screens are video frames, so textures come and go: keep the few most recently used and free the rest.
function texture(file) {
  let t = textures.get(file);
  if (t) textures.delete(file);
  else t = loadTexture(file);
  textures.set(file, t);
  while (textures.size > TEXTURE_CACHE) {
    const [oldest, evicted] = textures.entries().next().value;
    textures.delete(oldest);
    evicted.then((tex) => { tex.dispose(); tex.image.close(); }, () => {});
  }
  return t;
}

function pose(phone, spec, screenTexture) {
  phone.visible = true;
  phone.position.fromArray(spec.position ?? [0, 0, 0]);
  phone.rotation.set(...(spec.rotation ?? [0, 0, 0]), 'XYZ');
  phone.scale.setScalar(spec.scale ?? 1);
  const screen = phone.userData.screen, glow = Math.min(Math.max(spec.glow ?? 1, 0), 1);
  if (screen.map !== screenTexture) {
    screen.map = screenTexture;
    screen.needsUpdate = true;
  }
  // The color multiplies linear light, so glow^2.2 makes glow scale the output's pixel values, as a compositor would.
  // A switched-off screen is the black of the glass, not a pure void.
  if (screenTexture) screen.color.setScalar(glow ** 2.2);
  else screen.color.setHex(0x030304);
}

async function snapshot(index) {
  const blob = await new Promise((resolve) => renderer.domElement.toBlob(resolve, 'image/png'));
  if (!blob) throw new Error(`could not encode frame ${index} as PNG`);
  const res = await fetch(`/out/${index}`, { method: 'POST', body: blob });
  if (!res.ok) throw new Error(`could not write frame ${index}: ${await res.text()}`);
}

export async function frame(spec, index, nextScreens = []) {
  if (failure) throw failure;
  const specs = spec.phones ?? [];
  const screens = await Promise.all(specs.map((p) => (p.screen ? texture(p.screen) : null)));
  while (phones.length < specs.length) phones.push(makePhone());
  phones.forEach((phone, i) => {
    if (i < specs.length) pose(phone, specs[i], screens[i]);
    else phone.visible = false;
  });

  const cam = { ...DEFAULT_CAMERA, ...spec.camera };
  camera.fov = cam.fov;
  camera.position.fromArray(cam.position);
  camera.lookAt(...cam.lookAt);
  camera.updateProjectionMatrix();
  const [r, g, b] = spec.rim ?? DEFAULT_RIM;
  for (const light of rimLights) light.color.setRGB(r / 255, g / 255, b / 255, THREE.SRGBColorSpace);
  scene.environment = environment([r, g, b]);

  renderer.render(scene, camera);
  // toBlob copies the canvas now and encodes later, so the next frame can draw while this one encodes.
  const job = snapshot(index).catch((e) => { failure ??= e; }).finally(() => inFlight.delete(job));
  inFlight.add(job);
  for (const file of nextScreens) texture(file).catch(() => {});  // a bad file fails when its frame awaits it
  while (inFlight.size >= IN_FLIGHT) await Promise.race(inFlight);
  if (failure) throw failure;
}

export async function flush() {
  await Promise.all(inFlight);
  if (failure) throw failure;
}

window.phones3d = { init, frame, flush };
