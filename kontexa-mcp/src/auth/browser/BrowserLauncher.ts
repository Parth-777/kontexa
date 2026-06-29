import { spawn } from "node:child_process";

import { AuthError } from "../errors/AuthError.js";

/**
 * Opens a URL in the user's default browser. Abstracted so the login flow can
 * be tested without actually launching a browser.
 */
export interface BrowserLauncher {
  open(url: string): Promise<void>;
}

/**
 * Default launcher that shells out to the platform's "open default browser"
 * command. Uses no third-party dependencies.
 */
export class DefaultBrowserLauncher implements BrowserLauncher {
  async open(url: string): Promise<void> {
    const { command, args } = resolveOpenCommand(url);
    return new Promise<void>((resolve, reject) => {
      try {
        // IMPORTANT: never launch through a shell. cmd.exe's `start` treats `&`
        // as a command separator, which would truncate a URL with query params
        // (e.g. `?mcp=1&redirect_uri=…&state=…`) down to just `?mcp=1`. Passing
        // the URL as a single argv element (no shell) keeps it intact.
        const child = spawn(command, args, {
          stdio: "ignore",
          detached: true,
          shell: false,
        });
        child.on("error", (error) =>
          reject(
            new AuthError(
              "BROWSER_LAUNCH_FAILED",
              `Failed to open the browser to ${url}`,
              error,
            ),
          ),
        );
        child.unref();
        resolve();
      } catch (error) {
        reject(
          new AuthError(
            "BROWSER_LAUNCH_FAILED",
            `Failed to open the browser to ${url}`,
            error,
          ),
        );
      }
    });
  }
}

function resolveOpenCommand(url: string): { command: string; args: string[] } {
  switch (process.platform) {
    case "win32":
      // rundll32 opens the URL in the default browser and receives the URL as a
      // single, verbatim argument — no shell, so `&` in the query is preserved.
      return {
        command: "rundll32",
        args: ["url.dll,FileProtocolHandler", url],
      };
    case "darwin":
      return { command: "open", args: [url] };
    default:
      return { command: "xdg-open", args: [url] };
  }
}
