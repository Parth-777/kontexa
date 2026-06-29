import type { AuthConfig } from "../../config/AuthConfig.js";
import type { Logger } from "../../logging/Logger.js";
import type { LoginResult } from "../browser/LoginFlow.js";
import { AuthError } from "../errors/AuthError.js";
import type { AuthTokens } from "../models/Session.js";
import type { TokenClient } from "./TokenClient.js";

/**
 * Shape of a Kontexa auth response (login / mcp-exchange / refresh).
 * Tokens are opaque strings; expiries are ISO-8601 local date-time strings.
 */
interface KontexaTokenResponse {
  accessToken?: string;
  refreshToken?: string;
  accessExpiresAt?: string;
  refreshExpiresAt?: string;
  scope?: string;
  error?: string;
  // identity/workspace fields returned by /api/auth/mcp/exchange
  identityId?: string;
  workspaceId?: string;
  workspaceSlug?: string;
  email?: string;
  displayName?: string;
  role?: string;
}

const FALLBACK_ACCESS_TTL_MS = 55 * 60 * 1000;

/**
 * {@link TokenClient} backed by HTTP calls to the existing Kontexa auth
 * endpoints, using JSON request bodies (matching the web app's contract).
 */
export class HttpTokenClient implements TokenClient {
  constructor(
    private readonly config: AuthConfig,
    private readonly logger: Logger,
    private readonly fetchImpl: typeof fetch = fetch,
  ) {}

  async exchangeHandoffCode(code: string): Promise<LoginResult> {
    const payload = await this.postRaw(
      this.config.exchangeEndpoint,
      { code },
      "TOKEN_EXCHANGE_FAILED",
      "handoff code exchange",
    );
    const tokens = this.toAuthTokens(payload, "TOKEN_EXCHANGE_FAILED", "handoff code exchange");
    const slug = payload.workspaceSlug;
    const result: LoginResult = {
      tokens,
      workspace: {
        ...(payload.workspaceId ? { workspaceId: payload.workspaceId } : {}),
        ...(slug ? { workspaceSlug: slug, tenantId: slug } : {}),
        ...(payload.role ? { role: payload.role } : {}),
      },
      user: {
        ...(payload.identityId ? { id: payload.identityId } : {}),
        ...(payload.email ? { email: payload.email } : {}),
        ...(payload.displayName ? { displayName: payload.displayName } : {}),
      },
    };
    return result;
  }

  async refresh(refreshToken: string): Promise<AuthTokens> {
    const payload = await this.postRaw(
      this.config.refreshEndpoint,
      { refreshToken },
      "TOKEN_REFRESH_FAILED",
      "token refresh",
    );
    const tokens = this.toAuthTokens(payload, "TOKEN_REFRESH_FAILED", "token refresh");
    // Kontexa's refresh reuses the same refresh token and may omit it; preserve.
    if (!tokens.refreshToken) {
      return { ...tokens, refreshToken };
    }
    return tokens;
  }

  async revoke(accessToken: string): Promise<void> {
    try {
      const response = await this.fetchImpl(this.config.logoutEndpoint, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Accept: "application/json",
          Authorization: `Bearer ${accessToken}`,
        },
        body: JSON.stringify({ accessToken }),
      });
      if (!response.ok) {
        this.logger.warn("Logout returned non-OK status", {
          status: response.status,
        });
      }
    } catch (error) {
      // Logout is best-effort; never block on it.
      this.logger.warn("Logout request failed", { error: String(error) });
    }
  }

  private async postRaw(
    url: string,
    body: Record<string, unknown>,
    errorCode: "TOKEN_EXCHANGE_FAILED" | "TOKEN_REFRESH_FAILED",
    operation: string,
  ): Promise<KontexaTokenResponse> {
    let response: Response;
    try {
      response = await this.fetchImpl(url, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Accept: "application/json",
        },
        body: JSON.stringify(body),
      });
    } catch (error) {
      throw new AuthError(errorCode, `Network error during ${operation}.`, error);
    }

    const text = await response.text();
    let parsed: KontexaTokenResponse = {};
    if (text.length > 0) {
      try {
        parsed = JSON.parse(text) as KontexaTokenResponse;
      } catch {
        // fall through to status-based error handling
      }
    }

    if (!response.ok) {
      const detail = parsed.error ?? `HTTP ${response.status}`;
      throw new AuthError(errorCode, `Failed ${operation}: ${detail}`);
    }

    return parsed;
  }

  private toAuthTokens(
    payload: KontexaTokenResponse,
    errorCode: "TOKEN_EXCHANGE_FAILED" | "TOKEN_REFRESH_FAILED",
    operation: string,
  ): AuthTokens {
    if (!payload.accessToken) {
      throw new AuthError(
        errorCode,
        `Server response for ${operation} did not include an accessToken.`,
      );
    }
    return {
      accessToken: payload.accessToken,
      refreshToken: payload.refreshToken ?? "",
      tokenType: "Bearer",
      expiresAt: parseExpiry(payload.accessExpiresAt),
      ...(payload.scope ? { scope: payload.scope } : {}),
    };
  }
}

/**
 * Kontexa returns a local (no-timezone) ISO date-time, e.g. "2026-06-27T12:00:00".
 * JS parses such strings as local time, which is consistent with the server's
 * clock for relative expiry checks. Falls back to a conservative TTL.
 */
function parseExpiry(value: string | undefined): number {
  if (value) {
    const parsed = Date.parse(value);
    if (Number.isFinite(parsed)) {
      return parsed;
    }
  }
  return Date.now() + FALLBACK_ACCESS_TTL_MS;
}
