import { createAnthropicProvider } from './anthropic.js';
import { createOpenAiProvider } from './openai.js';
import { createMockProvider } from './mock.js';

/** To add a provider: implement `{ name, model, complete({system, messages, maxTokens}) -> {text, model, usage} }` and register it here. */
export function createProvider(config) {
  switch (config.provider) {
    case 'anthropic':
      return createAnthropicProvider(config.anthropic, config.providerTimeoutMs);
    case 'openai':
      return createOpenAiProvider(config.openai, config.providerTimeoutMs);
    default:
      return createMockProvider();
  }
}
