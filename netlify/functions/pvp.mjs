// The PvP relay for Virtual Pet Slasher 2 Turbo, at pets.superfun.games/api/pvp.
//
// Two players meet in a room by its 4-digit code. The relay only passes things between them: each player's
// fighter, a shared seed, and their moves turn by turn. It never plays the fight: both apps run the same
// engine on the same seed and moves (lockstep), so they agree on every hit without the relay knowing the rules.
// A move is revealed only once both players have locked in theirs, so neither can peek.
//
// Every value lives under its own key and the two players never write the same one, so nothing is
// read-modify-written: a guest joining and a move being locked in are single writes that only succeed once.
//
//   POST /api/pvp/rooms                  {v, fighter}    -> 201 {room, code, token, seed}
//   POST /api/pvp/join/:code             {v, fighter}    -> 200 {room, token, seed, host}
//   GET  /api/pvp/rooms/:room?token=                     -> 200 {host, guest, left}
//   POST /api/pvp/rooms/:room/turns/:n   {token, move}   -> 200 {ok}
//   GET  /api/pvp/rooms/:room/turns/:n?token=            -> 200 {hostIn, guestIn, host, guest, left}
//   POST /api/pvp/rooms/:room/leave      {token}         -> 200 {ok}
//
// Errors are {error} with: bad_request (400), forbidden (403), no_room (404), full or version (409), busy (503).
import { getStore } from "@netlify/blobs";

const PETS = ["JASON", "FREDDY", "PINHEAD", "GHOSTFACE", "CHUCKY", "MICHAEL", "LEATHERFACE"];
const MOVES = ["STRIKE", "HEAVY", "BLOCK", "SPECIAL", "POTION", "RUN"];

/** A code nobody has joined is free again after this long. */
const CODE_TTL_MS = 30 * 60 * 1000;
const MAX_TURN = 500;

const json = (status, body) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", "cache-control": "no-store" } });
const fail = (status, error, extra = {}) => json(status, { error, ...extra });

/** A fighter as the app sends it, checked field by field; null if anything is off. */
function fighterOf(f) {
  if (!f || typeof f !== "object") return null;
  const int = (v, lo, hi) => Number.isInteger(v) && v >= lo && v <= hi;
  if (!PETS.includes(f.id) || !int(f.level, 1, 999) || !int(f.maxHp, 1, 99999) || !int(f.mood, 0, 100)) return null;
  if (typeof f.strength !== "number" || !(f.strength >= 0 && f.strength <= 100)) return null;
  return { id: f.id, level: f.level, maxHp: f.maxHp, strength: f.strength, mood: f.mood };
}

async function body(req) {
  try {
    return await req.json();
  } catch {
    return null;
  }
}

/** Which side [token] plays in [meta]'s room ("host" or "guest"), or null. */
async function roleOf(store, room, meta, token) {
  if (!token) return null;
  if (token === meta.host.token) return "host";
  const guest = await store.get(`room/${room}/guest`, { type: "json" });
  return guest && token === guest.token ? "guest" : null;
}

async function leftOf(store, room) {
  const [host, guest] = await Promise.all([
    store.get(`room/${room}/left/host`, { type: "json" }),
    store.get(`room/${room}/left/guest`, { type: "json" }),
  ]);
  return host ? "host" : guest ? "guest" : null;
}

async function createRoom(store, req) {
  const b = await body(req);
  const fighter = fighterOf(b?.fighter);
  if (!fighter || !Number.isInteger(b?.v)) return fail(400, "bad_request");
  const room = crypto.randomUUID();
  const token = crypto.randomUUID();
  const seed = crypto.getRandomValues(new Int32Array(1))[0];
  const now = Date.now();
  await store.setJSON(`room/${room}/meta`, { v: b.v, seed, createdAt: now, host: { token, fighter } });
  // A free code: never used, or left unjoined long enough that its room is dead.
  for (let tries = 0; tries < 40; tries++) {
    const code = String(1000 + Math.floor(Math.random() * 9000));
    const key = `code/${code}`;
    const taken = await store.get(key, { type: "json" });
    if (taken && now - taken.createdAt < CODE_TTL_MS) continue;
    if (taken) await store.delete(key);
    const { modified } = await store.setJSON(key, { room, createdAt: now }, { onlyIfNew: true });
    if (modified) return json(201, { room, code, token, seed });
  }
  return fail(503, "busy");
}

async function joinRoom(store, req, code) {
  const b = await body(req);
  const fighter = fighterOf(b?.fighter);
  if (!fighter || !Number.isInteger(b?.v) || !/^\d{4}$/.test(code)) return fail(400, "bad_request");
  const entry = await store.get(`code/${code}`, { type: "json" });
  if (!entry || Date.now() - entry.createdAt >= CODE_TTL_MS) return fail(404, "no_room");
  const meta = await store.get(`room/${entry.room}/meta`, { type: "json" });
  if (!meta || (await leftOf(store, entry.room))) return fail(404, "no_room");
  // Both apps must run the same fight engine, or the lockstep would drift apart.
  if (meta.v !== b.v) return fail(409, "version", { v: meta.v });
  const token = crypto.randomUUID();
  const { modified } = await store.setJSON(`room/${entry.room}/guest`, { token, fighter }, { onlyIfNew: true });
  if (!modified) return fail(409, "full");
  // The code has done its job: free it for the next room.
  await store.delete(`code/${code}`);
  return json(200, { room: entry.room, token, seed: meta.seed, host: meta.host.fighter });
}

async function roomState(store, room, token) {
  const meta = await store.get(`room/${room}/meta`, { type: "json" });
  if (!meta) return fail(404, "no_room");
  if (!(await roleOf(store, room, meta, token))) return fail(403, "forbidden");
  const guest = await store.get(`room/${room}/guest`, { type: "json" });
  return json(200, { host: meta.host.fighter, guest: guest ? guest.fighter : null, left: await leftOf(store, room) });
}

async function lockIn(store, req, room, n) {
  const b = await body(req);
  if (!MOVES.includes(b?.move) || !(n >= 1 && n <= MAX_TURN)) return fail(400, "bad_request");
  const meta = await store.get(`room/${room}/meta`, { type: "json" });
  if (!meta) return fail(404, "no_room");
  const role = await roleOf(store, room, meta, b.token);
  if (!role) return fail(403, "forbidden");
  // Locked in once: a retried request finds its own move already there, which is fine.
  await store.setJSON(`room/${room}/turn/${n}/${role}`, { move: b.move }, { onlyIfNew: true });
  return json(200, { ok: true });
}

async function turnState(store, room, n, token) {
  if (!(n >= 1 && n <= MAX_TURN)) return fail(400, "bad_request");
  const meta = await store.get(`room/${room}/meta`, { type: "json" });
  if (!meta) return fail(404, "no_room");
  if (!(await roleOf(store, room, meta, token))) return fail(403, "forbidden");
  const [host, guest, left] = await Promise.all([
    store.get(`room/${room}/turn/${n}/host`, { type: "json" }),
    store.get(`room/${room}/turn/${n}/guest`, { type: "json" }),
    leftOf(store, room),
  ]);
  const both = Boolean(host && guest);
  return json(200, {
    hostIn: Boolean(host),
    guestIn: Boolean(guest),
    host: both ? host.move : null,
    guest: both ? guest.move : null,
    left,
  });
}

async function leave(store, req, room) {
  const b = await body(req);
  const meta = await store.get(`room/${room}/meta`, { type: "json" });
  if (!meta) return json(200, { ok: true });
  const role = await roleOf(store, room, meta, b?.token);
  if (!role) return fail(403, "forbidden");
  await store.setJSON(`room/${room}/left/${role}`, { at: Date.now() });
  return json(200, { ok: true });
}

export default async (req) => {
  const store = getStore({ name: "pvp", consistency: "strong" });
  const url = new URL(req.url);
  const parts = url.pathname.replace(/^\/api\/pvp\/?/, "").split("/").filter(Boolean);
  const token = url.searchParams.get("token");
  try {
    if (req.method === "POST" && parts.length === 1 && parts[0] === "rooms") return await createRoom(store, req);
    if (req.method === "POST" && parts.length === 2 && parts[0] === "join") return await joinRoom(store, req, parts[1]);
    if (parts[0] === "rooms" && parts.length >= 2) {
      const room = parts[1];
      if (!/^[0-9a-f-]{36}$/.test(room)) return fail(400, "bad_request");
      if (req.method === "GET" && parts.length === 2) return await roomState(store, room, token);
      if (req.method === "POST" && parts.length === 3 && parts[2] === "leave") return await leave(store, req, room);
      if (parts.length === 4 && parts[2] === "turns") {
        const n = Number(parts[3]);
        if (req.method === "POST") return await lockIn(store, req, room, n);
        if (req.method === "GET") return await turnState(store, room, n, token);
      }
    }
    return fail(404, "not_found");
  } catch (e) {
    console.error(e);
    return fail(503, "busy");
  }
};

export const config = { path: "/api/pvp/*" };
