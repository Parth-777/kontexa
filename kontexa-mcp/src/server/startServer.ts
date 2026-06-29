import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";

import { askKontexa, type AskKontexaDeps } from "../tools/askKontexa.js";

/**
 * Start the Kontexa MCP server over stdio, exposing exactly ONE tool:
 * `ask_kontexa`. The tool is a pure transport: it forwards the question to the
 * existing Kontexa backend and returns the answer JSON.
 */
export async function startMcpServer(deps: AskKontexaDeps): Promise<void> {
  const { logger } = deps;

  const server = new McpServer({
    name: "kontexa-mcp",
    version: "0.1.0",
  });

  server.registerTool(
    "ask_kontexa",
    {
      title: "Ask Kontexa",
      description:
        "Ask a natural-language analytical question against your Kontexa " +
        "workspace and return Kontexa's answer. Use this whenever the user " +
        "asks about their business data, metrics, or analytics.",
      inputSchema: {
        question: z
          .string()
          .min(1)
          .describe("The natural-language question to ask Kontexa."),
      },
    },
    async ({ question }) => {
      try {
        const answer = await askKontexa(question, deps, {
          interactiveLogin: true,
        });
        return {
          content: [
            { type: "text" as const, text: JSON.stringify(answer, null, 2) },
          ],
        };
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        return {
          content: [
            { type: "text" as const, text: `ask_kontexa failed: ${message}` },
          ],
          isError: true,
        };
      }
    },
  );

  const transport = new StdioServerTransport();
  await server.connect(transport);
  // Logs go to stderr; stdout is reserved for the MCP protocol.
  logger.info("Kontexa MCP server started");
  logger.info("Transport: stdio. Registered tool: ask_kontexa");
}
