import { test } from "node:test";
import assert from "node:assert/strict";
import { checkSubmission, cleanText, issueBody, issueTitle, networkOf } from "../src/submission.ts";

const install = "3f1c2b4a-8d7e-4f6a-9b0c-1d2e3f4a5b6c";
const ok = (body: object) => {
  const r = checkSubmission({ instalacja: install, wersja: "1.1.0", ...body });
  assert.equal(r.ok, true, JSON.stringify(r));
  return (r as { ok: true; value: any }).value;
};
const err = (body: object) => {
  const r = checkSubmission({ instalacja: install, ...body });
  assert.equal(r.ok, false, JSON.stringify(body));
  return (r as { ok: false; error: string }).error;
};

test("a new park needs a position in Poland", () => {
  const s = ok({ typ: "nowy", lat: 50.10265571, lon: 18.5159636, nazwa: "  Górka   dla Burka ", cechy: { fenced: true } });
  assert.equal(s.lat, 50.102656);
  assert.equal(s.nazwa, "Górka dla Burka");
  assert.deepEqual(s.cechy, { fenced: true });
  assert.match(err({ typ: "nowy" }), /położenia/);
  assert.match(err({ typ: "nowy", lat: 52.52, lon: 13.4 }), /poza Polską/);
  assert.match(err({ typ: "nowy", lat: "50", lon: 19 }), /położenia/);
  assert.match(err({ typ: "nowy", lat: 1e308, lon: 19 }), /poza Polską/);
});

test("the name goes through the filter, a generic one becomes null", () => {
  assert.equal(ok({ typ: "nowy", lat: 50, lon: 19, nazwa: "Wybieg dla psów" }).nazwa, null);
  assert.match(err({ typ: "nowy", lat: 50, lon: 19, nazwa: "k.u.r.w.a" }), /nie możemy opublikować/);
  assert.match(err({ typ: "nowy", lat: 50, lon: 19, nazwa: "Psi hotel www.example.pl" }), /adresów stron/);
  assert.match(err({ typ: "nowy", lat: 50, lon: 19, nazwa: "x".repeat(61) }), /60 znaków/);
  // a comment from an old app version is ignored, never published
  assert.equal("komentarz" in ok({ typ: "potwierdzam", parkId: "node/1", komentarz: "cokolwiek" }), false);
});

test("reports about a park name it by OSM or manual id", () => {
  assert.equal(ok({ typ: "potwierdzam", parkId: "way/808455741" }).parkId, "way/808455741");
  assert.equal(ok({ typ: "nie_istnieje", parkId: "manual/z-17" }).parkId, "manual/z-17");
  assert.match(err({ typ: "potwierdzam", parkId: "../../etc" }), /którego wybiegu/);
  assert.match(err({ typ: "cechy", parkId: "node/1", cechy: {} }), /przynajmniej jedną/);
  assert.match(err({ typ: "cechy", parkId: "node/1", cechy: { pool: true } }), /cechy/);
  assert.match(err({ typ: "cechy", parkId: "node/1", cechy: { lit: "yes" } }), /cechy/);
  assert.match(err({ typ: "cechy", parkId: "node/1", cechy: JSON.parse('{"__proto__": {"lit": true}}') }), /cechy/);
});

test("version is a version, install id a UUID", () => {
  assert.equal(ok({ typ: "potwierdzam", parkId: "node/1", wersja: "@everyone <b>" }).wersja, "?");
  const r = checkSubmission({ typ: "potwierdzam", parkId: "node/1", instalacja: "not-a-uuid" });
  assert.equal(r.ok, false);
});

test("cleanText drops invisible characters and zalgo", () => {
  const zw = String.fromCharCode(0x200b), wj = String.fromCharCode(0x2060), combining = String.fromCharCode(0x300);
  assert.equal(cleanText(`a${zw}b${wj}c`, 10), "abc");
  assert.equal(cleanText("a\tb\nc", 10), "a b c");
  // NFC folds the first mark into "à"; the 49 stacked after it are dropped
  assert.equal(cleanText("a" + combining.repeat(50), 60), "\u00e0\u0300"); // at most one mark per letter
  assert.equal(cleanText("a" + combining.repeat(300), 60), undefined); // raw size capped first
  assert.equal(cleanText("   ", 10), null);
  assert.equal(cleanText("x".repeat(11), 10), undefined);
});

test("the issue keeps typed text inside the JSON fence and out of the title", () => {
  const record = {
    v: 1 as const, typ: "nowy" as const, parkId: null, lat: 50.1, lon: 18.5, nazwa: "Psi zakątek",
    cechy: {}, autor: "abc", siec: "def", wersja: "1.1.0", czas: "2026-10-10T20:00:00Z",
  };
  assert.equal(issueTitle(record), "[Nowy wybieg] 50.10000, 18.50000");
  const body = issueBody(record);
  assert.match(body, /mlat=50.1&mlon=18.5/);
  assert.match(body, /"autor": "abc"/);
  assert.doesNotMatch(body, /instalacja/);
});

test("one household counts once: IPv4 /24, IPv6 /64", () => {
  assert.equal(networkOf("83.21.4.17"), "83.21.4");
  assert.equal(networkOf("2a02:a311:4040:1b00:1:2:3:4"), "2a02:a311:4040:1b00");
  assert.equal(networkOf("2a02:a311:4040:1b00::1"), "2a02:a311:4040:1b00");
  assert.equal(networkOf("2a02:a311::1"), "2a02:a311:0:0");
});
