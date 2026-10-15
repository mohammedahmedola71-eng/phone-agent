import { ProviderError } from './errors.js';

/** Works with OpenAI and any OpenAI-compatible endpoint that accepts image_url data URIs. */
export function createOpenAiProvider({ apiKey, model, baseUrl }, timeoutMs) {
  return {
    name: 'openai',
    model,
    async complete({ system, messages, maxTokens }) {
      const body = {
        model,
        max_tokens: maxTokens,
        messages: [
          { role: 'system', content: system },
          ...messages.map((m) => ({
            role: m.role,
            content: m.parts.map((p) =>
              p.type === 'text'
                ? { type: 'text', text: p.text }
                : { type: 'image_url', image_url: { url: `data:${p.mediaType};base64,${p.data}` } },
            ),
          })),
        ],
      };
      let res;
      try {
        res = await fetch(`${baseUrl}/chat/completions`, {
          method: 'POST',
          headers: { 'content-type': 'application/json', authorization: `Bearer ${apiKey}` },
          body: JSON.stringify(body),
          signal: AbortSignal.timeout(timeoutMs),
        });
      } catch (e) {
        throw new ProviderError('provider_unavailable', e?.name === 'TimeoutError' ? 'The AI provider timed out.' : 'Could not reach the AI provider.', 503);
      }
      if (!res.ok) {
        if (res.status === 401 || res.status === 403) throw new ProviderError('provider_misconfigured', 'The gateway\'s AI provider credentials were rejected.', 502);
        if (res.status === 404) throw new ProviderError('model_unavailable', 'The configured AI model is unavailable.', 502);
        if (res.status === 429 || res.status >= 500) throw new ProviderError('provider_unavailable', 'The AI provider is busy. Try again shortly.', 503);
        throw new ProviderError('provider_rejected', 'The AI provider rejected the request.', 502);
      }
      const json = await res.json();
      const text = (json.choices?.[0]?.message?.content ?? '').trim();
      if (!text) throw new ProviderError('provider_unavailable', 'The AI provider returned an empty answer.', 502);
      return { text, model: json.model ?? model, usage: { inputTokens: json.usage?.prompt_tokens, outputTokens: json.usage?.completion_tokens } };
    },
  };
}
