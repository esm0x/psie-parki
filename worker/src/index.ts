// Psie Parki submissions: the app's only write path, deliberately thin and free.
//
//   POST /zgloszenie                 check, rate-limit, filter the name, open a GitHub issue
//   GET  /zgloszenie/<nr>?t=<token>  status of one's own submission, for "Moje zgłoszenia"
//
// No paid API anywhere: the name goes through the deterministic filter in filter.ts.
// Nothing is decided here about the map itself: the moderation workflow in the repo
// reads the issues, checks duplicates and independent confirmations, and only then
// changes the data. This worker's job is to keep junk, abuse and personal data out.

import { checkSubmission, issueBody, issueTitle, networkOf, type PublicRecord } from "./submission.ts";

export interface Env {
  LIMITS: KVNamespace;
  /** Cloudflare rate limiting binding: bursts per network, before KV is touched. */
  BURST: RateLimit;
  /** Fine-grained token: Issues read/write on GITHUB_REPO only. */
  GITHUB_TOKEN: string;
  /** "esm0x/psie-parki" */
  GITHUB_REPO: string;
  /** Key for hashing install ids, networks and status tokens. Never rotate casually. */
  HASH_SECRET: string;
}

// Per day. Generous for a person, tight for a script. The free KV plan allows 1000
// writes a day and every accepted submission costs two, so the global cap is that.
const PER_INSTALL = 10;
const PER_NETWORK = 20;
const MAX_BODY = 2048;
const STATUS_CACHE_S = 600;

const json = (status: number, body: unknown, extra: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...extra },
  });

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);
    try {
      if (request.method === "POST" && url.pathname === "/zgloszenie") return await submit(request, env);
      const status = url.pathname.match(/^\/zgloszenie\/(\d{1,9})$/);
      if (request.method === "GET" && status) return await statusOf(Number(status[1]), url.searchParams.get("t"), request, env, ctx);
      return json(404, { error: "Nie ma takiego adresu." });
    } catch (e) {
      console.error(e);
      return json(503, { error: "Coś poszło nie tak po naszej stronie. Spróbuj za chwilę." });
    }
  },
} satisfies ExportedHandler<Env>;

async function submit(request: Request, env: Env): Promise<Response> {
  if (Number(request.headers.get("content-length") ?? MAX_BODY + 1) > MAX_BODY) {
    return json(413, { error: "Zgłoszenie jest za duże." });
  }
  const ip = request.headers.get("cf-connecting-ip") ?? "0.0.0.0";
  const net = networkOf(ip);
  const { success } = await env.BURST.limit({ key: "n:" + net });
  if (!success) return json(429, { error: "Za dużo zgłoszeń naraz. Spróbuj za minutę." });

  const raw = await request.text();
  if (raw.length > MAX_BODY) return json(413, { error: "Zgłoszenie jest za duże." });
  let body: unknown;
  try {
    body = JSON.parse(raw);
  } catch {
    return json(400, { error: "Nieprawidłowe zgłoszenie." });
  }
  const checked = checkSubmission(body);
  if (!checked.ok) return json(422, { error: checked.error });
  const s = checked.value;

  const autor = (await hmac(env.HASH_SECRET, "i:" + s.instalacja)).slice(0, 16);
  const siec = (await hmac(env.HASH_SECRET, "n:" + net)).slice(0, 12);

  // Counted before the issue exists, so a failure later cannot be retried for free, and
  // a KV hiccup (or the free plan's daily write quota) lets submissions through rather
  // than turning every one of them into an error: the moderation run is the real gate.
  const day = new Date().toISOString().slice(0, 10);
  const keys: [string, number][] = [[`i:${autor}:${day}`, PER_INSTALL], [`n:${siec}:${day}`, PER_NETWORK]];
  try {
    const counts = await Promise.all(keys.map(([k]) => env.LIMITS.get(k)));
    if (counts.some((c, i) => Number(c ?? 0) >= keys[i][1])) {
      return json(429, { error: "Dziś przyszło już dużo zgłoszeń z tej sieci. Spróbuj jutro — dziękujemy!" });
    }
    await Promise.all(keys.map(([k], i) =>
      env.LIMITS.put(k, String(Number(counts[i] ?? 0) + 1), { expirationTtl: 2 * 24 * 3600 })));
  } catch (e) {
    console.warn("limits unavailable", e);
  }

  const record: PublicRecord = {
    v: 1, typ: s.typ, parkId: s.parkId, lat: s.lat, lon: s.lon, nazwa: s.nazwa,
    cechy: s.cechy, autor, siec, wersja: s.wersja,
    czas: new Date().toISOString().slice(0, 19) + "Z",
  };
  const issue = await github(env, "POST", "/issues", {
    title: issueTitle(record),
    body: issueBody(record),
    labels: ["zgloszenie", `typ:${s.typ}`],
  }) as { number: number };

  return json(201, { nr: issue.number, token: await statusToken(env, issue.number), status: "oczekuje" });
}

/** Only the app that made a submission knows its token, so the status endpoint cannot
 *  be walked to burn the GitHub token's hourly quota. */
async function statusToken(env: Env, nr: number): Promise<string> {
  return (await hmac(env.HASH_SECRET, "s:" + nr)).slice(0, 20);
}

/** "oczekuje", "przyjete", "niepotwierdzone", "odrzucone", with the reason when there is one. */
async function statusOf(nr: number, token: string | null, request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  if (!token || token !== await statusToken(env, nr)) return json(404, { error: "Nie ma takiego zgłoszenia." });

  // Decisions change at most every half hour, so ten minutes of edge cache is free
  // protection for the GitHub token, without spending KV writes.
  const cache = caches.default;
  const key = new Request(new URL(`/zgloszenie/${nr}?t=${token}`, request.url).toString());
  const hit = await cache.match(key);
  if (hit) return hit;

  const issue = await github(env, "GET", `/issues/${nr}`) as { labels: { name: string }[] } | null;
  const labels = issue?.labels.map((l) => l.name) ?? [];
  if (!issue || !labels.includes("zgloszenie")) return json(404, { error: "Nie ma takiego zgłoszenia." });
  const status = ["przyjete", "niepotwierdzone", "odrzucone"].find((s) => labels.includes(s)) ?? "oczekuje";
  const powod = labels.find((l) => l.startsWith("powod:"))?.slice(6) ?? null;
  const response = json(200, { nr, status, powod }, { "cache-control": `public, max-age=${STATUS_CACHE_S}` });
  ctx.waitUntil(cache.put(key, response.clone()));
  return response;
}

async function github(env: Env, method: string, path: string, body?: unknown): Promise<unknown> {
  const r = await fetch(`https://api.github.com/repos/${env.GITHUB_REPO}${path}`, {
    method,
    headers: {
      authorization: `Bearer ${env.GITHUB_TOKEN}`,
      accept: "application/vnd.github+json",
      "user-agent": "psie-parki-zgloszenia",
      "x-github-api-version": "2022-11-28",
      ...(body ? { "content-type": "application/json" } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (method === "GET" && r.status === 404) return null;
  if (!r.ok) throw new Error(`GitHub ${method} ${path}: HTTP ${r.status}`);
  return r.json();
}

async function hmac(secret: string, data: string): Promise<string> {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const sig = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(data));
  return [...new Uint8Array(sig)].map((b) => b.toString(16).padStart(2, "0")).join("");
}
