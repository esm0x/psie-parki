// Pictures of the app's map tab for the store screenshots.
//
//     NODE_PATH=$(npm root -g) node play/map/render.mjs
//
// Robolectric, which renders the other screenshots, cannot run MapLibre's OpenGL map,
// so this draws the same map with MapLibre GL JS: the OpenFreeMap style the app uses,
// the parks from data/parks.json, and the layer colours and sizes copied from
// ParksMap in app/.../ui/MapScreen.kt. Keep the two in step.
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

// require, not import: NODE_PATH, which points at a global playwright, works only for require.
const { chromium } = createRequire(import.meta.url)('playwright');
const here = dirname(fileURLToPath(import.meta.url));
const repo = join(here, '..', '..');
const parks = JSON.parse(readFileSync(join(repo, 'data', 'parks.json'), 'utf8')).parks;

// Same viewport as the Robolectric devices in StoreScreenshots.kt.
const devices = {
  phone: { width: 360, height: 640, scale: 3 },
  tablet7: { width: 617, height: 1097, scale: 1.75 },
  tablet10: { width: 720, height: 1280, scale: 2 },
};
const me = [18.5418, 50.0971]; // Rybnik market square, the origin of every screenshot
// Zoomed out over Upper Silesia, where OSM has the most parks: clusters and pins both.
const view = { center: [18.64, 50.17], zoom: 9.4 };

const html = `<!doctype html><html><head>
<link href="https://unpkg.com/maplibre-gl@5.6.0/dist/maplibre-gl.css" rel="stylesheet">
<script src="https://unpkg.com/maplibre-gl@5.6.0/dist/maplibre-gl.js"></script>
<style>html,body,#m{margin:0;width:100%;height:100%}</style></head><body><div id="m"></div></body></html>`;

const browser = await chromium.launch({ args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
for (const [name, d] of Object.entries(devices)) {
  const page = await browser.newPage({ viewport: { width: d.width, height: d.height }, deviceScaleFactor: d.scale });
  await page.setContent(html, { waitUntil: 'load' });
  await page.evaluate(({ parks, me, view }) => new Promise((resolve) => {
    const green = '#2E6B3F', orange = '#E08A1E';
    const map = new maplibregl.Map({
      container: 'm', style: 'https://tiles.openfreemap.org/styles/liberty',
      center: view.center, zoom: view.zoom, attributionControl: false, fadeDuration: 0,
    });
    map.on('load', () => {
      map.addSource('parks', {
        type: 'geojson', cluster: true, clusterMaxZoom: 11, clusterRadius: 48,
        data: { type: 'FeatureCollection', features: parks.map((p) => ({
          type: 'Feature', geometry: { type: 'Point', coordinates: [p.lon, p.lat] },
          properties: { id: p.id, fenced: p.fenced === true },
        })) },
      });
      map.addSource('me', { type: 'geojson', data: { type: 'Feature', geometry: { type: 'Point', coordinates: me } } });
      map.addLayer({ id: 'clusters', type: 'circle', source: 'parks', filter: ['has', 'point_count'], paint: {
        'circle-color': green, 'circle-opacity': 0.88, 'circle-stroke-color': '#fff', 'circle-stroke-width': 2,
        'circle-radius': ['step', ['get', 'point_count'], 15, 10, 19, 50, 24, 200, 30] } });
      map.addLayer({ id: 'count', type: 'symbol', source: 'parks', filter: ['has', 'point_count'], layout: {
        'text-field': ['to-string', ['get', 'point_count_abbreviated']], 'text-font': ['Noto Sans Bold'],
        'text-size': 13, 'text-allow-overlap': true, 'text-ignore-placement': true }, paint: { 'text-color': '#fff' } });
      map.addLayer({ id: 'points', type: 'circle', source: 'parks', filter: ['!', ['has', 'point_count']], paint: {
        'circle-color': ['case', ['get', 'fenced'], green, orange], 'circle-radius': 8,
        'circle-stroke-color': '#fff', 'circle-stroke-width': 2.5 } });
      map.addLayer({ id: 'me', type: 'circle', source: 'me', paint: {
        'circle-color': '#1A73E8', 'circle-radius': 7, 'circle-stroke-color': '#fff', 'circle-stroke-width': 3 } });
      map.once('idle', resolve);
    });
  }), { parks, me, view });
  await page.screenshot({ path: join(here, `${name}.png`) });
  console.log(`play/map/${name}.png`);
  await page.close();
}
await browser.close();
