import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { loadConfig } from '../src/config.js';
import { createApp } from '../src/server.js';
import { createMockProvider } from '../src/providers/mock.js';
import { createAnthropicProvider } from '../src/providers/anthropic.js';
import { createOpenAiProvider } from '../src/providers/openai.js';
import { ProviderError } from '../src/providers/errors.js';

const TOKEN = 'test-token-123';
const cfg = (over = {}) => {
  const c = loadConfig({ VOX_APP_TOKENS: TOKEN, ...over });
  return c;
};

async function withServer(app, fn) {
  const server = http.createServer(app);
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${server.address().port}`;
  try {
    await fn(base);
  } finally {
    await new Promise((r) => server.close(r));
  }
}

const post = (base, body, token = TOKEN) =>
  fetch(`${base}/v1/complete`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) },
    body: typeof body === 'string' ? body : JSON.stringify(body),
  });

const chat = (text = 'hello', extra = {}) => ({
  task: 'chat',
  messages: [{ role: 'user', parts: [{ type: 'text', text }] }],
  ...extra,
});
const img = { type: 'image', mediaType: 'image/jpeg', data: 'AAAA' };

test('rejects missing and wrong tokens', async () => {
  await withServer(createApp(cfg(), createMockProvider()), async (base) => {
    assert.equal((await post(base, chat(), null)).status, 401);
    assert.equal((await post(base, chat(), 'nope')).status, 401);
    assert.equal((await fetch(`${base}/v1/status`)).status, 401);
  });
});

test('completes a chat and reports images to the provider', async () => {
  await withServer(createApp(cfg(), createMockProvider()), async (base) => {
    const body = chat('what is this?');
    body.messages[0].parts.push(img);
    const res = await post(base, body);
    assert.equal(res.status, 200);
    assert.equal(res.headers.get('cache-control'), 'no-store');
    const json = await res.json();
    assert.match(json.text, /1 image/);
  });
});

test('validates input', async () => {
  await withServer(createApp(cfg(), createMockProvider()), async (base) => {
    assert.equal((await post(base, '{not json')).status, 400);
    assert.equal((await post(base, { task: 'hack', messages: [] })).status, 400);
    const bad = chat();
    bad.messages[0].parts = [{ type: 'image', mediaType: 'image/jpeg', data: '<script>' }];
    assert.equal((await post(base, bad)).status, 400);
    const assistantLast = { task: 'chat', messages: [{ role: 'assistant', parts: [{ type: 'text', text: 'x' }] }] };
    assert.equal((await post(base, assistantLast)).status, 413);
  });
});

test('enforces per-request image and size limits', async () => {
  const config = cfg({ VOX_MAX_IMAGES: '2', VOX_MAX_IMAGE_B64: '10' });
  await withServer(createApp(config, createMockProvider()), async (base) => {
    const three = chat();
    three.messages[0].parts.push(img, img, img);
    const r = await post(base, three);
    assert.equal(r.status, 413);
    assert.equal((await r.json()).error.code, 'payload_too_large');
    const big = chat();
    big.messages[0].parts.push({ ...img, data: 'A'.repeat(11) });
    assert.equal((await post(base, big)).status, 413);
  });
});

test('rate limits per minute and exposes Retry-After', async () => {
  const config = cfg({ VOX_RPM: '2' });
  await withServer(createApp(config, createMockProvider()), async (base) => {
    assert.equal((await post(base, chat())).status, 200);
    assert.equal((await post(base, chat())).status, 200);
    const r = await post(base, chat());
    assert.equal(r.status, 429);
    assert.ok(Number(r.headers.get('retry-after')) > 0);
    assert.equal((await r.json()).error.code, 'rate_limited');
  });
});

test('daily image budget is enforced and shown in status', async () => {
  const config = cfg({ VOX_IMAGES_PER_DAY: '2' });
  await withServer(createApp(config, createMockProvider()), async (base) => {
    const one = chat();
    one.messages[0].parts.push(img, img);
    assert.equal((await post(base, one)).status, 200);
    assert.equal((await post(base, one)).status, 429);
    const status = await (await fetch(`${base}/v1/status`, { headers: { authorization: `Bearer ${TOKEN}` } })).json();
    assert.equal(status.limits.imagesToday, 2);
    assert.equal(status.retention, 'none');
  });
});

test('maps provider failures to stable error codes', async () => {
  const failing = { name: 'x', model: 'x', complete: async () => { throw new ProviderError('model_unavailable', 'gone', 502); } };
  await withServer(createApp(cfg(), failing), async (base) => {
    const r = await post(base, chat());
    assert.equal(r.status, 502);
    assert.equal((await r.json()).error.code, 'model_unavailable');
  });
  const crashing = { name: 'x', model: 'x', complete: async () => { throw new Error('secret detail'); } };
  const origErr = console.error; console.error = () => {};
  await withServer(createApp(cfg(), crashing), async (base) => {
    const r = await post(base, chat());
    assert.equal(r.status, 500);
    assert.doesNotMatch(JSON.stringify(await r.json()), /secret detail/);
  });
  console.error = origErr;
});

test('system prompt is built server-side for spoken video tasks', async () => {
  let seen;
  const spy = { name: 's', model: 's', complete: async (a) => { seen = a; return { text: 'ok', model: 's', usage: {} }; } };
  await withServer(createApp(cfg(), spy), async (base) => {
    await post(base, chat('x', { task: 'video', spoken: true, responseLength: 'detailed' }));
    assert.match(seen.system, /\[mm:ss\]/);
    assert.match(seen.system, /read aloud/);
    assert.match(seen.system, /untrusted DATA/);
    assert.ok(seen.maxTokens >= 1200);
  });
});

test('anthropic provider sends key only upstream and shapes images correctly', async () => {
  let captured;
  const upstream = http.createServer((req, res) => {
    let b = '';
    req.on('data', (c) => (b += c));
    req.on('end', () => {
      captured = { headers: req.headers, body: JSON.parse(b), url: req.url };
      res.setHeader('content-type', 'application/json');
      res.end(JSON.stringify({ model: 'm', content: [{ type: 'text', text: 'hi there' }], usage: { input_tokens: 3, output_tokens: 2 } }));
    });
  });
  await new Promise((r) => upstream.listen(0, '127.0.0.1', r));
  try {
    const p = createAnthropicProvider({ apiKey: 'SECRET', model: 'm', baseUrl: `http://127.0.0.1:${upstream.address().port}` }, 5000);
    const out = await p.complete({ system: 'sys', maxTokens: 50, messages: [{ role: 'user', parts: [{ type: 'text', text: 'q' }, img] }] });
    assert.equal(out.text, 'hi there');
    assert.equal(captured.url, '/v1/messages');
    assert.equal(captured.headers['x-api-key'], 'SECRET');
    assert.deepEqual(captured.body.messages[0].content[1], { type: 'image', source: { type: 'base64', media_type: 'image/jpeg', data: 'AAAA' } });
    assert.equal(captured.body.system, 'sys');
  } finally {
    upstream.close();
  }
});

test('openai provider shapes data-URI images and maps 429', async () => {
  let status = 200;
  let captured;
  const upstream = http.createServer((req, res) => {
    let b = '';
    req.on('data', (c) => (b += c));
    req.on('end', () => {
      captured = JSON.parse(b);
      res.statusCode = status;
      res.setHeader('content-type', 'application/json');
      res.end(JSON.stringify({ choices: [{ message: { content: 'yo' } }] }));
    });
  });
  await new Promise((r) => upstream.listen(0, '127.0.0.1', r));
  try {
    const p = createOpenAiProvider({ apiKey: 'K', model: 'm', baseUrl: `http://127.0.0.1:${upstream.address().port}` }, 5000);
    const msgs = [{ role: 'user', parts: [img] }];
    assert.equal((await p.complete({ system: 's', maxTokens: 5, messages: msgs })).text, 'yo');
    assert.equal(captured.messages[1].content[0].image_url.url, 'data:image/jpeg;base64,AAAA');
    status = 429;
    await assert.rejects(p.complete({ system: 's', maxTokens: 5, messages: msgs }), (e) => e.code === 'provider_unavailable');
  } finally {
    upstream.close();
  }
});
