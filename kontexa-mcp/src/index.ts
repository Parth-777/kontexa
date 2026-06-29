#!/usr/bin/env node
/**
 * Kontexa MCP — entry point.
 *
 * Commands:
 *   serve     Start the MCP server over stdio (default; used by Claude Desktop).
 *   ask "..." Ask a question via the ask_kontexa tool logic (local smoke test).
 *   login     Run the browser-based login and store the session.
 *   logout    Revoke (best-effort) and clear the local session.
 *   status    Print whether a valid session exists.
 *   refresh   Force a token refresh using the stored refresh token.
 *
 * The MCP exposes exactly one tool (ask_kontexa) and contains no analytics
 * logic — it is purely a transport between Claude and the Kontexa backend.
 */
import { createApp } from "./app.js";
import { AuthError } from "./auth/errors/AuthError.js";
import { startMcpServer } from "./server/startServer.js";
import { askKontexa } from "./tools/askKontexa.js";

const DEFAULT_SMOKE_QUESTION = "Which companies generate the highest revenue?";

type Command =
  | "serve"
  | "ask"
  | "login"
  | "logout"
  | "status"
  | "refresh"
  | "help";

function parseCommand(argv: string[]): Command {
  const raw = (argv[2] ?? "serve").toLowerCase();
  switch (raw) {
    case "serve":
    case "ask":
    case "login":
    case "logout":
    case "status":
    case "refresh":
    case "help":
      return raw;
    default:
      return "help";
  }
}

function printHelp(): void {
  process.stdout.write(
    [
      "Kontexa MCP",
      "",
      "Usage: kontexa-mcp <command>",
      "",
      "Commands:",
      "  serve            Start the MCP stdio server (default)",
      '  ask "<question>" Ask a question via ask_kontexa (local smoke test)',
      "  login            Sign in via the browser and store a session",
      "  logout           Clear the local session",
      "  status           Show current authentication status",
      "  refresh          Refresh the access token",
      "",
    ].join("\n"),
  );
}

/**
 * Returns an exit code, or null to indicate the process should keep running
 * (used by `serve`, where the stdio transport owns the lifecycle).
 */
async function main(): Promise<number | null> {
  const command = parseCommand(process.argv);
  if (command === "help") {
    printHelp();
    return 0;
  }

  const app = createApp();

  switch (command) {
    case "serve": {
      await startMcpServer(app.deps);
      return null; // keep running; stdio transport holds the process open
    }
    case "ask": {
      const question =
        process.argv.slice(3).join(" ").trim() || DEFAULT_SMOKE_QUESTION;
      const answer = await askKontexa(question, app.deps, {
        interactiveLogin: false,
      });
      process.stdout.write(`${JSON.stringify(answer, null, 2)}\n`);
      return 0;
    }
    case "login": {
      const session = await app.auth.login();
      const expiresAt = new Date(session.tokens.expiresAt).toISOString();
      process.stdout.write(
        `Signed in to Kontexa. Access token valid until ${expiresAt}.\n`,
      );
      return 0;
    }
    case "logout": {
      await app.auth.logout();
      process.stdout.write("Signed out. Local session cleared.\n");
      return 0;
    }
    case "status": {
      const loggedIn = await app.auth.isLoggedIn();
      if (!loggedIn) {
        process.stdout.write("Not signed in. Run `kontexa-mcp login`.\n");
        return 0;
      }
      const session = await app.auth.getSession();
      const expiresAt = session
        ? new Date(session.tokens.expiresAt).toISOString()
        : "unknown";
      const workspace = session?.workspace?.workspaceSlug ?? "(unknown)";
      process.stdout.write(
        `Signed in. Workspace: ${workspace}. Access token expiry: ${expiresAt}.\n`,
      );
      return 0;
    }
    case "refresh": {
      const session = await app.auth.refreshToken();
      const expiresAt = new Date(session.tokens.expiresAt).toISOString();
      process.stdout.write(`Token refreshed. New expiry: ${expiresAt}.\n`);
      return 0;
    }
    default:
      printHelp();
      return 0;
  }
}

main()
  .then((code) => {
    if (code !== null) {
      process.exit(code);
    }
  })
  .catch((error: unknown) => {
    if (AuthError.is(error)) {
      process.stderr.write(
        `Authentication error [${error.code}]: ${error.message}\n`,
      );
    } else {
      process.stderr.write(`Unexpected error: ${String(error)}\n`);
    }
    process.exit(1);
  });
