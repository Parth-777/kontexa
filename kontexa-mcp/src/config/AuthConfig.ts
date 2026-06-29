import os from "node:os";
import path from "node:path";

/**
 * Immutable configuration for the authentication layer.
 *
 * This reflects the **browser handoff** login model (no OAuth, no PKCE, no JWT):
 * the MCP opens the existing Kontexa `/signin` page in MCP mode, receives a
 * single-use code on its loopback, and exchanges it for the existing opaque
 * session tokens.
 */
export interface AuthConfig {
  /** Base URL of the Kontexa backend API (issues/refreshes/exchanges tokens). */
  readonly baseUrl: string;
  /** Base URL where the Kontexa web app (login page) is served. */
  readonly frontendBaseUrl: string;

  /** The existing Kontexa login page; opened in MCP mode (`?mcp=1`). */
  readonly loginPageUrl: string;
  /** Endpoint the MCP calls to redeem a handoff code for tokens. */
  readonly exchangeEndpoint: string;
  /** Endpoint used to refresh an access token. */
  readonly refreshEndpoint: string;
  /** Endpoint used to revoke a session on logout (best-effort). */
  readonly logoutEndpoint: string;
  /** Kontexa "ask a question" endpoint the ask_kontexa tool forwards to. */
  readonly questionEndpoint: string;
  /**
   * Optional tenant/workspace override sent as `X-Client-Id`. When unset, the
   * tenant is taken from the logged-in session's workspace context.
   */
  readonly defaultTenantId?: string;

  /** Loopback host the browser is redirected back to (always loopback). */
  readonly redirectHost: string;
  /** Loopback path that receives the handoff callback. */
  readonly redirectPath: string;
  /** Loopback port; 0 means "choose a random free port". */
  readonly redirectPort: number;

  /** Max time (ms) to wait for the interactive browser login to complete. */
  readonly loginTimeoutMs: number;
  /** Treat the access token as expired this many ms before real expiry. */
  readonly tokenExpirySkewMs: number;

  /** Absolute path of the encrypted local session file. */
  readonly sessionFilePath: string;

  /** Log verbosity. */
  readonly logLevel: LogLevel;
}

export type LogLevel = "debug" | "info" | "warn" | "error" | "silent";

const DEFAULT_BASE_URL = "http://localhost:5000";
const DEFAULT_FRONTEND_URL = "http://localhost:3000";

function trimTrailingSlash(value: string): string {
  return value.endsWith("/") ? value.slice(0, -1) : value;
}

function parseIntOr(raw: string | undefined, fallback: number): number {
  if (raw === undefined || raw.trim().length === 0) {
    return fallback;
  }
  const parsed = Number.parseInt(raw, 10);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function parseLogLevel(raw: string | undefined): LogLevel {
  switch ((raw ?? "").toLowerCase()) {
    case "debug":
      return "debug";
    case "warn":
      return "warn";
    case "error":
      return "error";
    case "silent":
      return "silent";
    case "info":
    default:
      return "info";
  }
}

/**
 * Build an {@link AuthConfig} from environment variables, applying defaults.
 * The single place where raw environment input is interpreted.
 */
export function loadAuthConfig(
  env: NodeJS.ProcessEnv = process.env,
): AuthConfig {
  const baseUrl = trimTrailingSlash(env.KONTEXA_BASE_URL ?? DEFAULT_BASE_URL);
  const frontendBaseUrl = trimTrailingSlash(
    env.KONTEXA_FRONTEND_URL ?? DEFAULT_FRONTEND_URL,
  );

  return {
    baseUrl,
    frontendBaseUrl,
    loginPageUrl: env.KONTEXA_LOGIN_PAGE_URL ?? `${frontendBaseUrl}/signin`,
    exchangeEndpoint:
      env.KONTEXA_EXCHANGE_ENDPOINT ?? `${baseUrl}/api/auth/mcp/exchange`,
    refreshEndpoint: env.KONTEXA_REFRESH_ENDPOINT ?? `${baseUrl}/api/auth/refresh`,
    logoutEndpoint: env.KONTEXA_LOGOUT_ENDPOINT ?? `${baseUrl}/api/auth/logout`,
    questionEndpoint:
      env.KONTEXA_QUESTION_ENDPOINT ?? `${baseUrl}/api/decision/v1/run`,
    ...(env.KONTEXA_TENANT_ID && env.KONTEXA_TENANT_ID.trim().length > 0
      ? { defaultTenantId: env.KONTEXA_TENANT_ID.trim() }
      : {}),
    redirectHost: env.KONTEXA_REDIRECT_HOST ?? "127.0.0.1",
    redirectPath: env.KONTEXA_REDIRECT_PATH ?? "/callback",
    redirectPort: parseIntOr(env.KONTEXA_REDIRECT_PORT, 0),
    loginTimeoutMs: parseIntOr(env.KONTEXA_LOGIN_TIMEOUT_MS, 5 * 60 * 1000),
    tokenExpirySkewMs: parseIntOr(env.KONTEXA_TOKEN_EXPIRY_SKEW_MS, 30 * 1000),
    sessionFilePath:
      env.KONTEXA_SESSION_FILE ??
      path.join(os.homedir(), ".kontexa", "session.json"),
    logLevel: parseLogLevel(env.KONTEXA_LOG_LEVEL),
  };
}
