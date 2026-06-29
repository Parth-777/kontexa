import { createAuthenticationManager } from "./auth/index.js";
import type { AuthenticationManager } from "./auth/AuthenticationManager.js";
import { loadAuthConfig, type AuthConfig } from "./config/AuthConfig.js";
import { KontexaClient } from "./kontexa/KontexaClient.js";
import { ConsoleLogger, type Logger } from "./logging/Logger.js";
import type { AskKontexaDeps } from "./tools/askKontexa.js";

/**
 * Composition root: builds the shared config + logger and wires the
 * authentication manager and Kontexa client together. A single place so the
 * CLI and the MCP server use identical dependencies.
 */
export interface App {
  readonly config: AuthConfig;
  readonly logger: Logger;
  readonly auth: AuthenticationManager;
  readonly client: KontexaClient;
  readonly deps: AskKontexaDeps;
}

export function createApp(): App {
  const config = loadAuthConfig();
  const logger = new ConsoleLogger(config.logLevel);
  const auth = createAuthenticationManager({ config, logger });
  const client = new KontexaClient(config, logger);
  return { config, logger, auth, client, deps: { auth, client, config, logger } };
}
