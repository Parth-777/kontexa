import crypto from "node:crypto";
import os from "node:os";

/**
 * Encrypts/decrypts session material at rest.
 *
 * Defined as an interface so the file store does not care *how* protection is
 * achieved. A future implementation can delegate to an OS keychain (Keychain,
 * DPAPI, libsecret) without touching the store.
 */
export interface EncryptionProvider {
  encrypt(plaintext: string): string;
  decrypt(payload: string): string;
}

/**
 * AES-256-GCM encryption using a key derived from stable, machine-local inputs
 * (hostname + OS username + a fixed application salt).
 *
 * Threat model: this protects the session file from being read after being
 * copied to a different machine or user account. It does NOT defend against a
 * same-user local attacker who can also run this process — that boundary is
 * enforced by file permissions in the store. This is defense-in-depth, and is
 * intentionally swappable for a keychain-backed provider later.
 */
export class MachineKeyEncryptionProvider implements EncryptionProvider {
  private static readonly VERSION = "v1";
  private static readonly APP_SALT = "kontexa-mcp/auth/session/v1";
  private readonly key: Buffer;

  constructor(keyMaterial?: string) {
    const material =
      keyMaterial ?? `${os.hostname()}::${safeUsername()}`;
    this.key = crypto.scryptSync(
      material,
      MachineKeyEncryptionProvider.APP_SALT,
      32,
    );
  }

  encrypt(plaintext: string): string {
    const iv = crypto.randomBytes(12);
    const cipher = crypto.createCipheriv("aes-256-gcm", this.key, iv);
    const ciphertext = Buffer.concat([
      cipher.update(plaintext, "utf8"),
      cipher.final(),
    ]);
    const tag = cipher.getAuthTag();
    return [
      MachineKeyEncryptionProvider.VERSION,
      iv.toString("base64"),
      tag.toString("base64"),
      ciphertext.toString("base64"),
    ].join(".");
  }

  decrypt(payload: string): string {
    const parts = payload.split(".");
    if (parts.length !== 4 || parts[0] !== MachineKeyEncryptionProvider.VERSION) {
      throw new Error("Unrecognized encrypted session format");
    }
    const iv = Buffer.from(parts[1]!, "base64");
    const tag = Buffer.from(parts[2]!, "base64");
    const ciphertext = Buffer.from(parts[3]!, "base64");
    const decipher = crypto.createDecipheriv("aes-256-gcm", this.key, iv);
    decipher.setAuthTag(tag);
    return Buffer.concat([
      decipher.update(ciphertext),
      decipher.final(),
    ]).toString("utf8");
  }
}

function safeUsername(): string {
  try {
    return os.userInfo().username;
  } catch {
    return "unknown-user";
  }
}
