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
 *
 * Auth product flow (when {@link AskKontexaOptions.interactiveLogin} is true):
 *  1. Never logged in  → open Kontexa website login → handoff back → ask
 *  2. Session expired / refresh dead → same browser login → handoff → ask
 */
export async function askKontexa(
  question: string,
  deps: AskKontexaDeps,
  options: AskKontexaOptions = {},
): Promise<unknown> {
  const { auth, client, config, logger } = deps;

  logger.info("ask_kontexa tool called");
  logger.info("ask_kontexa question received", { question });

  // 1. Ensure a usable access token (login / re-login via browser if needed).
  const accessToken = await ensureAccessToken(auth, logger, options);

  // 2. Resolve tenant/workspace context (decision endpoint needs X-Client-Id).
  //    After the tenant-isolation backend fix, the backend derives tenant from
  //    AuthContext; the header is only a matching hint / ignored if equal.
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

  // 3. Forward to Kontexa and return its answer unchanged.
  try {
    const answer = await client.ask({ question, accessToken, tenantId });
    logger.info("ask_kontexa success");
    return answer;
  } catch (error) {
    logger.error("ask_kontexa failure", { error: String(error) });
    throw error;
  }
}

/**
 * Obtain a valid access token, launching browser login when:
 * - there is no local session, or
 * - the access token is expired and refresh fails (dead refresh / revoked).
 */
async function ensureAccessToken(
  auth: AuthenticationManager,
  logger: Logger,
  options: AskKontexaOptions,
): Promise<string> {
  const interactive = options.interactiveLogin === true;

  const loggedIn = await auth.isLoggedIn();
  if (!loggedIn) {
    if (!interactive) {
      logger.warn("ask_kontexa blocked — not authenticated");
      throw new AuthError(
        "NOT_AUTHENTICATED",
        "Not signed in to Kontexa. Run `kontexa-mcp login` first.",
      );
    }
    logger.info("Not authenticated — starting interactive browser login");
    await auth.login();
    return auth.getAccessToken();
  }

  try {
    return await auth.getAccessToken();
  } catch (error) {
    const needsReLogin =
      AuthError.is(error) &&
      (error.code === "TOKEN_REFRESH_FAILED" ||
        error.code === "NOT_AUTHENTICATED");

    if (!needsReLogin || !interactive) {
      throw error;
    }

    // Stale local session: access expired and refresh token is dead.
    // Clear it and open the website login again (same handoff as first login).
    logger.info(
      "Session expired or refresh failed — starting interactive browser re-login",
      { error: error.message, code: error.code },
    );
    try {
      await auth.logout();
    } catch (logoutError) {
      logger.warn("Local session clear during re-login had issues", {
        error: String(logoutError),
      });
    }
    await auth.login();
    return auth.getAccessToken();
  }
}
