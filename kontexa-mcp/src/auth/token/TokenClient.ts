import type { LoginResult } from "../browser/LoginFlow.js";
import type { AuthTokens } from "../models/Session.js";

/**
 * Talks to the Kontexa auth endpoints. This is the only component that knows the
 * wire format of the handoff exchange / refresh / logout requests, isolating the
 * rest of the auth layer from HTTP details.
 */
export interface TokenClient {
  /**
   * Redeem a single-use handoff code for a session token set plus the
   * identity/workspace context the backend returns on exchange.
   */
  exchangeHandoffCode(code: string): Promise<LoginResult>;

  /** Obtain a fresh access token using a refresh token. */
  refresh(refreshToken: string): Promise<AuthTokens>;

  /** Best-effort session revocation on logout. */
  revoke(accessToken: string): Promise<void>;
}
