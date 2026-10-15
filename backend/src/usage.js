import { createHash, timingSafeEqual } from 'node:crypto';

const sha = (s) => createHash('sha256').update(s).digest();

/** Constant-time token check. Returns a stable, non-reversible client id, or null. */
export function authenticate(header, tokens) {
  const m = /^Bearer\s+(.+)$/i.exec(header ?? '');
  if (!m) return null;
  const presented = sha(m[1].trim());
  let matched = null;
  for (const t of tokens) {
    const h = sha(t);
    if (timingSafeEqual(presented, h)) matched = h.toString('hex').slice(0, 16);
  }
  return matched;
}

/** In-memory usage meter: per-minute request window plus per-UTC-day request/image totals. Resets on restart. */
export function createUsageMeter(limits, now = () => Date.now()) {
  const clients = new Map();
  const day = () => new Date(now()).toISOString().slice(0, 10);

  function state(id) {
    let s = clients.get(id);
    if (!s || s.day !== day()) {
      s = { day: day(), requests: 0, images: 0, window: [] };
      clients.set(id, s);
    }
    return s;
  }

  return {
    /** Returns null if allowed (and counts it) or `{ reason, retryAfterSec }`. */
    tryConsume(id, images) {
      const s = state(id);
      const t = now();
      s.window = s.window.filter((x) => t - x < 60_000);
      if (s.window.length >= limits.requestsPerMinute) {
        return { reason: 'Too many requests. Slow down a little.', retryAfterSec: Math.ceil((60_000 - (t - s.window[0])) / 1000) };
      }
      if (s.requests >= limits.requestsPerDay) return { reason: 'Daily request limit reached.', retryAfterSec: 3600 };
      if (s.images + images > limits.imagesPerDay) return { reason: 'Daily image limit reached.', retryAfterSec: 3600 };
      s.window.push(t);
      s.requests += 1;
      s.images += images;
      return null;
    },
    snapshot(id) {
      const s = state(id);
      return {
        requestsToday: s.requests,
        imagesToday: s.images,
        requestsPerDay: limits.requestsPerDay,
        imagesPerDay: limits.imagesPerDay,
        requestsPerMinute: limits.requestsPerMinute,
      };
    },
  };
}
