/**
 * Phase 24.1 Plan 03 — local Overpass fixture HTTP server for the real-backend Playwright spec.
 *
 * Listens on http://localhost:18086 and replies to POST /api/interpreter with the canned
 * Overpass XML at frontend/e2e/server/fixtures/overpass-real-modlin.xml. The Playwright
 * webServer block in playwright.config.ts spawns this process before tests run; the Spring
 * backend is started with --osm.overpass.urls=http://localhost:18086 so its outbound
 * Overpass calls hit this server instead of overpass-api.de.
 *
 * Pure Node stdlib (http + fs + path), no npm dependencies, CommonJS so Playwright's webServer
 * spawn can run it via `node ./server/overpass-fixture-server.cjs` without TS transpile.
 */
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');

const PORT = Number(process.env.OVERPASS_FIXTURE_PORT || 18086);
const FIXTURE = path.join(__dirname, 'fixtures', 'overpass-real-modlin.xml');

const xml = fs.readFileSync(FIXTURE, 'utf-8');

const server = http.createServer((req, res) => {
  if (req.method === 'POST' && req.url && req.url.endsWith('/api/interpreter')) {
    // Drain the body (we don't need to inspect it — the spec asserts on UI outcome, not on
    // the Overpass query string. Plan 01's unit test pins the query shape; Plan 02's
    // OsmPipelineSmokeIT pins the captured request body shape).
    req.on('data', () => {});
    req.on('end', () => {
      res.writeHead(200, { 'Content-Type': 'application/xml; charset=utf-8' });
      res.end(xml);
    });
    return;
  }
  if (req.method === 'GET' && req.url === '/health') {
    res.writeHead(200, { 'Content-Type': 'text/plain' });
    res.end('ok');
    return;
  }
  res.writeHead(404, { 'Content-Type': 'text/plain' });
  res.end('not found');
});

server.listen(PORT, () => {
  console.log(`[overpass-fixture-server] listening on http://localhost:${PORT}`);
});

process.on('SIGTERM', () => server.close(() => process.exit(0)));
process.on('SIGINT', () => server.close(() => process.exit(0)));
