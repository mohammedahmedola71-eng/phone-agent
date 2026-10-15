/** Offline provider for development and tests. Echoes what it received so the pipeline can be verified end to end. */
export function createMockProvider() {
  return {
    name: 'mock',
    model: 'mock-1',
    async complete({ messages, maxTokens }) {
      const last = messages[messages.length - 1];
      const images = messages.reduce((n, m) => n + m.parts.filter((p) => p.type === 'image').length, 0);
      const text = last.parts.filter((p) => p.type === 'text').map((p) => p.text).join(' ').slice(0, 160);
      return {
        text: `[mock] I received ${images} image(s). You said: ${text}`,
        model: 'mock-1',
        usage: { inputTokens: 0, outputTokens: 0, maxTokens },
      };
    },
  };
}
