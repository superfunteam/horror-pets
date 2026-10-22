// Render 3D smartphones with gameplay on their screens to transparent PNGs, one per frame, in one headless Chrome
// session (three.js in the installed Chrome, driven by puppeteer-core; no browser is downloaded).
//
// Usage:
//   (cd tools/phones3d && npm install)          # once
//   node tools/phones3d/render.mjs <spec.json>
//
// The spec (tools/reel/phones.py writes them) gives the size and, per frame, the output PNG, the camera, an optional rim light color
// and the phones, each with a screen image (cover-fit to the screen) or null for a switched-off screen. Units are
// roughly centimeters; a phone is 16.0 x 7.5 x 0.9, centered on its position, screen facing +Z. scene.mjs builds it.
//
// Env: CHROME_PATH overrides the Chrome binary.
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import puppeteer from 'puppeteer-core';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const CHROME = process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const COMMON_ARGS = ['--no-first-run', '--no-default-browser-check', '--mute-audio', '--hide-scrollbars',
  '--disable-background-timer-throttling', '--disable-renderer-backgrounding',
  '--disable-backgrounding-occluded-windows'];
const GPU_ARGS = ['--use-angle=metal', '--enable-gpu', '--ignore-gpu-blocklist'];
const SOFTWARE_ARGS = ['--use-angle=swiftshader', '--enable-unsafe-swiftshader'];
const MIME = { '.html': 'text/html', '.mjs': 'text/javascript', '.js': 'text/javascript', '.json': 'application/json',
  '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.webp': 'image/webp' };

class RenderError extends Error {}

function fail(message) {
  console.error(`phones3d: ${message}`);
  process.exit(1);
}

function isVec(v, n) {
  return Array.isArray(v) && v.length === n && v.every(Number.isFinite);
}

// Check the whole spec up front so a typo fails in a second, not after minutes of rendering.
function loadSpec(file) {
  let spec;
  try {
    spec = JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (e) {
    throw new RenderError(`cannot read spec ${file}: ${e.message}`);
  }
  spec.width ??= 1920;
  spec.height ??= 1080;
  if (!Number.isInteger(spec.width) || !Number.isInteger(spec.height) || spec.width < 1 || spec.height < 1) {
    throw new RenderError('width and height must be positive integers');
  }
  if (!Array.isArray(spec.frames) || spec.frames.length === 0) throw new RenderError('frames must be a non-empty list');

  const screens = new Set();
  spec.frames.forEach((frame, i) => {
    const where = `frame ${i}`;
    if (typeof frame !== 'object' || frame === null) throw new RenderError(`${where}: must be an object`);
    if (typeof frame.out !== 'string' || !path.isAbsolute(frame.out) || !frame.out.endsWith('.png')) {
      throw new RenderError(`${where}: out must be an absolute .png path`);
    }
    const cam = frame.camera ?? {};
    if (cam.fov !== undefined && !(Number.isFinite(cam.fov) && cam.fov > 0 && cam.fov < 180)) {
      throw new RenderError(`${where}: camera.fov must be in (0, 180)`);
    }
    for (const key of ['position', 'lookAt']) {
      if (cam[key] !== undefined && !isVec(cam[key], 3)) {
        throw new RenderError(`${where}: camera.${key} must be [x, y, z]`);
      }
    }
    if (frame.rim !== undefined && !isVec(frame.rim, 3)) {
      throw new RenderError(`${where}: rim must be [r, g, b] 0..255`);
    }
    if (frame.phones !== undefined && !Array.isArray(frame.phones)) {
      throw new RenderError(`${where}: phones must be a list`);
    }
    (frame.phones ?? []).forEach((phone, j) => {
      const at = `${where} phone ${j}`;
      if (typeof phone !== 'object' || phone === null) throw new RenderError(`${at}: must be an object`);
      for (const key of ['position', 'rotation']) {
        if (phone[key] !== undefined && !isVec(phone[key], 3)) throw new RenderError(`${at}: ${key} must be [x, y, z]`);
      }
      for (const key of ['scale', 'glow']) {
        if (phone[key] !== undefined && !Number.isFinite(phone[key])) {
          throw new RenderError(`${at}: ${key} must be a number`);
        }
      }
      if (phone.screen === undefined || phone.screen === null) return;
      if (typeof phone.screen !== 'string' || !path.isAbsolute(phone.screen)) {
        throw new RenderError(`${at}: screen must be an absolute path or null`);
      }
      if (!screens.has(phone.screen) && !fs.statSync(phone.screen, { throwIfNoEntry: false })?.isFile()) {
        throw new RenderError(`${at}: screen not found: ${phone.screen}`);
      }
      screens.add(phone.screen);
    });
  });
  return { spec, screens };
}

function send(res, status, body, type = 'text/plain') {
  res.writeHead(status, { 'Content-Type': type, 'Cache-Control': 'no-store' });
  res.end(body);
}

async function readBody(req) {
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  return Buffer.concat(chunks);
}

// Serves this folder (the page and three), the spec's screen images under /fs/, and takes each frame's PNG as
// POST /out/<index>. Only the spec's own screens and outputs are reachable.
function startServer(spec, screens) {
  const outs = spec.frames.map((f) => f.out);
  const handle = async (req, res) => {
    const url = new URL(req.url, 'http://localhost');
    if (req.method === 'POST' && url.pathname.startsWith('/out/')) {
      const out = outs[Number(url.pathname.slice(5))];
      if (!out) return send(res, 404, `no frame ${url.pathname}`);
      await fsp.writeFile(out, await readBody(req));
      return send(res, 204, '');
    }
    let file;
    if (url.pathname.startsWith('/fs/')) {
      file = decodeURIComponent(url.pathname.slice(4));
      if (!screens.has(file)) return send(res, 403, `not a screen in the spec: ${file}`);
    } else {
      file = path.join(HERE, path.normalize(decodeURIComponent(url.pathname)));
      if (!file.startsWith(HERE + path.sep)) return send(res, 403, 'outside phones3d');
    }
    const data = await fsp.readFile(file).catch(() => null);
    if (!data) return send(res, 404, `not found: ${file}`);
    send(res, 200, data, MIME[path.extname(file).toLowerCase()] ?? 'application/octet-stream');
  };
  const server = http.createServer((req, res) => handle(req, res).catch((e) => send(res, 500, e.message)));
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => resolve(server));
  });
}

// Opens the scene in a fresh headless Chrome; returns null when it gets no WebGL with these flags.
async function openScene(args, url, spec, errors) {
  errors.length = 0;
  const browser = await puppeteer.launch({ executablePath: CHROME, headless: true, args: [...COMMON_ARGS, ...args] });
  try {
    const page = await browser.newPage();
    page.on('pageerror', (e) => errors.push(e.message));
    page.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
    await page.goto(url, { waitUntil: 'load' });
    await page.waitForFunction(() => window.phones3d, { timeout: 30000 });
    const gl = await page.evaluate((w, h) => window.phones3d.init(w, h), spec.width, spec.height);
    return { browser, page, gl };
  } catch (e) {
    await browser.close();
    if (/WebGL|context/i.test(e.message)) return null;
    throw e;
  }
}

async function render(specFile) {
  const t0 = performance.now();
  const { spec, screens } = loadSpec(specFile);
  for (const dir of new Set(spec.frames.map((f) => path.dirname(f.out)))) {
    try {
      fs.mkdirSync(dir, { recursive: true });
    } catch (e) {
      throw new RenderError(`cannot create output folder ${dir}: ${e.message}`);
    }
  }
  if (!fs.existsSync(CHROME)) throw new RenderError(`Chrome not found at ${CHROME} (set CHROME_PATH)`);

  const server = await startServer(spec, screens);
  const url = `http://127.0.0.1:${server.address().port}/scene.html`;
  const errors = [];
  let session;
  try {
    session = (await openScene(GPU_ARGS, url, spec, errors)) ?? (await openScene(SOFTWARE_ARGS, url, spec, errors));
    if (!session) throw new RenderError(`no WebGL in headless Chrome, even with SwiftShader${pageErrors(errors)}`);
    const { page, gl } = session;
    const t1 = performance.now();
    for (let i = 0; i < spec.frames.length; i++) {
      const next = (spec.frames[i + 1]?.phones ?? []).map((p) => p.screen).filter(Boolean);
      try {
        await page.evaluate((f, n, ahead) => window.phones3d.frame(f, n, ahead), spec.frames[i], i, next);
      } catch (e) {
        throw new RenderError(`frame ${i} (${spec.frames[i].out}): ${firstLine(e)}${pageErrors(errors)}`);
      }
    }
    await page.evaluate(() => window.phones3d.flush()).catch((e) => {
      throw new RenderError(`${firstLine(e)}${pageErrors(errors)}`);
    });
    const t2 = performance.now(), n = spec.frames.length;
    console.log(`phones3d: ${n} frames ${spec.width}x${spec.height} in ${((t2 - t0) / 1000).toFixed(1)}s ` +
      `(startup ${((t1 - t0) / 1000).toFixed(1)}s, ${((t2 - t1) / n).toFixed(0)} ms/frame) on ${gl}`);
  } finally {
    await session?.browser.close();
    server.close();
  }
}

// Puppeteer appends the page's stack to the message; the first line is the part worth reading.
function firstLine(e) {
  return String(e.message).split('\n')[0];
}

function pageErrors(errors) {
  return errors.length ? `\n  page errors:\n    ${errors.slice(0, 5).join('\n    ')}` : '';
}

if (process.argv.length !== 3) fail('usage: node tools/phones3d/render.mjs <spec.json>');
render(process.argv[2]).catch((e) => fail(e instanceof RenderError ? e.message : e.stack ?? String(e)));
