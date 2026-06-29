import type { Session } from "../models/Session.js";

/**
 * Persistence boundary for the authentication session.
 *
 * The {@link AuthenticationManager} depends only on this interface, so the
 * physical storage (encrypted file, OS keychain, in-memory for tests) is an
 * implementation detail.
 */
export interface SessionStore {
  /** Returns the stored session, or null if none exists. */
  load(): Promise<Session | null>;
  /** Persists the given session, replacing any existing one. */
  save(session: Session): Promise<void>;
  /** Removes any stored session. Idempotent. */
  clear(): Promise<void>;
}
