/**
 * Stable, machine-readable codes for every failure the auth layer can surface.
 * Consumers (including future Claude tools) should branch on `code`, not on
 * human-readable messages.
 */
export type AuthErrorCode =
  | "NOT_AUTHENTICATED"
  | "CONFIG_ERROR"
  | "BROWSER_LAUNCH_FAILED"
  | "LOGIN_TIMEOUT"
  | "LOGIN_FAILED"
  | "STATE_MISMATCH"
  | "TOKEN_EXCHANGE_FAILED"
  | "TOKEN_REFRESH_FAILED"
  | "REVOCATION_FAILED"
  | "SESSION_STORAGE_ERROR"
  | "SESSION_CORRUPT";

/**
 * The single error type thrown across the authentication layer.
 */
export class AuthError extends Error {
  readonly code: AuthErrorCode;
  override readonly cause?: unknown;

  constructor(code: AuthErrorCode, message: string, cause?: unknown) {
    super(message);
    this.name = "AuthError";
    this.code = code;
    if (cause !== undefined) {
      this.cause = cause;
    }
    Object.setPrototypeOf(this, AuthError.prototype);
  }

  static is(value: unknown): value is AuthError {
    return value instanceof AuthError;
  }
}
