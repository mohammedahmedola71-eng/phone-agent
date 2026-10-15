import { loadConfig, assertConfigUsable } from './config.js';
import { createProvider } from './providers/index.js';
import { createApp } from './server.js';

const config = loadConfig();
assertConfigUsable(config);
const provider = createProvider(config);
createApp(config, provider).listen(config.port, () => {
  console.log(`Vox gateway listening on :${config.port} (provider=${provider.name}, model=${provider.model})`);
  console.log('Terminate TLS in front of this process (reverse proxy / platform HTTPS). Do not expose plain HTTP.');
});
