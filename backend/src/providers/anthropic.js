import { ProviderError } from './errors.js';

export function createAnthropicProvider({ apiKey, model, baseUrl }, timeoutMs) {
  return {
    name: 'anthropic',
    model,
    async complete({ system, messages, maxTokens }) {
      const body = {
        model,
        max_tokens: maxTokens,
        system,
        messages: messages.map((m) => ({
          role: m.role,
          content: m.parts.map((p) =>
            p.type === 'text'
              ? { type: 'text', text: p.text }
              : { type: 'image', source: { type: 'base64', media_type: p.mediaType, data: p.data } },
          ),
        })),
      };
      let res;
      try {
        res = await fetch(`${baseUrl}/v1/messages`, {
          method: 'POST',
          headers: { 'content-type': 'application/json', 'x-api-key': apiKey, 'anthropic-version': '2023-06-01' },
          body: JSON.stringify(body),
          signal: AbortSignal.timeout(timeoutMs),
        });
      } catch (e) {
        throw new ProviderError('provider_unavailable', e?.name === 'TimeoutError' ? 'The AI provider timed out.' : 'Could not reach the AI provider.', 503);
      }
      if (!res.ok) throw mapStatus(res.status);
      const json = await res.json();
      const text = (json.content ?? []).filter((c) => c.type === 'text').map((c) => c.text).join('').trim();
      if (!text) throw new ProviderError('provider_unavailable', 'The AI provider returned an empty answer.', 502);
      return { text, model: json.model ?? model, usage: { inputTokens: json.usage?.input_tokens, outputTokens: json.usage?.output_tokens } };
    },
  };
}

function mapStatus(status) {
  if (status === 401 || status === 403) return new ProviderError('provider_misconfigured', 'The gateway\'s AI provider credentials were rejected.', 502);
  if (status === 404) return new ProviderError('model_unavailable', 'The configured AI model is unavailable.', 502);
  if (status === 429 || status === 529 || status >= 500) return new ProviderError('provider_unavailable', 'The AI provider is busy. Try again shortly.', 503);
  return new ProviderError('provider_rejected', 'The AI provider rejected the request.', 502);
}
