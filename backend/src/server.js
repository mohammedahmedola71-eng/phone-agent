import express from 'express';
import { authenticate, createUsageMeter } from './usage.js';
import { completeRequest, checkLimits, countImages } from './schema.js';
import { buildSystemPrompt, maxTokensFor } from './prompts.js';
import { ProviderError } from './providers/errors.js';

const err = (res, status, code, message, extra = {}) => res.status(status).json({ error: { code, message, ...extra } });

export function createApp(config, provider, { now } = {}) {
  const app = express();
  const usage = createUsageMeter(config.limits, now);

  app.disable('x-powered-by');
  app.use((_req, res, next) => {
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Strict-Transport-Security', 'max-age=31536000');
    next();
  });

  // Auth runs before body parsing so unauthenticated callers can't make us buffer 30 MB.
  app.use('/v1', (req, res, next) => {
    const id = authenticate(req.headers.authorization, config.appTokens);
    if (!id) return err(res, 401, 'unauthorized', 'Missing or invalid app token.');
    req.clientId = id;
    next();
  });
  app.use(express.json({ limit: config.limits.bodyLimit }));

  app.get('/healthz', (_req, res) => res.json({ ok: true }));

  app.get('/v1/status', (req, res) => {
    res.json({
      ok: true,
      provider: provider.name,
      model: provider.model,
      retention: 'none',
      limits: { ...usage.snapshot(req.clientId), maxImagesPerRequest: config.limits.maxImagesPerRequest },
    });
  });

  app.post('/v1/complete', async (req, res) => {
    const parsed = completeRequest.safeParse(req.body);
    if (!parsed.success) {
      return err(res, 400, 'invalid_request', parsed.error.issues[0]?.message ?? 'Invalid request.');
    }
    const body = parsed.data;
    const limitProblem = checkLimits(body, config.limits);
    if (limitProblem) return err(res, 413, 'payload_too_large', limitProblem);

    const denied = usage.tryConsume(req.clientId, countImages(body));
    if (denied) {
      res.setHeader('Retry-After', String(denied.retryAfterSec));
      return err(res, 429, 'rate_limited', denied.reason, { retryAfterSec: denied.retryAfterSec });
    }

    try {
      const out = await provider.complete({
        system: buildSystemPrompt(body),
        messages: body.messages,
        maxTokens: maxTokensFor(body),
      });
      res.json({ text: out.text, model: out.model, usage: out.usage });
    } catch (e) {
      if (e instanceof ProviderError) return err(res, e.status, e.code, e.message);
      console.error('unexpected provider failure:', e?.name); // never log request content
      err(res, 500, 'internal', 'Unexpected gateway error.');
    }
  });

  // eslint-disable-next-line no-unused-vars
  app.use((e, _req, res, _next) => {
    if (e?.type === 'entity.too.large') return err(res, 413, 'payload_too_large', 'Request body too large.');
    if (e instanceof SyntaxError) return err(res, 400, 'invalid_request', 'Body must be valid JSON.');
    console.error('request error:', e?.name);
    err(res, 500, 'internal', 'Unexpected gateway error.');
  });

  return app;
}
