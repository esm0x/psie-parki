import { test } from "node:test";
import assert from "node:assert/strict";
import { checkName, fold } from "../src/filter.ts";

const ZW = String.fromCharCode(0x200b);     // zero-width space
const RLO = String.fromCharCode(0x202e);    // right-to-left override
const CYR_K = String.fromCharCode(0x043a);  // Cyrillic small ka, looks like "k"
const fullwidth = (s: string) => [...s].map((c) => String.fromCharCode(c.charCodeAt(0) + 0xfee0)).join("");

const accepted = (raw: string) => {
  const r = checkName(raw);
  assert.equal(r.ok, true, `should accept: ${JSON.stringify(raw)} → ${JSON.stringify(r)}`);
  return (r as { ok: true; name: string | null }).name;
};
const rejected = (raw: string) => {
  const r = checkName(raw);
  assert.equal(r.ok, false, `should reject: ${JSON.stringify(raw)}`);
};

test("real Polish park names pass untouched", () => {
  for (const name of [
    "Górka dla Burka", "Psistanek", "Wybieg przy ul. 3 Maja 65", "Psi park na Bielanach",
    "Park Kościuszki", "Psia Łączka", "Wybieg Dolina Służewska", "Hau Hau Park", "Psi raj",
    "Wybieg dla psów Łączek", "Polana Hutnik", "Ogródek na Ogrodowej", "PSAdyba",
    "Skwer Ormiański", "Park Cioci Zosi", "Kurza Stopka", "Szmaragdowy wybieg",
    "Wybieg Pod Dębem", "Na czterech łapach", "Psi Szpilplac", "Chrząszczyki",
    "Wybieg im. Jana Pawła II", "Psia Górka (Chwałowice)", "Wybieg nr 2", "Dog Point",
    "Hubertus", "Psiakrew", "Wybieg w Sadach Żoliborskich", "Duży wybieg - Sielce",
    // from the adversarial review: must not trip the filter
    "Wybieg przy ul. Leśnej", "Wybieg przy al. KEN", "Wybieg przy SP 15", "Wybieg nr 2 (os. Słoneczne)",
    "Psi Park „Hau-Hau”", "Wybieg Szczęśliwice", "Wybieg Brzęczkowice", "Wybieg na Grzybowie",
    "Wybieg Strzyża", "Wybieg Chrzanów", "Psia Łąka – Ursynów", "Wybieg Kurów", "Wybieg Suków",
    "Wybieg dla suk i psów", "Wybieg Osuchów", "Psi Gaj im. Reksia", "Dog Park Mokotów",
    "Łazienki Dog Run", "Wybieg Kozia Górka", "Psi Fiord", "Wybieg przy Biedronce",
    "Wybieg Zakole Wawerskie", "Pusty plac przy szkole", "Park Nigeryjski",
  ]) {
    assert.equal(accepted(name), name);
  }
});

test("names that only say what the place is become null", () => {
  for (const name of ["Wybieg dla psów", "psi wybieg", "PARK DLA PSÓW", "Dog park", "  wybieg  ", "Nowy wybieg", "...", "🐕🐕🐕", "!!!!"]) {
    assert.equal(accepted(name), null, name);
  }
  assert.equal(accepted(""), null);
});

test("shouting is tidied, emoji dropped, punctuation trimmed", () => {
  assert.equal(accepted("PSI PARK NA BIELANACH"), "Psi Park na Bielanach");
  assert.equal(accepted("🐶 Psi zakątek 🐾!!"), "Psi zakątek");
  assert.equal(accepted(`Psi${ZW} zakątek`), "Psi zakątek");
  // A bidi override would show "kurwa" reversed; stripped, it is just harmless letters.
  assert.equal(accepted(`${RLO}Psi zakątek`), "Psi zakątek");
});

test("vulgarity, however it is spelled", () => {
  for (const name of [
    "kurwa", "Kurwa park", "k.u.r.w.a", "k u r w a", "k-u-r-w-a", "KURWAAAA", "kurw@ mać",
    "ku rwa", `ku${ZW}rwa`, `${CYR_K}urwa`, fullwidth("kurwa"), "kurvva",
    "chuj", "CHUJ", "huj", "jebać psy", "wyjebane", "pierdolę", "spierdalaj", "pizda",
    "c1pa", "dziwka", "skurwysyn", "Heil Hitler", "1488", "fuck this", "shit park",
    "nigga", "Park pedałów", "ciota",
    // from the adversarial review
    "kvrwa", "kurwo", "ku®wa", "𝐤𝐮𝐫𝐰𝐚", "ⓚⓤⓡⓦⓐ", "kürwa", "k🐕u🐕r🐕w🐕a", "j3bany", "zajebisty",
    "cipa", "p1zda", "fvck", "f u c k", "sh1t", "b!tch", "a" + String.fromCharCode(0x300).repeat(58),
    // Cyrillic "у" read as "u", found by probing the documented examples
    "к" + String.fromCharCode(0x443) + "рwa", "f" + String.fromCharCode(0x443) + "ck", "Skurwiel",
  ]) {
    rejected(name);
  }
});

test("ads, links, contacts and markup", () => {
  for (const name of [
    "Sprzedam szczeniaki", "Promocja -50%", "Groomer Ania", "Grooming u Basi", "Hodowla Golden", "Zapraszamy!", "Kasyno online",
    "Obserwuj na insta", "Psi park @esm0x", "Wybieg #1", "Park <script>", "Park ```json",
    "Wybieg {test}", "nazwa_z_podkreśleniem", "Ch*j", "k***a",
    "Hotel dla psów -20% ZAPRASZAMY", "Groomer Azor, umów się", "kup teraz", "psiehotel.pl",
    "psiehotel(.)pl", "psiehotel . pl", "psiehotel.shop", "psiehotel.de", "jan(at)wp.pl",
    "tel 601234567", "601.234.567", "６０１２３４５６７", "WX 12345 parkuje tu", "PESEL 90010112345",
  ]) {
    rejected(name);
  }
});

test("nonsense and tests", () => {
  for (const name of ["asdfgh", "qwerty", "test", "Test 2", "aaaa", "xxx", "zzzzzzz", "1234567", "brzmckwt", "Lorem ipsum", "xD", "qwertyuiop", "Test 123"]) {
    rejected(name);
  }
});

test("folding maps look-alikes to plain letters", () => {
  assert.equal(fold("Ł0dź"), "lodz");
  assert.equal(fold(`${CYR_K}ot`), "kot");
  assert.equal(fold(fullwidth("abc")), "abc");
});
