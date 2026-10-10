// The text check for a park's name: deterministic, free, and run before anything is
// published. It replaced a language model, so it errs on the side of refusing: a
// refused name costs the sender a rewording, a published slur costs the project.
//
// What it is up against is not people who write "kurwa" but people who write
// "k.u.r.w.a", "kurw@", "ｋｕｒｗａ", "кurwa" (Cyrillic к) or "ku<zero-width>rwa". So the text
// is first folded to plain lowercase ASCII letters, and only then searched.

export type FilterResult = { ok: true; name: string | null } | { ok: false; reason: string };

// Cyrillic and Greek letters that look Latin, for "кurwa" and friends.
const HOMOGLYPHS: Record<string, string> = {
  "а": "a", "е": "e", "о": "o", "р": "p", "с": "c", "у": "y", "х": "x", "к": "k", "м": "m",
  "т": "t", "н": "h", "в": "b", "і": "i", "ј": "j", "ѕ": "s", "ԁ": "d", "ɡ": "g",
  "α": "a", "ε": "e", "ο": "o", "ι": "i", "κ": "k", "ρ": "p", "τ": "t", "υ": "u", "χ": "x",
  "ν": "v", "β": "b",
};
const LEET: Record<string, string> = {
  "0": "o", "1": "i", "!": "i", "|": "i", "3": "e", "4": "a", "@": "a", "5": "s",
  "$": "s", "7": "t", "8": "b", "9": "g", "€": "e", "£": "l", "+": "t", "®": "r", "©": "c",
};

/** Zero-width and bidi controls: removed outright, so they cannot split a word. */
const INVISIBLE = /[\u00ad\u034f\u061c\u115f-\u1160\u17b4-\u17b5\u180e\u200b-\u200f\u2028-\u202e\u2060-\u206f\u3164\ufeff\uffa0]/g;

/** Plain lowercase letters a–z only, digits and look-alikes turned into letters. */
export function fold(text: string): string {
  let s = text.normalize("NFKC").replace(INVISIBLE, "").toLowerCase();
  s = [...s].map((ch) => HOMOGLYPHS[ch] ?? LEET[ch] ?? ch).join("");
  s = s.normalize("NFD").replace(/\p{M}+/gu, "").replace(/ł/g, "l");
  return s;
}

/** "kuuurwaaa" → "kurwa": stretched letters are the oldest trick there is. */
const collapse = (s: string) => s.replace(/(.)\1+/g, "$1");

// Stems, folded and collapsed. `^` marks a stem that must start a word: "huj" inside
// "Hujanówka" would be a false alarm, "chuj" inside anything never is.
const VULGAR = [
  "kurw", "kurew", "chuj", "^huj", "^hui", "pierdol", "pierdal", "pizd", "^cip", "kutas",
  "^fiut", "cwel", "^pedal", "^ciota", "^cioty", "dziwk", "^szmat", "sukinsyn", "skurw",
  "zjeb", "^jeb", "wyjeb", "zajeb", "pojeb", "rozjeb", "najeb", "przyjeb", "odjeb", "dojeb",
  "spierd", "wypierd", "zapierd", "^gown", "^dup", "^sraj", "sraka", "^zesra", "^debil",
  "^idiot", "^kretyn", "^frajer", "pedofil", "czarnuch", "^zydy", "^zydow", "^parch",
  "hitler", "^heil", "^nazi", "^sieg", "^ss$", "^kkk",
  "fuck", "^fuk", "shit", "cunt", "bitch", "^dick", "^cock", "^pusy", "^niga", "^niger$", "^nigers$", "^fag",
  "whore", "^slut", "porn", "^sex", "^rape", "^anal", "^cum$",
];
// So strong that they count even across word boundaries ("ku rwa", "pi zda").
const STRONG = ["kurw", "chuj", "pierdol", "pizd", "skurw", "fuck", "cunt", "czarnuch"];

const ADVERTS = [
  "promocj", "rabat", "znizk", "kodrabat", "zapraszam", "sprzedam", "sprzedaz", "^kupi",
  "^tanio", "oferta", "gratis", "darmow", "kasyn", "casino", "kredyt", "pozyczk", "bitcoin",
  "krypto", "hodowl", "szczeniet", "^reklam", "^follow", "subskryb", "obserwuj", "^insta",
  "facebook", "tiktok", "youtube", "whatsapp", "telegram", "^onlyfans", "^umow", "^gromer", "^groming",
  "^kup$", "^kupuj", "^zamow", "^rezerw", "^sklep",
];

const NONSENSE = ["qwert", "asdf", "zxcv", "qazws", "wsxed", "hjkl", "uiop", "lorem", "ipsum"];
const NONSENSE_WORDS = new Set(["test", "testy", "testowy", "testowa", "aaa", "xxx", "xyz", "abc", "abcd", "asd", "nic", "brak", "null", "undefined", "none", "xd", "lol", "haha", "hehe"]);

// Abbreviations that legitimately sit before a dot in a name: "ul. Leśna", "al. KEN".
const ABBREVIATIONS = new Set(["ul", "al", "os", "pl", "im", "nr", "sw", "gen", "ks", "sp", "dr", "prof", "bl", "m", "ok", "wz"]);
const TLD = "(pl|com|eu|net|org|info|io|de|uk|shop|store|online|app|xyz|site|biz|me|co|tv|link|top)";

const VOWELS = /[aeiouy]/;

// Words a name should not need and an attack needs: markup, mentions, code fences.
const FORBIDDEN_CHARS = /[<>{}\[\]`@#\\|^~*_=$%]/;

const GENERIC = /^((nowy|stary|duzy|maly|fajny)\s+)?(psi\s+)?(wybieg|park|plac|wybieg dla psow)(\s+(dla|do)\s+ps(ow|a|y))?$|^dog\s*park$/;

const SMALL_WORDS = new Set(["na", "w", "we", "przy", "do", "dla", "z", "ze", "i", "u", "nad", "pod", "od", "za", "o", "im", "ul"]);

function words(folded: string): string[] {
  // Letters only inside a word, so "k.u.r.w.a" and "k-u-r-w-a" become one word.
  return folded.split(/\s+/).map((w) => collapse(w.replace(/[^a-z]/g, ""))).filter(Boolean);
}

function hits(ws: string[], stems: string[]): boolean {
  return stems.some((stem) => {
    const anchoredStart = stem.startsWith("^");
    const anchoredEnd = stem.endsWith("$");
    const core = stem.replace(/^\^/, "").replace(/\$$/, "");
    return ws.some((w) => {
      if (anchoredStart && anchoredEnd) return w === core;
      if (anchoredStart) return w.startsWith(core);
      return w.includes(core);
    });
  });
}

/** Single letters spaced out ("k u r w a") joined back into the word they spell. */
function joinSpelled(ws: string[]): string[] {
  const out: string[] = [];
  let run = "";
  for (const w of ws) {
    if (w.length === 1) {
      run += w;
      continue;
    }
    if (run.length >= 3) out.push(run);
    run = "";
  }
  if (run.length >= 3) out.push(run);
  return out;
}

function looksLikeNonsense(ws: string[], raw: string): boolean {
  const letters = raw.replace(/[^\p{L}]/gu, "").length;
  const visible = raw.replace(/\s/g, "").length;
  if (letters < 2 || letters < visible * 0.6) return true;
  if (/(.)\1{3,}/u.test(raw.toLowerCase())) return true;               // "aaaa", "!!!!"
  if (raw.split(/\s+/).some((w) => /^(\p{L})\1{2,}$/u.test(w.toLowerCase()))) return true; // "xxx"
  if (ws.some((w) => NONSENSE_WORDS.has(w))) return ws.length <= 2;    // "test", "test 2"
  if (ws.some((w) => NONSENSE.some((k) => w.includes(k)))) return true;
  // Polish allows long consonant runs ("chrząszcz" → "chrzaszcz" has 4), seven is mashing.
  if (ws.some((w) => /[^aeiouy]{7,}/.test(w))) return true;
  if (ws.some((w) => w.length >= 5 && !VOWELS.test(w))) return true;
  return false;
}

/** Web addresses and contacts, including the ways people hide them from filters. */
function looksLikeContact(name: string, folded: string): boolean {
  if (new RegExp("\\(\\s*(\\.|dot|kropka|at|malpa)\\s*\\)|\\b(dot|kropka|malpa)\\b").test(folded)) return true;
  if (new RegExp("\\s\\.\\s?" + TLD + "\\b|\\.\\s" + TLD + "\\b").test(folded)) return true;
  for (const m of folded.matchAll(/([a-z0-9-]+)\.([a-z]{2,})/g)) {
    if (!ABBREVIATIONS.has(m[1])) return true;                         // psiehotel.shop, wp.pl
  }
  const digits = (folded.match(/[0-9]/g) ?? []).length;
  if (digits >= 7) return true;                                         // phone, PESEL, IBAN
  if (/\b[A-Z]{2,3}\s?\d{4,5}\b|\b[A-Z]{2,3}\s?\d{2,3}[A-Z]{1,2}\b/.test(name.normalize("NFKC"))) return true; // plates
  return false;
}

/** "PSI PARK NA BIELANACH" → "Psi Park na Bielanach"; mixed case is left as typed. */
function tidyCase(name: string): string {
  const letters = name.replace(/[^\p{L}]/gu, "");
  if (letters.length < 4 || letters !== letters.toUpperCase()) return name;
  return name.toLowerCase().split(" ").map((w, i) =>
    i > 0 && SMALL_WORDS.has(w) ? w : w.charAt(0).toUpperCase() + w.slice(1),
  ).join(" ");
}

const CYRILLIC_SOUNDS: Record<string, string> = {
  "а": "a", "б": "b", "в": "w", "г": "g", "д": "d", "е": "e", "ё": "jo", "ж": "z", "з": "z",
  "и": "i", "й": "j", "к": "k", "л": "l", "м": "m", "н": "n", "о": "o", "п": "p", "р": "r",
  "с": "s", "т": "t", "у": "u", "ф": "f", "х": "h", "ц": "c", "ч": "cz", "ш": "sz", "щ": "szcz",
  "ъ": "", "ы": "y", "ь": "", "э": "e", "ю": "ju", "я": "ja", "і": "i", "ї": "ji", "є": "je",
};
const transliterate = (name: string) =>
  [...name.toLowerCase()].map((ch) => CYRILLIC_SOUNDS[ch] ?? ch).join("");

function isVulgar(folded: string): boolean {
  const ws = words(folded);
  const spelled = joinSpelled(ws);
  const squashed = collapse(folded.replace(/[^a-z]/g, ""));
  // Polish has almost no "v", so "kurva", "kurvva" are the same word in disguise.
  const asW = ws.map((w) => collapse(w.replace(/v/g, "w")));
  const asU = ws.map((w) => collapse(w.replace(/v/g, "u")));
  return hits(ws, VULGAR) || hits(asW, VULGAR) || hits(asU, VULGAR) ||
    hits(spelled, VULGAR.map((s) => s.replace(/^\^|\$$/g, ""))) ||
    STRONG.some((s) => [squashed, squashed.replace(/v/g, "w"), squashed.replace(/v/g, "u")].some((q) => q.includes(s)));
}

/**
 * Checks a park name. `null` in, `null` out: the name is optional. A name that only
 * says "dog park" comes back as null too, which is how the app titles unnamed parks.
 */
export function checkName(raw: string | null): FilterResult {
  if (raw === null) return { ok: true, name: null };
  let name = raw.replace(INVISIBLE, "").normalize("NFC")
    // Emoji and pictographs: decoration, never needed, sometimes the insult itself.
    // (© and ® count as pictographs to Unicode, but "ku®wa" needs them read as letters.)
    .replace(/(?![©®])[\p{Extended_Pictographic}\u{1F1E6}-\u{1F1FF}\u{FE0F}]/gu, "")
    .replace(/\s+/g, " ")
    .trim()
    // Stray punctuation at the ends; quotes stay, they are usually balanced („Hau-Hau”).
    .replace(/^[\s.,;:!?-]+|[\s.,;:!?-]+$/g, "");
  if (!name) return { ok: true, name: null };

  if (FORBIDDEN_CHARS.test(name)) {
    return { ok: false, reason: "Nazwa może zawierać litery, cyfry i zwykłe znaki interpunkcyjne — bez symboli." };
  }

  const folded = fold(name);
  const ws = words(folded);

  // Cyrillic is checked twice: as the Latin letters it looks like ("р" as "p") and as
  // the sounds it spells ("курва" is "kurwa" to anyone who reads it).
  const readings = /\p{Script=Cyrillic}/u.test(name) ? [folded, fold(transliterate(name))] : [folded];
  if (readings.some(isVulgar)) {
    return { ok: false, reason: "Ta nazwa zawiera słowa, których nie możemy opublikować." };
  }
  // Digits survive folding as letters ("601" → "goi"), so contacts are checked on the
  // text with digits kept: NFKC (fullwidth → ASCII), lowercase, no diacritics.
  const withDigits = name.normalize("NFKC").toLowerCase().normalize("NFD").replace(/\p{M}+/gu, "").replace(/ł/g, "l");
  if (looksLikeContact(name, withDigits)) {
    return { ok: false, reason: "Bez adresów stron, e-maili, telefonów i tablic rejestracyjnych, prosimy." };
  }
  if (hits(ws, ADVERTS)) {
    return { ok: false, reason: "Bez reklam, prosimy. Podaj nazwę samego wybiegu." };
  }
  if (/\b1488\b|\b88\b/.test(name)) {
    return { ok: false, reason: "Ta nazwa zawiera słowa, których nie możemy opublikować." };
  }
  if (looksLikeNonsense(ws, name)) {
    return { ok: false, reason: "To nie wygląda na nazwę. Zostaw pole puste, jeśli wybieg nie ma nazwy." };
  }

  name = tidyCase(name);
  if (GENERIC.test(collapse(fold(name).replace(/\s+/g, " ")))) return { ok: true, name: null };
  return { ok: true, name };
}
