import type { LogLevel } from "../config/AuthConfig.js";

/**
 * Minimal logging abstraction the auth layer depends on.
 *
 * Implementations MUST write to stderr (never stdout). The MCP stdio transport
 * reserves stdout for protocol traffic, so any stray stdout write corrupts it.
 */
export interface Logger {
  debug(message: string, meta?: Record<string, unknown>): void;
  info(message: string, meta?: Record<string, unknown>): void;
  warn(message: string, meta?: Record<string, unknown>): void;
  error(message: string, meta?: Record<string, unknown>): void;
}

const LEVEL_PRIORITY: Record<LogLevel, number> = {
  debug: 10,
  info: 20,
  warn: 30,
  error: 40,
  silent: 100,
};

/**
 * Default logger that writes structured lines to stderr.
 */
export class ConsoleLogger implements Logger {
  constructor(private readonly level: LogLevel = "info") {}

  debug(message: string, meta?: Record<string, unknown>): void {
    this.write("debug", message, meta);
  }

  info(message: string, meta?: Record<string, unknown>): void {
    this.write("info", message, meta);
  }

  warn(message: string, meta?: Record<string, unknown>): void {
    this.write("warn", message, meta);
  }

  error(message: string, meta?: Record<string, unknown>): void {
    this.write("error", message, meta);
  }

  private write(
    level: Exclude<LogLevel, "silent">,
    message: string,
    meta?: Record<string, unknown>,
  ): void {
    if (LEVEL_PRIORITY[level] < LEVEL_PRIORITY[this.level]) {
      return;
    }
    const timestamp = new Date().toISOString();
    const suffix =
      meta && Object.keys(meta).length > 0 ? ` ${safeStringify(meta)}` : "";
    process.stderr.write(
      `${timestamp} [${level.toUpperCase()}] kontexa-mcp: ${message}${suffix}\n`,
    );
  }
}

function safeStringify(value: unknown): string {
  try {
    return JSON.stringify(value);
  } catch {
    return "[unserializable meta]";
  }
}

/** A logger that discards everything. Useful for tests and embedding. */
export class NoopLogger implements Logger {
  debug(): void {}
  info(): void {}
  warn(): void {}
  error(): void {}
}
