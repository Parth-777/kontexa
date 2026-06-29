import http from "node:http";
import { AddressInfo } from "node:net";

import type { AuthConfig } from "../../config/AuthConfig.js";
import type { Logger } from "../../logging/Logger.js";
import { AuthError } from "../errors/AuthError.js";
import { safeEquals } from "./state.js";

/** Result of a successful OAuth redirect. */
export interface CallbackResult {
  readonly code: string;
}

/**
 * A short-lived localhost HTTP server that receives the OAuth redirect.
 *
 * Lifecycle: {@link start} → (open browser elsewhere) → {@link waitForCallback}
 * → {@link stop}. Only the configured redirect path is honored; every other
 * request gets a 404. The server validates the returned `state` to defend
 * against CSRF before resolving.
 */
export class LoopbackServer {
  private server: http.Server | null = null;
  private redirectUri: string | null = null;

  constructor(
    private readonly config: AuthConfig,
    private readonly logger: Logger,
  ) {}

  /** Bind to a loopback port and return the concrete redirect URI to use. */
  async start(): Promise<{ redirectUri: string }> {
    if (this.server) {
      throw new AuthError("LOGIN_FAILED", "Loopback server already started.");
    }
    const server = http.createServer();
    this.server = server;

    await new Promise<void>((resolve, reject) => {
      const onError = (error: unknown) =>
        reject(
          new AuthError(
            "LOGIN_FAILED",
            "Failed to start the local callback server.",
            error,
          ),
        );
      server.once("error", onError);
      server.listen(this.config.redirectPort, this.config.redirectHost, () => {
        server.removeListener("error", onError);
        resolve();
      });
    });

    const address = this.server.address() as AddressInfo;
    this.redirectUri = `http://${this.config.redirectHost}:${address.port}${this.config.redirectPath}`;
    this.logger.debug("Loopback server listening", {
      redirectUri: this.redirectUri,
    });
    return { redirectUri: this.redirectUri };
  }

  /**
   * Resolve once the browser is redirected back with a valid `code` whose
   * `state` matches `expectedState`. Rejects on OAuth errors or timeout.
   */
  async waitForCallback(expectedState: string): Promise<CallbackResult> {
    const server = this.server;
    const redirectUri = this.redirectUri;
    if (!server || !redirectUri) {
      throw new AuthError(
        "LOGIN_FAILED",
        "Loopback server is not started.",
      );
    }
    const expectedPath = new URL(redirectUri).pathname;

    return new Promise<CallbackResult>((resolve, reject) => {
      const timer = setTimeout(() => {
        cleanup();
        reject(
          new AuthError(
            "LOGIN_TIMEOUT",
            `Login was not completed within ${Math.round(
              this.config.loginTimeoutMs / 1000,
            )}s.`,
          ),
        );
      }, this.config.loginTimeoutMs);

      const onRequest = (
        req: http.IncomingMessage,
        res: http.ServerResponse,
      ) => {
        const requestUrl = new URL(
          req.url ?? "/",
          `http://${this.config.redirectHost}`,
        );
        if (requestUrl.pathname !== expectedPath) {
          respond(res, 404, "Not found");
          return;
        }

        const error = requestUrl.searchParams.get("error");
        if (error) {
          const description =
            requestUrl.searchParams.get("error_description") ?? error;
          respondHtml(res, 400, failurePage(description));
          finish(
            () =>
              reject(
                new AuthError(
                  "LOGIN_FAILED",
                  `Authorization server returned an error: ${description}`,
                ),
              ),
          );
          return;
        }

        const returnedState = requestUrl.searchParams.get("state") ?? "";
        if (!safeEquals(returnedState, expectedState)) {
          respondHtml(res, 400, failurePage("State mismatch."));
          finish(
            () =>
              reject(
                new AuthError(
                  "STATE_MISMATCH",
                  "Returned state did not match the expected value (possible CSRF).",
                ),
              ),
          );
          return;
        }

        const code = requestUrl.searchParams.get("code");
        if (!code) {
          respondHtml(res, 400, failurePage("Missing authorization code."));
          finish(
            () =>
              reject(
                new AuthError(
                  "LOGIN_FAILED",
                  "Callback did not include an authorization code.",
                ),
              ),
          );
          return;
        }

        respondHtml(res, 200, successPage());
        finish(() => resolve({ code }));
      };

      const cleanup = () => {
        clearTimeout(timer);
        server.removeListener("request", onRequest);
      };

      // Give the success/failure response time to flush before settling.
      const finish = (settle: () => void) => {
        cleanup();
        setTimeout(settle, 50);
      };

      server.on("request", onRequest);
    });
  }

  async stop(): Promise<void> {
    const server = this.server;
    this.server = null;
    this.redirectUri = null;
    if (!server) {
      return;
    }
    await new Promise<void>((resolve) => server.close(() => resolve()));
    this.logger.debug("Loopback server stopped");
  }
}

function respond(res: http.ServerResponse, status: number, body: string): void {
  res.writeHead(status, { "Content-Type": "text/plain; charset=utf-8" });
  res.end(body);
}

function respondHtml(
  res: http.ServerResponse,
  status: number,
  html: string,
): void {
  res.writeHead(status, { "Content-Type": "text/html; charset=utf-8" });
  res.end(html);
}

function successPage(): string {
  return page(
    "You're signed in to Kontexa",
    "Authentication succeeded. You can close this tab and return to your editor.",
    "#16a34a",
  );
}

function failurePage(reason: string): string {
  return page(
    "Kontexa sign-in failed",
    escapeHtml(reason),
    "#dc2626",
  );
}

function page(title: string, message: string, accent: string): string {
  return `<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <title>${escapeHtml(title)}</title>
    <style>
      body { margin: 0; font-family: -apple-system, Segoe UI, Roboto, sans-serif;
             background: #0b0f17; color: #e5e7eb; display: flex;
             align-items: center; justify-content: center; height: 100vh; }
      .card { max-width: 420px; padding: 40px; text-align: center;
              background: #111827; border-radius: 16px;
              box-shadow: 0 10px 40px rgba(0,0,0,.4); }
      .dot { width: 14px; height: 14px; border-radius: 50%;
             background: ${accent}; display: inline-block; margin-bottom: 16px; }
      h1 { font-size: 20px; margin: 0 0 8px; }
      p { color: #9ca3af; line-height: 1.5; margin: 0; }
    </style>
  </head>
  <body>
    <div class="card">
      <span class="dot"></span>
      <h1>${escapeHtml(title)}</h1>
      <p>${message}</p>
    </div>
  </body>
</html>`;
}

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}
