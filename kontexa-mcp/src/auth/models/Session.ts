/**
 * Tokens returned by Kontexa after a successful authentication or refresh.
 */
export interface AuthTokens {
  readonly accessToken: string;
  readonly refreshToken: string;
  /** Typically "Bearer". */
  readonly tokenType: string;
  /** Absolute expiry of the access token, as epoch milliseconds. */
  readonly expiresAt: number;
  /** Granted scopes, if the server returned them. */
  readonly scope?: string;
}

/**
 * Lightweight identity details, when the backend chooses to provide them.
 * The auth layer does not require these to function.
 */
export interface AuthenticatedUser {
  readonly id?: string;
  readonly email?: string;
  readonly displayName?: string;
}

/**
 * Workspace/tenant context captured at login. Needed because the Kontexa
 * decision endpoint resolves tenant from the `X-Client-Id` header (or body
 * `tenantId`), NOT from the Bearer token. We persist it so downstream calls can
 * supply it without re-authenticating.
 */
export interface WorkspaceContext {
  readonly workspaceId?: string;
  readonly workspaceSlug?: string;
  /** Tenant identifier expected by the backend (equals the workspace slug). */
  readonly tenantId?: string;
  readonly role?: string;
}

/**
 * A persisted authentication session. This is the unit the SessionStore reads
 * and writes, and what {@link AuthenticationManager} keeps in memory.
 */
export interface Session {
  readonly tokens: AuthTokens;
  readonly user?: AuthenticatedUser;
  readonly workspace?: WorkspaceContext;
  /** Epoch ms when this session was first created (initial login). */
  readonly createdAt: number;
  /** Epoch ms when this session was last written (login or refresh). */
  readonly updatedAt: number;
}

/**
 * Returns true when the access token is expired (or within `skewMs` of expiry).
 */
export function isAccessTokenExpired(
  tokens: AuthTokens,
  skewMs: number,
  now: number = Date.now(),
): boolean {
  return now + skewMs >= tokens.expiresAt;
}

/** Returns true if the session carries a usable refresh token. */
export function hasRefreshToken(session: Session): boolean {
  return (
    typeof session.tokens.refreshToken === "string" &&
    session.tokens.refreshToken.length > 0
  );
}

/**
 * Structural validation for a value loaded from disk. Storage formats can drift
 * or be tampered with, so we never trust a parsed object blindly.
 */
export function isValidSession(value: unknown): value is Session {
  if (typeof value !== "object" || value === null) {
    return false;
  }
  const session = value as Record<string, unknown>;
  const tokens = session.tokens as Record<string, unknown> | undefined;
  if (typeof tokens !== "object" || tokens === null) {
    return false;
  }
  return (
    typeof tokens.accessToken === "string" &&
    typeof tokens.refreshToken === "string" &&
    typeof tokens.tokenType === "string" &&
    typeof tokens.expiresAt === "number" &&
    typeof session.createdAt === "number" &&
    typeof session.updatedAt === "number"
  );
}
