import type {
  AuthenticatedUser,
  AuthTokens,
  WorkspaceContext,
} from "../models/Session.js";

/**
 * Outcome of a successful authentication: the token set plus any
 * identity/workspace context the backend returned at login.
 */
export interface LoginResult {
  readonly tokens: AuthTokens;
  readonly workspace?: WorkspaceContext;
  readonly user?: AuthenticatedUser;
}

/**
 * Performs the interactive authentication and returns a {@link LoginResult}.
 *
 * {@link AuthenticationManager} depends on this abstraction rather than on the
 * concrete browser/loopback mechanics, so the strategy can be swapped (e.g. a
 * fake flow in tests).
 */
export interface LoginFlow {
  authenticate(): Promise<LoginResult>;
}
