import { z } from 'zod';

export const TASKS = ['chat', 'screen', 'live', 'video', 'reply', 'notifications'];

const textPart = z.object({ type: z.literal('text'), text: z.string() });
const imagePart = z.object({
  type: z.literal('image'),
  mediaType: z.enum(['image/jpeg', 'image/png', 'image/webp']),
  data: z.string().regex(/^[A-Za-z0-9+/=\r\n]+$/, 'image data must be base64'),
});
const part = z.discriminatedUnion('type', [textPart, imagePart]);

const message = z.object({
  role: z.enum(['user', 'assistant']),
  parts: z.array(part).min(1),
});

export const completeRequest = z.object({
  task: z.enum(TASKS),
  responseLength: z.enum(['short', 'medium', 'detailed']).default('medium'),
  spoken: z.boolean().default(false),
  messages: z.array(message).min(1),
});

/** Checks the limits that depend on configuration. Returns an error string or null. */
export function checkLimits(req, limits) {
  if (req.messages.length > limits.maxMessages) return `Too many messages (max ${limits.maxMessages}).`;
  if (req.messages[req.messages.length - 1].role !== 'user') return 'The last message must be from the user.';
  let images = 0;
  let textChars = 0;
  for (const m of req.messages) {
    if (m.parts.length > limits.maxPartsPerMessage) return `Too many parts in one message (max ${limits.maxPartsPerMessage}).`;
    for (const p of m.parts) {
      if (p.type === 'image') {
        images += 1;
        if (p.data.length > limits.maxImageBase64Chars) return 'An image is too large.';
      } else {
        textChars += p.text.length;
      }
    }
  }
  if (images > limits.maxImagesPerRequest) return `Too many images (max ${limits.maxImagesPerRequest}).`;
  if (textChars > limits.maxTextChars) return `Text too long (max ${limits.maxTextChars} characters).`;
  return null;
}

export function countImages(req) {
  return req.messages.reduce((n, m) => n + m.parts.filter((p) => p.type === 'image').length, 0);
}
