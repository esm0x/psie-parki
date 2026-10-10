// What the app may send, checked before anything costs money or reaches GitHub.
// Pure functions: test/submission.test.ts runs them under plain Node.

export const TYPES = ["nowy", "cechy", "potwierdzam", "nie_istnieje"] as const;
export type SubmissionType = (typeof TYPES)[number];

export const FEATURES = ["fenced", "lit", "water", "agility", "smallDogArea"] as const;
export type Feature = (typeof FEATURES)[number];

export interface Submission {
  typ: SubmissionType;
  /** "way/123", "node/5", "manual/z-17": the park a correction is about. */
  parkId: string | null;
  lat: number | null;
  lon: number | null;
  nazwa: string | null;
  cechy: Partial<Record<Feature, boolean>>;
  komentarz: string | null;
  /** Random id the app made on first launch; hashed before it goes anywhere. */
  instalacja: string;
  wersja: string;
}

export type Checked = { ok: true; value: Submission } | { ok: false; error: string };

const PARK_ID = /^(node|way|relation)\/\d{1,12}$|^manual\/[a-z0-9-]{1,60}$/;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

// Poland with a margin; the moderation run checks the real border later.
const LAT = [48.9, 55.0];
const LON = [14.0, 24.2];

export const MAX_NAME = 60;
export const MAX_COMMENT = 300;

// Things a dog park's name or a note never needs and spam always does. Rejected here,
// for free, before the model sees the text.
const LINK = /(https?:\/\/|www\.|\b[a-z0-9-]+\.(pl|com|eu|net|org|info|io)\b)/i;
const EMAIL = /[^\s@]+@[^\s@]+\.[^\s@]+/;
const PHONE = /(\+?\d[\d\s-]{7,}\d)/;

/** Collapses whitespace and drops control characters; empty becomes null. */
export function cleanText(raw: unknown, max: number): string | null | undefined {
  if (raw === null || raw === undefined) return null;
  if (typeof raw !== "string") return undefined;
  const text = raw.replace(/[\u0000-\u001f\u007f-\u009f\u200b-\u200f\u2028-\u202e]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
  if (!text) return null;
  return text.length <= max ? text : undefined;
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
  const wersja = typeof b.wersja === "string" ? b.wersja.slice(0, 20) : "?";

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

  const nazwa = typ === "nowy" ? cleanText(b.nazwa, MAX_NAME) : null;
  if (nazwa === undefined) return { ok: false, error: `Nazwa może mieć najwyżej ${MAX_NAME} znaków.` };
  const komentarz = cleanText(b.komentarz, MAX_COMMENT);
  if (komentarz === undefined) return { ok: false, error: `Uwagi mogą mieć najwyżej ${MAX_COMMENT} znaków.` };

  for (const text of [nazwa, komentarz]) {
    if (text && (LINK.test(text) || EMAIL.test(text) || PHONE.test(text))) {
      return { ok: false, error: "Bez linków, adresów e-mail i numerów telefonu, prosimy." };
    }
  }

  const cechy: Partial<Record<Feature, boolean>> = {};
  if (b.cechy !== undefined && b.cechy !== null) {
    if (typeof b.cechy !== "object" || Array.isArray(b.cechy)) {
      return { ok: false, error: "Nieprawidłowe cechy wybiegu." };
    }
    for (const [k, v] of Object.entries(b.cechy as Record<string, unknown>)) {
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
    value: { typ: typ as SubmissionType, parkId, lat, lon, nazwa, cechy, komentarz, instalacja: b.instalacja, wersja },
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
  komentarz: string | null;
  /** HMAC of the install id: lets the moderation tell two people from one, never who. */
  autor: string;
  /** HMAC of the network (/24 or /48): one person with two installs on one Wi-Fi. */
  siec: string;
  wersja: string;
  czas: string;
}

export function issueTitle(r: PublicRecord): string {
  const where = r.parkId ?? `${r.lat?.toFixed(5)}, ${r.lon?.toFixed(5)}`;
  const label = { nowy: "Nowy wybieg", cechy: "Cechy", potwierdzam: "Potwierdzenie", nie_istnieje: "Nie istnieje" }[r.typ];
  return `[${label}] ${r.nazwa ? r.nazwa + " – " : ""}${where}`.slice(0, 200);
}

export function issueBody(r: PublicRecord): string {
  const lines = [
    "Zgłoszenie z aplikacji Psie Parki. Automatyczna moderacja (`.github/workflows/moderacja.yml`) " +
      "przetworzy je, skomentuje i zamknie — nie trzeba nic robić.",
    "",
  ];
  if (r.lat !== null && r.lon !== null) {
    lines.push(`Miejsce: https://www.openstreetmap.org/?mlat=${r.lat}&mlon=${r.lon}#map=18/${r.lat}/${r.lon}`, "");
  } else if (r.parkId) {
    lines.push(`Wybieg: https://www.openstreetmap.org/${r.parkId.startsWith("manual/") ? "" : r.parkId}`, "");
  }
  lines.push("```json", JSON.stringify(r, null, 2), "```");
  return lines.join("\n");
}

/** Network part of an address: one household or office counts as one witness. */
export function networkOf(ip: string): string {
  if (ip.includes(":")) return ip.split(":").slice(0, 3).join(":"); // /48
  return ip.split(".").slice(0, 3).join(".");                       // /24
}
