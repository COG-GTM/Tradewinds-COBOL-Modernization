// Parity-only Jest setup file: supplies the Node Web APIs (TextEncoder/TextDecoder, streams, MessagePort) that
// enzyme -> cheerio -> undici expects but CRA's jsdom environment does not expose. Frontend sources are untouched.
const util = require('util');
const g = globalThis;
if (!g.TextEncoder) g.TextEncoder = util.TextEncoder;
if (!g.TextDecoder) g.TextDecoder = util.TextDecoder;
try { const s = require('stream/web'); for (const k of Object.keys(s)) if (!g[k]) g[k] = s[k]; } catch (e) {}
try { const w = require('worker_threads'); for (const k of ['MessageChannel', 'MessagePort']) if (!g[k]) g[k] = w[k]; } catch (e) {}
if (!g.Blob) g.Blob = require('buffer').Blob;
