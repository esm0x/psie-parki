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
  assert.equal(r.ok, false);
  return (r as { ok: false; error: string }).error;
};

test("a new park needs a position in Poland", () => {
  const s = ok({ typ: "nowy", lat: 50.10265571, lon: 18.5159636, nazwa: "  Górka   dla Burka ", cechy: { fenced: true } });
  assert.equal(s.lat, 50.102656);
  assert.equal(s.nazwa, "Górka dla Burka");
  assert.deepEqual(s.cechy, { fenced: true });
  assert.match(err({ typ: "nowy" }), /położenia/);
  assert.match(err({ typ: "nowy", lat: 52.52, lon: 13.4 }), /poza Polską/);
});

test("reports about a park name it by OSM or manual id", () => {
  assert.equal(ok({ typ: "potwierdzam", parkId: "way/808455741" }).parkId, "way/808455741");
  assert.equal(ok({ typ: "nie_istnieje", parkId: "manual/z-17" }).parkId, "manual/z-17");
  assert.match(err({ typ: "potwierdzam", parkId: "../../etc" }), /którego wybiegu/);
  assert.match(err({ typ: "cechy", parkId: "node/1", cechy: {} }), /przynajmniej jedną/);
  assert.match(err({ typ: "cechy", parkId: "node/1", cechy: { pool: true } }), /cechy/);
  assert.match(err({ typ: "cechy", parkId: "node/1", cechy: { lit: "yes" } }), /cechy/);
});

test("links, e-mails and phone numbers never reach the model", () => {
  assert.match(err({ typ: "nowy", lat: 50, lon: 19, nazwa: "Psi hotel www.example.pl" }), /linków/);
  assert.match(err({ typ: "potwierdzam", parkId: "node/1", komentarz: "pisz: jan@example.com" }), /linków/);
  assert.match(err({ typ: "potwierdzam", parkId: "node/1", komentarz: "dzwoń 601 234 567" }), /linków/);
  // a street number is not a phone number
  assert.equal(ok({ typ: "nowy", lat: 50, lon: 19, nazwa: "Wybieg przy ul. 3 Maja 65" }).nazwa, "Wybieg przy ul. 3 Maja 65");
});

test("lengths, control characters and the install id", () => {
  assert.equal(cleanText("a\u0000b​c", 10), "a b c");
  assert.equal(cleanText("   ", 10), null);
  assert.equal(cleanText("x".repeat(11), 10), undefined);
  assert.match(err({ typ: "nowy", lat: 50, lon: 19, nazwa: "x".repeat(61) }), /60 znaków/);
  const r = checkSubmission({ typ: "potwierdzam", parkId: "node/1", instalacja: "not-a-uuid" });
  assert.equal(r.ok, false);
});

test("the issue carries the anonymised record and a map link", () => {
  const record = {
    v: 1 as const, typ: "nowy" as const, parkId: null, lat: 50.1, lon: 18.5, nazwa: "Psi zakątek",
    cechy: {}, komentarz: null, autor: "abc", siec: "def", wersja: "1.1.0", czas: "2026-10-10T20:00:00Z",
  };
  assert.equal(issueTitle(record), "[Nowy wybieg] Psi zakątek – 50.10000, 18.50000");
  const body = issueBody(record);
  assert.match(body, /mlat=50.1&mlon=18.5/);
  assert.match(body, /"autor": "abc"/);
  assert.doesNotMatch(body, /instalacja/);
});

test("one network counts once", () => {
  assert.equal(networkOf("83.21.4.17"), "83.21.4");
  assert.equal(networkOf("2a02:a311:4040:1b00::1"), "2a02:a311:4040");
});
