import type { AuthConfig } from "../../config/AuthConfig.js";
import type { Logger } from "../../logging/Logger.js";
import type { TokenClient } from "../token/TokenClient.js";
import type { BrowserLauncher } from "./BrowserLauncher.js";
import type { LoginFlow, LoginResult } from "./LoginFlow.js";
import { LoopbackServer } from "./LoopbackServer.js";
import { createState } from "./state.js";

/**
 * Browser handoff login (no OAuth, no PKCE, no JWT).
 *
 * Steps:
 *  1. Start a loopback server and learn the concrete redirect URI.
 *  2. Generate a CSRF `state`.
 *  3. Open the existing Kontexa login page in MCP mode:
 *     `${loginPageUrl}?mcp=1&redirect_uri=<loopback>&state=<state>`.
 *  4. The user signs in with their existing Kontexa credentials; the page calls
 *     the backend `/api/auth/mcp/complete` and redirects the browser to the
 *     loopback with a single-use `code`.
 *  5. Verify `state`, then redeem the `code` for session tokens via the backend
 *     `/api/auth/mcp/exchange`.
 */
export class BrowserHandoffLoginFlow implements LoginFlow {
  constructor(
    private readonly config: AuthConfig,
    private readonly tokenClient: TokenClient,
    private readonly browser: BrowserLauncher,
    private readonly logger: Logger,
    private readonly loopbackFactory: () => LoopbackServer = () =>
      new LoopbackServer(config, logger),
  ) {}

  async authenticate(): Promise<LoginResult> {
    const loopback = this.loopbackFactory();
    try {
      const { redirectUri } = await loopback.start();
      const state = createState();
      const loginUrl = this.buildLoginUrl(redirectUri, state);

      // Diagnostics: confirm the callback URL, state, and the fully-formed
      // browser URL (with redirect_uri + state) before launching the browser.
      this.logger.info("MCP login — loopback callback URL", { redirectUri });
      this.logger.info("MCP login — state", { state });
      this.logger.info("MCP login — opening browser URL", { url: loginUrl });

      await this.browser.open(loginUrl);

      const { code } = await loopback.waitForCallback(state);
      this.logger.debug("Handoff code received; exchanging for tokens");

      return await this.tokenClient.exchangeHandoffCode(code);
    } finally {
      await loopback.stop();
    }
  }

  private buildLoginUrl(redirectUri: string, state: string): string {
    const url = new URL(this.config.loginPageUrl);
    url.searchParams.set("mcp", "1");
    url.searchParams.set("redirect_uri", redirectUri);
    url.searchParams.set("state", state);
    return url.toString();
  }
}
