// What the app may send, checked before anything reaches GitHub.
// Pure functions: test/*.test.ts runs them under plain Node.

import { checkName } from "./filter.ts";

export const TYPES = ["nowy", "cechy", "potwierdzam", "nie_istnieje"] as const;
export type SubmissionType = (typeof TYPES)[number];

export const FEATURES = ["fenced", "lit", "water", "agility", "smallDogArea"] as const;
export type Feature = (typeof FEATURES)[number];

export interface Submission {
  typ: SubmissionType;
  /** "way/123", "node/5", "manual/z-17": the park a report is about. */
  parkId: string | null;
  lat: number | null;
  lon: number | null;
  /** Already through filter.ts: clean, or null. */
  nazwa: string | null;
  cechy: Partial<Record<Feature, boolean>>;
  /** Random id the app made on first launch; hashed before it goes anywhere. */
  instalacja: string;
  wersja: string;
}

export type Checked = { ok: true; value: Submission } | { ok: false; error: string };

const PARK_ID = /^(node|way|relation)\/\d{1,12}$|^manual\/[a-z0-9-]{1,60}$/;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const VERSION = /^[0-9A-Za-z.+-]{1,20}$/;

// Poland with a margin; the moderation run checks the real border later.
const LAT = [48.9, 55.0];
const LON = [14.0, 24.2];

export const MAX_NAME = 60;

/**
 * Collapses whitespace and drops what has no business in a name: control, format and
 * private-use characters, and stacks of combining marks ("zalgo"). Empty → null,
 * too long → undefined.
 */
export function cleanText(raw: unknown, max: number): string | null | undefined {
  if (raw === null || raw === undefined) return null;
  if (typeof raw !== "string" || raw.length > max * 4) return undefined;
  const text = raw.normalize("NFC")
    .replace(/[\p{Cc}\p{Cf}\p{Co}\p{Cn}]/gu, (c) => (/\s/.test(c) ? " " : ""))
    .replace(/(\p{M})\p{M}+/gu, "$1")
    .replace(/\s+/g, " ")
    .trim();
  if (!text) return null;
  return [...text].length <= max ? text : undefined;
}

export function checkSubmission(body: unknown): Checked {
  if (typeof body !== "object" || body === null || Array.isArray(body)) {
    return { ok: false, error: "Nieprawidłowe zgłoszenie." };
  }
  const b = body as Record<string, unknown>;

  const typ = b.typ;
  if (typeof typ !== "string" || !(TYPES as readonly string[]).includes(typ)) {
    return { ok: false, error: "Nieznany rodzaj zgłoszenia." };
  }
  if (typeof b.instalacja !== "string" || !UUID.test(b.instalacja)) {
    return { ok: false, error: "Brak identyfikatora instalacji." };
  }
  const wersja = typeof b.wersja === "string" && VERSION.test(b.wersja) ? b.wersja : "?";

  let parkId: string | null = null;
  let lat: number | null = null;
  let lon: number | null = null;
  if (typ === "nowy") {
    if (typeof b.lat !== "number" || typeof b.lon !== "number" || !isFinite(b.lat) || !isFinite(b.lon)) {
      return { ok: false, error: "Brak położenia wybiegu." };
    }
    if (b.lat < LAT[0] || b.lat > LAT[1] || b.lon < LON[0] || b.lon > LON[1]) {
      return { ok: false, error: "Ten punkt jest poza Polską." };
    }
    lat = Math.round(b.lat * 1e6) / 1e6;
    lon = Math.round(b.lon * 1e6) / 1e6;
  } else {
    if (typeof b.parkId !== "string" || !PARK_ID.test(b.parkId)) {
      return { ok: false, error: "Nie wiadomo, którego wybiegu dotyczy zgłoszenie." };
    }
    parkId = b.parkId;
  }

  let nazwa: string | null = null;
  if (typ === "nowy") {
    const cleaned = cleanText(b.nazwa, MAX_NAME);
    if (cleaned === undefined) return { ok: false, error: `Nazwa może mieć najwyżej ${MAX_NAME} znaków.` };
    const verdict = checkName(cleaned);
    if (!verdict.ok) return { ok: false, error: verdict.reason };
    nazwa = verdict.name;
  }

  const cechy: Partial<Record<Feature, boolean>> = {};
  if (b.cechy !== undefined && b.cechy !== null) {
    if (typeof b.cechy !== "object" || Array.isArray(b.cechy)) {
      return { ok: false, error: "Nieprawidłowe cechy wybiegu." };
    }
    // Own keys only, into a fresh object: "__proto__" and friends never get through.
    for (const k of Object.keys(b.cechy as object)) {
      const v = (b.cechy as Record<string, unknown>)[k];
      if (!(FEATURES as readonly string[]).includes(k) || typeof v !== "boolean") {
        return { ok: false, error: "Nieprawidłowe cechy wybiegu." };
      }
      cechy[k as Feature] = v;
    }
  }
  if (typ === "cechy" && Object.keys(cechy).length === 0) {
    return { ok: false, error: "Zaznacz przynajmniej jedną cechę." };
  }

  return {
    ok: true,
    value: { typ: typ as SubmissionType, parkId, lat, lon, nazwa, cechy, instalacja: b.instalacja, wersja },
  };
}

/** The anonymised record that goes into the public GitHub issue. */
export interface PublicRecord {
  v: 1;
  typ: SubmissionType;
  parkId: string | null;
  lat: number | null;
  lon: number | null;
  nazwa: string | null;
  cechy: Partial<Record<Feature, boolean>>;
  /** HMAC of the install id: tells one install's reports apart, never whose. */
  autor: string;
  /** HMAC of the network (IPv4 /24, IPv6 /64): two installs on one Wi-Fi count once. */
  siec: string;
  wersja: string;
  czas: string;
}

export function issueTitle(r: PublicRecord): string {
  const where = r.parkId ?? `${r.lat?.toFixed(5)}, ${r.lon?.toFixed(5)}`;
  const label = { nowy: "Nowy wybieg", cechy: "Cechy", potwierdzam: "Potwierdzenie", nie_istnieje: "Nie istnieje" }[r.typ];
  // The name stays out of the title: titles render Markdown, the JSON fence does not.
  return `[${label}] ${where}`;
}

export function issueBody(r: PublicRecord): string {
  const lines = [
    "Zgłoszenie z aplikacji Psie Parki. Automatyczna moderacja (`.github/workflows/moderacja.yml`) " +
      "przetworzy je, skomentuje i zamknie — nie trzeba nic robić.",
    "",
  ];
  if (r.lat !== null && r.lon !== null) {
    lines.push(`Miejsce: https://www.openstreetmap.org/?mlat=${r.lat}&mlon=${r.lon}#map=18/${r.lat}/${r.lon}`, "");
  } else if (r.parkId && !r.parkId.startsWith("manual/")) {
    lines.push(`Wybieg: https://www.openstreetmap.org/${r.parkId}`, "");
  }
  // Everything a person typed lives inside this fence, where GitHub renders no mentions,
  // links or markup; the filter has already refused backticks, braces and "@" in it.
  lines.push("```json", JSON.stringify(r, null, 2), "```");
  return lines.join("\n");
}

/** The network part of an address: IPv4 /24, IPv6 /64 (what one household gets). */
export function networkOf(ip: string): string {
  if (!ip.includes(":")) return ip.split(".").slice(0, 3).join(".");
  const [head, tail = ""] = ip.split("::");
  const h = head ? head.split(":") : [];
  const t = tail ? tail.split(":") : [];
  const groups = [...h, ...Array(Math.max(0, 8 - h.length - t.length)).fill("0"), ...t];
  return groups.slice(0, 4).map((g) => g.toLowerCase().replace(/^0+(?=.)/, "")).join(":");
}
