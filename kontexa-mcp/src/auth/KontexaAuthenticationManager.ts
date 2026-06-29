import type { AuthConfig } from "../config/AuthConfig.js";
import type { Logger } from "../logging/Logger.js";
import type { AuthenticationManager } from "./AuthenticationManager.js";
import type { LoginFlow } from "./browser/LoginFlow.js";
import { AuthError } from "./errors/AuthError.js";
import {
  hasRefreshToken,
  isAccessTokenExpired,
  type AuthTokens,
  type Session,
} from "./models/Session.js";
import type { SessionStore } from "./storage/SessionStore.js";
import type { TokenClient } from "./token/TokenClient.js";

/**
 * Default {@link AuthenticationManager} implementation.
 *
 * It coordinates collaborators only through their interfaces:
 *  - {@link LoginFlow}    — performs the interactive browser login
 *  - {@link TokenClient}  — refreshes / revokes tokens
 *  - {@link SessionStore} — persists the session locally
 *
 * The current session is cached in memory to avoid repeated disk reads, and
 * concurrent refreshes are de-duplicated so a burst of callers triggers a
 * single network round-trip.
 */
export class KontexaAuthenticationManager implements AuthenticationManager {
  private current: Session | null = null;
  private inFlightRefresh: Promise<Session> | null = null;

  constructor(
    private readonly config: AuthConfig,
    private readonly loginFlow: LoginFlow,
    private readonly tokenClient: TokenClient,
    private readonly store: SessionStore,
    private readonly logger: Logger,
  ) {}

  async login(): Promise<Session> {
    await this.ensureLoaded();
    if (this.current && (await this.isLoggedIn())) {
      this.logger.info("Already authenticated; reusing existing session");
      return this.current;
    }

    this.logger.info("Starting interactive login");
    const result = await this.loginFlow.authenticate();
    const now = Date.now();
    this.current = {
      tokens: result.tokens,
      ...(result.workspace ? { workspace: result.workspace } : {}),
      ...(result.user ? { user: result.user } : {}),
      createdAt: now,
      updatedAt: now,
    };
    await this.persist();
    this.logger.info("Login successful; session stored");
    return this.current;
  }

  async logout(): Promise<void> {
    await this.ensureLoaded();
    const token = this.current?.tokens.refreshToken;
    if (token) {
      try {
        await this.tokenClient.revoke(token);
      } catch (error) {
        this.logger.warn("Token revocation failed during logout", {
          error: String(error),
        });
      }
    }
    this.current = null;
    await this.store.clear();
    this.logger.info("Logged out; local session cleared");
  }

  async isLoggedIn(): Promise<boolean> {
    await this.ensureLoaded();
    if (!this.current) {
      this.logger.info("Auth check result: NOT logged in (no session in memory)");
      return false;
    }
    if (!isAccessTokenExpired(this.current.tokens, this.config.tokenExpirySkewMs)) {
      this.logger.info("Auth check result: logged in", {
        source: "store reload",
        accessTokenExpired: false,
      });
      return true;
    }
    const refreshable = hasRefreshToken(this.current);
    this.logger.info("Auth check result", {
      source: "store reload",
      accessTokenExpired: true,
      refreshable,
    });
    return refreshable;
  }

  async refreshToken(): Promise<Session> {
    await this.ensureLoaded();
    if (!this.current) {
      throw new AuthError(
        "NOT_AUTHENTICATED",
        "Cannot refresh: no session is loaded.",
      );
    }
    if (!hasRefreshToken(this.current)) {
      throw new AuthError(
        "TOKEN_REFRESH_FAILED",
        "Cannot refresh: the session has no refresh token.",
      );
    }

    // De-duplicate concurrent refreshes.
    if (this.inFlightRefresh) {
      return this.inFlightRefresh;
    }
    this.inFlightRefresh = this.doRefresh(this.current);
    try {
      return await this.inFlightRefresh;
    } finally {
      this.inFlightRefresh = null;
    }
  }

  async loadSession(): Promise<Session | null> {
    this.current = await this.store.load();
    if (this.current) {
      this.logger.info("Session loaded from store", {
        path: this.config.sessionFilePath,
        workspace: this.current.workspace?.workspaceSlug ?? "(unknown)",
        accessToken: maskToken(this.current.tokens.accessToken),
      });
    } else {
      this.logger.info("Session missing from store", {
        path: this.config.sessionFilePath,
      });
    }
    return this.current;
  }

  async getSession(): Promise<Session | null> {
    await this.ensureLoaded();
    return this.current;
  }

  async saveSession(): Promise<void> {
    if (!this.current) {
      throw new AuthError(
        "NOT_AUTHENTICATED",
        "Cannot save: no session is loaded.",
      );
    }
    await this.persist();
  }

  async getAccessToken(): Promise<string> {
    await this.ensureLoaded();
    if (!this.current) {
      throw new AuthError(
        "NOT_AUTHENTICATED",
        "No session. Call login() first.",
      );
    }
    if (
      isAccessTokenExpired(this.current.tokens, this.config.tokenExpirySkewMs)
    ) {
      this.logger.debug("Access token expired; refreshing");
      const refreshed = await this.refreshToken();
      this.logger.info("Token used", {
        source: "refreshed",
        accessToken: maskToken(refreshed.tokens.accessToken),
      });
      return refreshed.tokens.accessToken;
    }
    this.logger.info("Token used", {
      source: "store reload",
      accessToken: maskToken(this.current.tokens.accessToken),
    });
    return this.current.tokens.accessToken;
  }

  private async doRefresh(session: Session): Promise<Session> {
    this.logger.info("Refreshing access token");
    const tokens: AuthTokens = await this.tokenClient.refresh(
      session.tokens.refreshToken,
    );
    this.current = {
      ...session,
      tokens,
      updatedAt: Date.now(),
    };
    await this.persist();
    return this.current;
  }

  private async persist(): Promise<void> {
    if (!this.current) {
      return;
    }
    const next: Session = { ...this.current, updatedAt: Date.now() };
    await this.store.save(next);
    this.current = next;
  }

  /**
   * Reconcile the in-memory session with the persistent store on EVERY
   * auth-sensitive operation. The store is the single source of truth, so an
   * external `logout` (which deletes the session file, possibly from another
   * process) is reflected immediately by this long-lived server — the cache can
   * never go stale relative to disk.
   */
  private async ensureLoaded(): Promise<void> {
    await this.loadSession();
  }
}

/** Show only enough of a token to correlate logs, never the full secret. */
function maskToken(token: string): string {
  if (!token) {
    return "(empty)";
  }
  if (token.length <= 8) {
    return "********";
  }
  return `${token.slice(0, 4)}…${token.slice(-4)} (len=${token.length})`;
}
