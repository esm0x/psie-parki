// Psie Parki submissions: the app's only write path, deliberately thin.
//
//   POST /zgloszenie          check, rate-limit, moderate text, open a GitHub issue
//   GET  /zgloszenie/<nr>     status of a submission, for "Moje zgłoszenia" in the app
//
// Nothing is decided here about the map itself: the moderation workflow in the repo
// reads the issues, checks duplicates and independent confirmations, and only then
// changes the data. This worker's job is to keep junk, abuse and personal data out.

import Anthropic from "@anthropic-ai/sdk";
import { needsModeration, moderate } from "./moderate.ts";
import { checkSubmission, issueBody, issueTitle, networkOf, type PublicRecord } from "./submission.ts";

export interface Env {
  LIMITS: KVNamespace;
  ANTHROPIC_API_KEY: string;
  /** Fine-grained token: Issues read/write on GITHUB_REPO only. */
  GITHUB_TOKEN: string;
  /** "esm0x/psie-parki" */
  GITHUB_REPO: string;
  /** Key for hashing install ids and networks; rotate and every author looks new. */
  HASH_SECRET: string;
}

// Per day. Generous for a person, tight for a script.
const PER_INSTALL = 10;
const PER_NETWORK = 30;
const OVERALL = 500;

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    try {
      if (request.method === "POST" && url.pathname === "/zgloszenie") return await submit(request, env);
      const status = url.pathname.match(/^\/zgloszenie\/(\d{1,9})$/);
      if (request.method === "GET" && status) return await statusOf(Number(status[1]), env);
      return json(404, { error: "Nie ma takiego adresu." });
    } catch (e) {
      console.error(e);
      return json(503, { error: "Coś poszło nie tak po naszej stronie. Spróbuj za chwilę." });
    }
  },
} satisfies ExportedHandler<Env>;

async function submit(request: Request, env: Env): Promise<Response> {
  const raw = await request.text();
  if (raw.length > 4096) return json(413, { error: "Zgłoszenie jest za duże." });
  let body: unknown;
  try {
    body = JSON.parse(raw);
  } catch {
    return json(400, { error: "Nieprawidłowe zgłoszenie." });
  }
  const checked = checkSubmission(body);
  if (!checked.ok) return json(422, { error: checked.error });
  const s = checked.value;

  const ip = request.headers.get("cf-connecting-ip") ?? "0.0.0.0";
  const autor = (await hmac(env.HASH_SECRET, "i:" + s.instalacja)).slice(0, 16);
  const siec = (await hmac(env.HASH_SECRET, "n:" + networkOf(ip))).slice(0, 12);

  const day = new Date().toISOString().slice(0, 10);
  const over = await overLimit(env.LIMITS, [
    [`i:${autor}:${day}`, PER_INSTALL],
    [`n:${siec}:${day}`, PER_NETWORK],
    [`all:${day}`, OVERALL],
  ]);
  if (over) return json(429, { error: "Dziś przyszło już dużo zgłoszeń. Spróbuj jutro — dziękujemy!" });

  let nazwa = s.nazwa;
  if (needsModeration(s)) {
    const client = new Anthropic({ apiKey: env.ANTHROPIC_API_KEY, maxRetries: 2 });
    const verdict = await moderate(client, s);
    if (verdict.decision === "reject") {
      return json(422, { error: verdict.reason_pl, kategoria: verdict.category });
    }
    if (s.typ === "nowy") nazwa = verdict.clean_name;
  }

  const record: PublicRecord = {
    v: 1, typ: s.typ, parkId: s.parkId, lat: s.lat, lon: s.lon, nazwa,
    cechy: s.cechy, komentarz: s.komentarz, autor, siec, wersja: s.wersja,
    czas: new Date().toISOString().slice(0, 19) + "Z",
  };
  const issue = await github(env, "POST", "/issues", {
    title: issueTitle(record),
    body: issueBody(record),
    labels: ["zgloszenie", `typ:${s.typ}`],
  }) as { number: number };
  await bump(env.LIMITS, [`i:${autor}:${day}`, `n:${siec}:${day}`, `all:${day}`]);

  return json(201, { nr: issue.number, status: "oczekuje" });
}

/** "oczekuje", "przyjete", "niepotwierdzone", "odrzucone", with the reason when there is one. */
async function statusOf(nr: number, env: Env): Promise<Response> {
  const issue = await github(env, "GET", `/issues/${nr}`) as {
    state: string;
    labels: { name: string }[];
  } | null;
  const labels = issue?.labels.map((l) => l.name) ?? [];
  if (!issue || !labels.includes("zgloszenie")) return json(404, { error: "Nie ma takiego zgłoszenia." });
  const status = ["przyjete", "niepotwierdzone", "odrzucone"].find((s) => labels.includes(s)) ?? "oczekuje";
  const powod = labels.find((l) => l.startsWith("powod:"))?.slice(6) ?? null;
  return json(200, { nr, status, powod });
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

async function overLimit(kv: KVNamespace, limits: [string, number][]): Promise<boolean> {
  const counts = await Promise.all(limits.map(([k]) => kv.get(k)));
  return counts.some((c, i) => Number(c ?? 0) >= limits[i][1]);
}

// KV is eventually consistent, so two requests in the same second can both pass: a
// soft limit, which is all a day's quota needs.
async function bump(kv: KVNamespace, keys: string[]): Promise<void> {
  await Promise.all(keys.map(async (k) => {
    const n = Number((await kv.get(k)) ?? 0) + 1;
    await kv.put(k, String(n), { expirationTtl: 2 * 24 * 3600 });
  }));
}
