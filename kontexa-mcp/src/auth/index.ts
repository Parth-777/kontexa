import { loadAuthConfig, type AuthConfig } from "../config/AuthConfig.js";
import { ConsoleLogger, type Logger } from "../logging/Logger.js";
import { AuthenticationManager } from "./AuthenticationManager.js";
import { BrowserHandoffLoginFlow } from "./browser/BrowserHandoffLoginFlow.js";
import {
  DefaultBrowserLauncher,
  type BrowserLauncher,
} from "./browser/BrowserLauncher.js";
import type { LoginFlow } from "./browser/LoginFlow.js";
import { KontexaAuthenticationManager } from "./KontexaAuthenticationManager.js";
import {
  MachineKeyEncryptionProvider,
  type EncryptionProvider,
} from "./storage/EncryptionProvider.js";
import { FileSessionStore } from "./storage/FileSessionStore.js";
import type { SessionStore } from "./storage/SessionStore.js";
import { HttpTokenClient } from "./token/HttpTokenClient.js";
import type { TokenClient } from "./token/TokenClient.js";

/**
 * Optional overrides for {@link createAuthenticationManager}. Each collaborator
 * can be replaced (e.g. an in-memory store or a fake login flow in tests)
 * without the manager knowing the difference.
 */
export interface AuthDependencies {
  config?: AuthConfig;
  logger?: Logger;
  encryption?: EncryptionProvider;
  sessionStore?: SessionStore;
  tokenClient?: TokenClient;
  browserLauncher?: BrowserLauncher;
  loginFlow?: LoginFlow;
}

/**
 * Compose a production-ready {@link AuthenticationManager} with sensible
 * defaults, wiring all the concrete implementations together. This is the
 * single recommended construction point for the rest of the MCP server.
 */
export function createAuthenticationManager(
  deps: AuthDependencies = {},
): AuthenticationManager {
  const config = deps.config ?? loadAuthConfig();
  const logger = deps.logger ?? new ConsoleLogger(config.logLevel);

  const encryption =
    deps.encryption ?? new MachineKeyEncryptionProvider();
  const sessionStore =
    deps.sessionStore ??
    new FileSessionStore(config.sessionFilePath, encryption, logger);
  const tokenClient =
    deps.tokenClient ?? new HttpTokenClient(config, logger);
  const browserLauncher =
    deps.browserLauncher ?? new DefaultBrowserLauncher();
  const loginFlow =
    deps.loginFlow ??
    new BrowserHandoffLoginFlow(config, tokenClient, browserLauncher, logger);

  return new KontexaAuthenticationManager(
    config,
    loginFlow,
    tokenClient,
    sessionStore,
    logger,
  );
}

// Public surface for consumers of the auth layer.
export type { AuthenticationManager } from "./AuthenticationManager.js";
export { AuthError, type AuthErrorCode } from "./errors/AuthError.js";
export type {
  AuthTokens,
  AuthenticatedUser,
  WorkspaceContext,
  Session,
} from "./models/Session.js";
export type { LoginResult } from "./browser/LoginFlow.js";
export type { SessionStore } from "./storage/SessionStore.js";
export type { EncryptionProvider } from "./storage/EncryptionProvider.js";
export type { TokenClient } from "./token/TokenClient.js";
export type { LoginFlow } from "./browser/LoginFlow.js";
export type { BrowserLauncher } from "./browser/BrowserLauncher.js";
export type { AuthConfig } from "../config/AuthConfig.js";
export { loadAuthConfig } from "../config/AuthConfig.js";
export type { Logger } from "../logging/Logger.js";
export { ConsoleLogger, NoopLogger } from "../logging/Logger.js";
