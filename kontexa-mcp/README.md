# kontexa-mcp

Standalone **Model Context Protocol (MCP)** service for Kontexa, enabling Kontexa to run inside Claude alongside the existing individual product.

> **Phase 0 — Authentication foundation only.**
> This package currently implements *only* the authentication layer. There are
> **no Claude tools, business questions, SQL generation, or warehouse access**
> yet. Those arrive in later phases. The existing Kontexa backend/frontend
> codebases are untouched by this service.

## Why this exists

Kontexa is moving toward two delivery surfaces:

1. The existing **individual** web product.
2. A new **Claude** surface, where users interact with Kontexa from inside Claude via MCP.

Both surfaces must authenticate against the same Kontexa identity. This service
owns that authentication concern in isolation, so the rest of the MCP server can
depend on a single, stable abstraction: [`AuthenticationManager`](src/auth/AuthenticationManager.ts).

## Architecture

Authentication is deliberately **independent of analytics**. The public entry
point is the `AuthenticationManager` interface; everything else is a swappable
implementation detail behind an interface.

```
AuthenticationManager (interface)              ← the only thing consumers depend on
  └─ KontexaAuthenticationManager (impl)
       ├─ LoginFlow (interface)
       │    └─ BrowserOAuthLoginFlow           ← OAuth 2.0 + PKCE, loopback redirect
       │         ├─ BrowserLauncher            ← opens the system browser
       │         └─ LoopbackServer             ← localhost callback receiver
       ├─ TokenClient (interface)
       │    └─ HttpTokenClient                 ← code exchange + refresh + revoke
       └─ SessionStore (interface)
            └─ FileSessionStore                ← encrypted, 0600, atomic writes
                 └─ EncryptionProvider (interface)
                      └─ MachineKeyEncryptionProvider  ← AES-256-GCM at rest
```

### Login flow (browser-based, OAuth-style)

1. A short-lived **loopback HTTP server** binds to `127.0.0.1` on a random port.
2. The MCP generates a **PKCE** verifier/challenge and a CSRF `state`.
3. The system **browser opens the Kontexa login page**.
4. After the user signs in, Kontexa **redirects back to the loopback** with an
   authorization `code`.
5. The MCP **exchanges the code** (+ PKCE verifier) for a **JWT access token and
   refresh token**.
6. Tokens are **encrypted and stored locally** and reused for future requests;
   expired access tokens are refreshed transparently.

## `AuthenticationManager` API

```ts
interface AuthenticationManager {
  login(): Promise<Session>;          // interactive browser login + persist
  logout(): Promise<void>;            // revoke (best-effort) + clear local session
  isLoggedIn(): Promise<boolean>;     // valid token, or refreshable
  refreshToken(): Promise<Session>;   // refresh using stored refresh token
  loadSession(): Promise<Session | null>;
  saveSession(): Promise<void>;
  getAccessToken(): Promise<string>;  // valid token, auto-refreshing as needed
}
```

`getAccessToken()` is the method downstream (future) MCP components should use —
it is the single coupling point between business logic and authentication.

## Getting started

```bash
cd kontexa-mcp
npm install
cp .env.example .env   # adjust if your backend isn't on http://localhost:8080
npm run build
```

Then exercise the auth foundation via the bundled CLI:

```bash
npm run auth:login     # opens the browser, stores an encrypted session
npm run auth:status    # shows whether a valid session exists
npm run auth:refresh   # refreshes the access token
npm run auth:logout    # clears the local session
```

## Configuration

All configuration is environment-driven with sensible defaults — see
[`.env.example`](.env.example). Key variables:

| Variable | Default | Purpose |
| --- | --- | --- |
| `KONTEXA_BASE_URL` | `http://localhost:8080` | Backend that issues/refreshes tokens |
| `KONTEXA_CLIENT_ID` | `kontexa-mcp-desktop` | OAuth client id for this desktop client |
| `KONTEXA_SCOPES` | `openid profile offline_access` | Requested scopes |
| `KONTEXA_SESSION_FILE` | `<home>/.kontexa/session.json` | Encrypted session location |
| `KONTEXA_LOG_LEVEL` | `info` | `debug`/`info`/`warn`/`error`/`silent` |

### Expected backend OAuth endpoints

This client assumes a standard OAuth 2.0 Authorization Code + PKCE setup. By
default it targets:

- `GET  {baseUrl}/oauth/authorize` — renders the Kontexa login page
- `POST {baseUrl}/oauth/token` — code exchange and refresh
- `POST {baseUrl}/oauth/revoke` — token revocation (optional)

Each can be overridden individually via environment variables if the backend
exposes them under different paths.

## Security notes

- The session file is written with `0600` permissions via an **atomic** write
  (temp file + rename) and **AES-256-GCM** encryption at rest.
- The encryption key is derived from machine-local inputs, so a copied session
  file is useless on another machine/account. The `EncryptionProvider` interface
  allows swapping in an OS keychain (Keychain / DPAPI / libsecret) later.
- All logs go to **stderr** to keep stdout clean for the future MCP stdio
  transport.

## Project layout

```
kontexa-mcp/
├── src/
│   ├── index.ts                       # CLI harness (auth only)
│   ├── config/AuthConfig.ts           # env-driven configuration
│   ├── logging/Logger.ts              # stderr logger
│   └── auth/
│       ├── AuthenticationManager.ts   # public interface
│       ├── KontexaAuthenticationManager.ts
│       ├── index.ts                   # factory + public exports
│       ├── errors/AuthError.ts
│       ├── models/Session.ts
│       ├── browser/                   # OAuth login flow + loopback + PKCE
│       ├── storage/                   # SessionStore + encryption
│       └── token/                     # TokenClient (HTTP)
├── package.json
├── tsconfig.json
└── .env.example
```

## What's intentionally NOT here (future phases)

- MCP server wiring / Claude tool definitions
- Natural-language questions, intent parsing, SQL generation
- Warehouse / catalogue access
- Any change to the existing `backend` or `frontend` projects
