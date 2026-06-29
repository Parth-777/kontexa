import { promises as fs } from "node:fs";
import path from "node:path";

import type { Logger } from "../../logging/Logger.js";
import { AuthError } from "../errors/AuthError.js";
import { isValidSession, type Session } from "../models/Session.js";
import type { EncryptionProvider } from "./EncryptionProvider.js";
import type { SessionStore } from "./SessionStore.js";

/**
 * Stores the session as an encrypted blob on the local filesystem.
 *
 * Hardening applied:
 *  - the containing directory is created with 0700 (best effort on Windows),
 *  - the session file is written with 0600 permissions,
 *  - writes are atomic (write temp file, then rename) to avoid torn reads,
 *  - the payload is encrypted via the injected {@link EncryptionProvider}.
 */
export class FileSessionStore implements SessionStore {
  constructor(
    private readonly filePath: string,
    private readonly encryption: EncryptionProvider,
    private readonly logger: Logger,
  ) {}

  async load(): Promise<Session | null> {
    let raw: string;
    try {
      raw = await fs.readFile(this.filePath, "utf8");
    } catch (error) {
      if (isNotFound(error)) {
        return null;
      }
      throw new AuthError(
        "SESSION_STORAGE_ERROR",
        `Failed to read session file at ${this.filePath}`,
        error,
      );
    }

    let decrypted: string;
    try {
      decrypted = this.encryption.decrypt(raw.trim());
    } catch (error) {
      throw new AuthError(
        "SESSION_CORRUPT",
        "Stored session could not be decrypted; it may be corrupt or from another machine.",
        error,
      );
    }

    let parsed: unknown;
    try {
      parsed = JSON.parse(decrypted);
    } catch (error) {
      throw new AuthError(
        "SESSION_CORRUPT",
        "Stored session is not valid JSON.",
        error,
      );
    }

    if (!isValidSession(parsed)) {
      throw new AuthError(
        "SESSION_CORRUPT",
        "Stored session is missing required fields.",
      );
    }
    return parsed;
  }

  async save(session: Session): Promise<void> {
    const dir = path.dirname(this.filePath);
    try {
      await fs.mkdir(dir, { recursive: true, mode: 0o700 });
    } catch (error) {
      throw new AuthError(
        "SESSION_STORAGE_ERROR",
        `Failed to create session directory at ${dir}`,
        error,
      );
    }

    const payload = this.encryption.encrypt(JSON.stringify(session));
    const tempPath = `${this.filePath}.${process.pid}.tmp`;
    try {
      await fs.writeFile(tempPath, payload, { mode: 0o600, encoding: "utf8" });
      await fs.rename(tempPath, this.filePath);
      // Re-assert permissions in case the file pre-existed with looser modes.
      await fs.chmod(this.filePath, 0o600).catch(() => undefined);
    } catch (error) {
      await fs.rm(tempPath, { force: true }).catch(() => undefined);
      throw new AuthError(
        "SESSION_STORAGE_ERROR",
        `Failed to write session file at ${this.filePath}`,
        error,
      );
    }
    this.logger.debug("Session persisted", { path: this.filePath });
  }

  async clear(): Promise<void> {
    try {
      await fs.rm(this.filePath, { force: true });
      this.logger.debug("Session cleared", { path: this.filePath });
    } catch (error) {
      throw new AuthError(
        "SESSION_STORAGE_ERROR",
        `Failed to remove session file at ${this.filePath}`,
        error,
      );
    }
  }
}

function isNotFound(error: unknown): boolean {
  return (
    typeof error === "object" &&
    error !== null &&
    (error as NodeJS.ErrnoException).code === "ENOENT"
  );
}
