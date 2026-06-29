import type { AuthConfig } from "../config/AuthConfig.js";
import type { AuthenticationManager } from "../auth/AuthenticationManager.js";
import { AuthError } from "../auth/errors/AuthError.js";
import type { KontexaClient } from "../kontexa/KontexaClient.js";
import type { Logger } from "../logging/Logger.js";

export interface AskKontexaDeps {
  readonly auth: AuthenticationManager;
  readonly client: KontexaClient;
  readonly config: AuthConfig;
  readonly logger: Logger;
}

export interface AskKontexaOptions {
  /**
   * When true and no valid session exists, launch the interactive browser login
   * before asking. The MCP server passes true (matches the product flow); the
   * CLI smoke test passes false to avoid unexpectedly opening a browser.
   */
  readonly interactiveLogin?: boolean;
}

/**
 * Core logic for the single `ask_kontexa` tool.
 *
 * MCP is ONLY a transport layer here: Claude → MCP → Kontexa backend → MCP →
 * Claude. This function verifies authentication, obtains an access token, and
 * forwards the question unchanged to the existing Kontexa endpoint, returning
 * the backend's answer JSON as-is. No analytics logic lives here.
 */
export async function askKontexa(
  question: string,
  deps: AskKontexaDeps,
  options: AskKontexaOptions = {},
): Promise<unknown> {
  const { auth, client, config, logger } = deps;

  logger.info("ask_kontexa tool called");
  logger.info("ask_kontexa question received", { question });

  // 1. Verify authentication.
  const loggedIn = await auth.isLoggedIn();
  if (!loggedIn) {
    if (options.interactiveLogin) {
      logger.info("Not authenticated — starting interactive browser login");
      await auth.login();
    } else {
      logger.warn("ask_kontexa blocked — not authenticated");
      throw new AuthError(
        "NOT_AUTHENTICATED",
        "Not signed in to Kontexa. Run `kontexa-mcp login` first.",
      );
    }
  }

  // 2. Obtain a valid access token (auto-refreshes if needed).
  const accessToken = await auth.getAccessToken();

  // 3. Resolve tenant/workspace context (decision endpoint needs X-Client-Id).
  const session = await auth.getSession();
  const tenantId =
    config.defaultTenantId ??
    session?.workspace?.tenantId ??
    session?.workspace?.workspaceSlug;
  if (!tenantId) {
    logger.warn(
      "No tenant/workspace context available; Kontexa may be unable to resolve data",
    );
  }

  // 4. Forward to Kontexa and return its answer unchanged.
  try {
    const answer = await client.ask({ question, accessToken, tenantId });
    logger.info("ask_kontexa success");
    return answer;
  } catch (error) {
    logger.error("ask_kontexa failure", { error: String(error) });
    throw error;
  }
}
