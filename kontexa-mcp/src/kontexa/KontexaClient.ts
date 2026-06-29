import type { AuthConfig } from "../config/AuthConfig.js";
import type { Logger } from "../logging/Logger.js";

export interface AskParams {
  /** The user's natural-language question, passed through unchanged. */
  readonly question: string;
  /** Bearer access token for the authenticated Kontexa session. */
  readonly accessToken: string;
  /** Tenant/workspace identifier sent as `X-Client-Id` (decision endpoint). */
  readonly tenantId?: string;
}

/**
 * Thin HTTP transport to the EXISTING Kontexa "ask a question" endpoint
 * (`POST /api/decision/v1/run`).
 *
 * This contains NO analytics logic — it forwards the question verbatim and
 * returns whatever JSON the backend produces. The backend owns all planning,
 * SQL, catalogue, and reasoning behaviour.
 */
export class KontexaClient {
  constructor(
    private readonly config: AuthConfig,
    private readonly logger: Logger,
    private readonly fetchImpl: typeof fetch = fetch,
  ) {}

  async ask(params: AskParams): Promise<unknown> {
    const headers: Record<string, string> = {
      "Content-Type": "application/json",
      Accept: "application/json",
      Authorization: `Bearer ${params.accessToken}`,
    };
    if (params.tenantId) {
      headers["X-Client-Id"] = params.tenantId;
    }

    this.logger.info("Invoking Kontexa question endpoint", {
      endpoint: this.config.questionEndpoint,
      tenantId: params.tenantId ?? "(none)",
    });

    let response: Response;
    try {
      response = await this.fetchImpl(this.config.questionEndpoint, {
        method: "POST",
        headers,
        // Question passed through unchanged.
        body: JSON.stringify({ question: params.question }),
      });
    } catch (error) {
      this.logger.error("Kontexa request failed (network)", {
        error: String(error),
      });
      throw new Error(
        `Failed to reach Kontexa at ${this.config.questionEndpoint}: ${String(error)}`,
      );
    }

    const text = await response.text();
    let json: unknown;
    try {
      json = text.length > 0 ? JSON.parse(text) : {};
    } catch {
      json = { raw: text };
    }

    if (!response.ok) {
      const detail =
        (json as { error?: string })?.error ?? `HTTP ${response.status}`;
      this.logger.error("Kontexa request failed (status)", {
        status: response.status,
        detail,
      });
      throw new Error(`Kontexa returned an error: ${detail}`);
    }

    this.logger.info("Kontexa question endpoint returned a response", {
      status: response.status,
    });
    return json;
  }
}
