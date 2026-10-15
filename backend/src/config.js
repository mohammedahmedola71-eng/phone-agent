// Gateway configuration. Every provider secret comes from the environment and never leaves this process.

const int = (v, d) => {
  const n = Number.parseInt(v ?? '', 10);
  return Number.isFinite(n) && n > 0 ? n : d;
};

export function loadConfig(env = process.env) {
  const tokens = (env.VOX_APP_TOKENS ?? '')
    .split(',')
    .map((t) => t.trim())
    .filter(Boolean);

  return {
    port: int(env.PORT, 8787),
    // Per-install bearer tokens the Android app presents. Generate with `openssl rand -hex 24`.
    appTokens: tokens,
    provider: (env.VOX_PROVIDER ?? 'mock').toLowerCase(), // anthropic | openai | mock
    anthropic: {
      apiKey: env.ANTHROPIC_API_KEY ?? '',
      model: env.ANTHROPIC_MODEL ?? 'claude-sonnet-5-5',
      baseUrl: env.ANTHROPIC_BASE_URL ?? 'https://api.anthropic.com',
    },
    openai: {
      apiKey: env.OPENAI_API_KEY ?? '',
      model: env.OPENAI_MODEL ?? 'gpt-4o',
      baseUrl: env.OPENAI_BASE_URL ?? 'https://api.openai.com/v1',
    },
    limits: {
      requestsPerMinute: int(env.VOX_RPM, 30),
      requestsPerDay: int(env.VOX_RPD, 500),
      imagesPerDay: int(env.VOX_IMAGES_PER_DAY, 2000),
      maxImagesPerRequest: int(env.VOX_MAX_IMAGES, 32),
      maxImageBase64Chars: int(env.VOX_MAX_IMAGE_B64, 2_500_000),
      maxMessages: 40,
      maxPartsPerMessage: 80,
      maxTextChars: 20_000,
      bodyLimit: env.VOX_BODY_LIMIT ?? '30mb',
    },
    providerTimeoutMs: int(env.VOX_PROVIDER_TIMEOUT_MS, 120_000),
  };
}

export function assertConfigUsable(config) {
  if (config.appTokens.length === 0) {
    throw new Error('VOX_APP_TOKENS is empty. Set at least one app token (e.g. `openssl rand -hex 24`).');
  }
  if (config.provider === 'anthropic' && !config.anthropic.apiKey) throw new Error('ANTHROPIC_API_KEY is required for VOX_PROVIDER=anthropic');
  if (config.provider === 'openai' && !config.openai.apiKey) throw new Error('OPENAI_API_KEY is required for VOX_PROVIDER=openai');
  if (!['anthropic', 'openai', 'mock'].includes(config.provider)) throw new Error(`Unknown VOX_PROVIDER "${config.provider}"`);
}
