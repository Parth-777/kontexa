import type { Session } from "./models/Session.js";

/**
 * The single entry point the rest of the MCP server depends on for
 * authentication. Everything behind this interface (browser flow, token HTTP
 * calls, encrypted storage) is an implementation detail.
 *
 * Authentication is intentionally decoupled from analytics: nothing here knows
 * about warehouses, catalogues, SQL, or Claude tools. Consumers obtain a valid
 * access token via {@link getAccessToken} and use it however they need.
 */
export interface AuthenticationManager {
  /**
   * Run the interactive browser login, persist the resulting session, and
   * return it. If a valid session already exists it is returned as-is.
   */
  login(): Promise<Session>;

  /**
   * Revoke (best-effort) and clear the local session. Safe to call when not
   * logged in.
   */
  logout(): Promise<void>;

  /**
   * Whether a usable session exists — either with a still-valid access token
   * or with a refresh token that can mint a new one. Non-mutating.
   */
  isLoggedIn(): Promise<boolean>;

  /**
   * Force a token refresh using the stored refresh token, persisting the new
   * tokens. Throws if there is no refreshable session.
   */
  refreshToken(): Promise<Session>;

  /** Load the persisted session into memory (if any) and return it. */
  loadSession(): Promise<Session | null>;

  /**
   * Return the current in-memory session (loading from storage on first use).
   * Useful for reading workspace/identity context without forcing a disk read.
   */
  getSession(): Promise<Session | null>;

  /** Persist the current in-memory session. Throws if there is none. */
  saveSession(): Promise<void>;

  /**
   * Return a valid bearer access token, transparently refreshing if the
   * current one is expired. Throws `NOT_AUTHENTICATED` if there is no session.
   *
   * This is the method downstream MCP components should use; it is the only
   * coupling point they need to authentication.
   */
  getAccessToken(): Promise<string>;
}
