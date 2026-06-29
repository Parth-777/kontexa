# Browser-Based MCP Login — Architecture & Backend Change Design

**Status:** Design proposal (Phase 0.5)
**Scope:** Enable an MCP client (Claude Desktop) to authenticate a user against the
**existing** Kontexa username/password + session-token system by opening the
**existing** Kontexa login page in the browser.

**Explicit non-goals / constraints (per request):**

- ❌ No OAuth 2.0 (no authorization server, scopes, client registration).
- ❌ No PKCE.
- ❌ No JWT (the backend doesn't use JWT today; we keep its opaque-token model).
- ❌ No changes to analytics, planners, SQL generation, catalogue logic, or
  warehouse execution.
- ✅ Reuse the existing user model (admin creates users → username/password).
- ✅ Reuse the existing login endpoint and `auth_sessions` token model.
- ✅ Keep backend changes to the practical minimum.

> This document only *describes* backend and frontend changes. No existing code
> has been modified by writing this document.

---

## 1. Ground truth: how Kontexa auth works today

This design is built directly on the current implementation (not OAuth). The
relevant facts:

| Concern | Current reality |
| --- | --- |
| Framework | **No Spring Security.** A Spring MVC `HandlerInterceptor` (`identity/auth/AuthInterceptor.java`) guards `/api/**`. |
| Login endpoint | `POST /api/auth/login` (`identity/WorkspaceAuthController.java`), body `{ workspaceId, password, deviceLabel? }`. |
| Login response | JSON body containing `accessToken`, `refreshToken`, `accessExpiresAt`, `refreshExpiresAt`, plus identity/workspace/role fields. |
| Token type | **Opaque random strings** (64 hex chars), not JWT. Only SHA-256 hashes are stored in the `auth_sessions` table (`identity/session/SessionService.java`, `TokenHasher.java`). |
| Token transport | `Authorization: Bearer <accessToken>` header. No cookies. |
| Refresh | `POST /api/auth/refresh` body `{ refreshToken }` → new access token (refresh token reused, not rotated). |
| Token TTLs | Access **60 min**, refresh **14 days** (`kontexa.auth.session.*` in `application.properties`). |
| Session creation primitive | `SessionService.createSession(identity, workspaceId, deviceLabel, userAgent, ip)` → `SessionTokens`. Supports a **deviceLabel** per session. |
| Frontend login | React `SignInPage.jsx` → `authApi.workspaceLogin()` → stores the session in `window.sessionStorage` (`api/session.js`). Frontend dev origin `http://localhost:3000`; backend API `http://localhost:5000`. |
| CORS | Hardcoded allow `http://localhost:3000`, `allowCredentials(true)` (`config/CorsConfig.java`). |
| Public (no-token) paths | Declared in `AuthInterceptor.PUBLIC_PREFIXES` (e.g. `/api/auth/login`, `/api/auth/refresh`). |

**Key consequences for this design:**

1. We do **not** need an authorization server. The "session" already exists the
   moment `POST /api/auth/login` succeeds — we only need to *deliver an existing
   session token to the local MCP process*.
2. Because sessions already carry a `deviceLabel`, the MCP can get its **own
   independent session** (`deviceLabel = "Claude Desktop (MCP)"`), separately
   revocable from the web session.
3. The only genuinely new problem is **handoff**: moving a token from the
   browser (where the human logs in) to the local MCP process.

---

## 2. The core problem: browser → MCP token handoff

```
┌──────────────┐        opens browser         ┌──────────────────────┐
│  MCP server  │ ───────────────────────────▶ │  System web browser  │
│ (local node) │                              │  (user logs in here) │
└──────────────┘                              └──────────────────────┘
       ▲                                                  │
       │   ??? how does the token get back here ???       │ logs in OK,
       └──────────────────────────────────────────────────┘ token lives in browser
```

The browser ends up holding the token; the MCP process needs it. We bridge this
gap with the well-established **loopback redirect** technique (used by CLIs and
desktop apps — this is *not* OAuth, just a localhost HTTP handshake):

- The MCP starts a tiny **HTTP server on `127.0.0.1:<random port>`** before
  opening the browser.
- After a successful login, the browser is redirected to that loopback address,
  carrying a **single-use handoff code** (never the token itself).
- The MCP exchanges that code with the backend for the real session tokens.

This keeps long-lived tokens out of browser URLs/history and out of logs.

---

## 3. Recommended architecture

### 3.1 End-to-end flow

```mermaid
sequenceDiagram
    participant Claude as Claude / MCP tool call
    participant MCP as kontexa-mcp (local)
    participant LB as Loopback http://127.0.0.1:PORT
    participant BR as System Browser
    participant FE as Kontexa Web (login page, :3000)
    participant BE as Kontexa Backend (:5000)

    Claude->>MCP: User asks a question
    MCP->>MCP: isLoggedIn()? (local session valid / refreshable)
    alt No valid session
        MCP->>LB: start loopback server, pick PORT
        MCP->>MCP: generate random `state`
        MCP->>BR: open ${FE}/signin?mcp=1&redirect_uri=http://127.0.0.1:PORT/callback&state=STATE
        BR->>FE: load existing login page (mcp mode)
        Note over FE: User logs in with existing username/password
        FE->>BE: POST /api/auth/login {workspaceId,password,deviceLabel:"Claude Desktop (MCP)"}
        BE-->>FE: 200 {accessToken, refreshToken, ...}   (existing behavior, unchanged)
        FE->>BE: POST /api/auth/mcp/complete {redirectUri, state}  (Authorization: Bearer access)
        BE->>BE: validate redirectUri is loopback; mint one-time CODE bound to session
        BE-->>FE: 200 {redirectUri: "http://127.0.0.1:PORT/callback?code=CODE&state=STATE"}
        FE->>BR: window.location = redirectUri
        BR->>LB: GET /callback?code=CODE&state=STATE
        LB->>MCP: verify state matches; capture CODE
        MCP->>BE: POST /api/auth/mcp/exchange {code: CODE}
        BE->>BE: validate code (single-use, unexpired); load bound session tokens
        BE-->>MCP: 200 {accessToken, refreshToken, accessExpiresAt, refreshExpiresAt, identity...}
        MCP->>MCP: store session locally (encrypted), stop loopback
        LB-->>BR: "You're signed in. Return to Claude." (HTML)
    end
    MCP->>BE: retry original request with Authorization: Bearer access
    BE-->>MCP: data
    MCP-->>Claude: answer
```

### 3.2 Why this shape (and how it avoids OAuth/PKCE)

- **No authorization server / no scopes / no client secret.** The browser logs
  in through the *exact same* `POST /api/auth/login` the web app already uses.
- **No PKCE.** PKCE protects a *public-client code exchange* against
  interception on the redirect. We don't need it because (a) the redirect target
  is the loopback on the *same machine*, and (b) the `/mcp/complete` step that
  mints the code is **authenticated with the user's freshly issued Bearer
  token** — only the person who just logged in can produce a code. The `state`
  parameter (MCP-generated, verified at the loopback) prevents a foreign page
  from injecting a code into our loopback.
- **One-time code, not the token, travels in the browser URL.** The actual
  session tokens are only ever transmitted over direct `localhost` HTTP between
  MCP↔backend (`/mcp/exchange`) and backend↔browser (`/api/auth/login`, which is
  already the case today).

---

## 4. The four items requested, identified

### 4.1 Session creation endpoint

**Reused, unchanged:** `POST /api/auth/login` in `WorkspaceAuthController`. The
MCP-mode browser hits this exactly like the web app does. The only difference is
the `deviceLabel` (`"Claude Desktop (MCP)"`), which the existing endpoint already
accepts. The underlying session row is created by the existing
`SessionService.createSession(...)`.

> No new "session creation" logic is introduced. MCP sessions are ordinary
> `auth_sessions` rows, distinguishable only by `device_label`.

### 4.2 Browser redirect flow

1. MCP opens: `${frontend-base-url}/signin?mcp=1&redirect_uri=<loopback>&state=<state>`
   (`frontend-base-url` already exists as `kontexa.auth.frontend-base-url`).
2. User authenticates on the **existing** login page.
3. On success, the page calls the new `POST /api/auth/mcp/complete` and receives
   a fully-formed loopback redirect URL.
4. The page sets `window.location` to that URL (instead of navigating to the
   dashboard) → the browser hits the MCP loopback with the one-time code.

### 4.3 Token handoff mechanism

A **single-use, short-lived handoff code**:

- Minted by `POST /api/auth/mcp/complete` (authenticated), bound to a freshly
  created MCP session for the same identity/workspace.
- Delivered to the MCP via the loopback redirect (`?code=...&state=...`).
- Redeemed exactly once via `POST /api/auth/mcp/exchange` (public), which returns
  the real `accessToken`/`refreshToken` and then **invalidates the code**.
- TTL ≈ **60 seconds**; codes are high-entropy random; verified `state` ties the
  code to the MCP instance that initiated login.

### 4.4 Session persistence requirements

Two layers:

- **Backend (existing):** the session lives in `auth_sessions` (SHA-256 hashed
  tokens, 60-min access / 14-day refresh). Nothing new required here.
- **Handoff code store (new, ephemeral):** `code → { rawAccess, rawRefresh,
  expiresAt, identity/workspace summary }`, single-use, ~60s TTL. Recommended
  **in-memory** (`ConcurrentHashMap` + scheduled eviction) so raw tokens are
  never written to disk. *HA caveat:* if the backend runs multi-instance behind
  a load balancer, this map must move to a shared store (e.g. a small
  `mcp_handoff_codes` table or Redis) so `complete` and `exchange` can land on
  different nodes. For a single instance, in-memory is the safest minimum.
- **MCP client (already built):** tokens stored locally, encrypted at rest
  (AES-256-GCM, `0600`), via the existing `FileSessionStore` in this repo.

---

## 5. Minimum backend changes

All additions are **new files** — no edits to analytics/planner/catalogue/SQL.
Total: **one new controller, one new service, two new public interceptor paths,
and (optionally) one CORS line.**

### 5.1 New endpoints (`identity/mcp/McpLoginController.java`)

#### `POST /api/auth/mcp/complete` — authenticated (Bearer required)

Called by the login page immediately after a successful login while in MCP mode.

Request:

```json
{ "redirectUri": "http://127.0.0.1:53124/callback", "state": "<opaque>" }
```

Backend behavior:

1. Read the caller's `AuthContext` (populated by the existing `AuthInterceptor`).
   If absent → `401`.
2. Validate `redirectUri`: scheme `http`, host ∈ {`127.0.0.1`, `localhost`,
   `[::1]`}, path `/callback`. Reject anything else → `400` (this is the key
   guard that replaces OAuth redirect-URI registration).
3. Mint a **new MCP-labelled session** for the caller's identity + active
   workspace via `SessionService.createSession(identity, workspaceId,
   "Claude Desktop (MCP)", userAgent, ip)`.
4. Generate a random `code`; store `code → { sessionTokens, state, expiresAt =
   now+60s }` in the handoff registry.
5. Respond:

```json
{ "redirectUri": "http://127.0.0.1:53124/callback?code=<code>&state=<state>" }
```

> `complete` being authenticated is the security crux: only the user who just
> logged in can mint a handoff code, and it is bound to *their* new session.

#### `POST /api/auth/mcp/exchange` — public

Called by the MCP process (server-to-server, no browser, no CORS).

Request:

```json
{ "code": "<code>" }
```

Backend behavior:

1. Look up `code` in the registry; if missing/expired → `400/410` and ensure
   removal.
2. **Delete the code (single-use)** before returning.
3. Respond with the bound session tokens (same shape the web login returns):

```json
{
  "accessToken": "…",
  "refreshToken": "…",
  "accessExpiresAt": "2026-06-27T12:00:00",
  "refreshExpiresAt": "2026-07-11T11:00:00",
  "identityId": "…", "workspaceId": "…", "workspaceSlug": "…",
  "email": "…", "displayName": "…", "role": "ANALYST"
}
```

#### (Optional hardening) `GET /api/auth/mcp/authorize` — public

If you prefer the backend (not the SPA) to own the loopback registration, add a
pre-step: MCP opens `…/api/auth/mcp/authorize?redirect_uri=…&state=…`; the
backend validates the loopback, stores a pending-login id, and 302-redirects to
`${frontend-base-url}/signin?mcpLogin=<id>`. This removes `redirect_uri` from the
SPA's responsibility. It is **not required** for the minimal flow and adds a
second TTL store, so it's listed as optional.

### 5.2 New service (`identity/mcp/McpHandoffRegistry.java`)

A small component holding the ephemeral code store:

- `String issue(SessionTokens tokens, String state, identitySummary)` → returns
  `code`.
- `Optional<Handoff> redeem(String code)` → returns and removes (single-use).
- Background eviction of entries past `expiresAt` (e.g. a
  `@Scheduled` sweep or a `DelayQueue`).
- **In-memory `ConcurrentHashMap`** for single-instance; documented swap point
  for Redis/DB in HA.

### 5.3 Interceptor path additions (`identity/auth/AuthInterceptor.java`)

Add to `PUBLIC_PREFIXES`:

```
/api/auth/mcp/exchange
/api/auth/mcp/authorize   (only if the optional authorize step is adopted)
```

`/api/auth/mcp/complete` is intentionally **not** public — it must run under the
just-issued Bearer token.

### 5.4 CORS

The existing rule already covers `/api/**` for `http://localhost:3000` with
credentials, so `/mcp/complete` (called by the SPA) works unchanged.
`/mcp/exchange` is called by the Node MCP process directly (no `Origin`, no
preflight), so CORS does not apply. **No CORS change strictly required**; only
add origins if the login page is ever served from a non-localhost host.

### 5.5 Summary of backend deltas

| Change | File | Type |
| --- | --- | --- |
| `POST /api/auth/mcp/complete`, `POST /api/auth/mcp/exchange` | `identity/mcp/McpLoginController.java` | new |
| Ephemeral handoff code registry | `identity/mcp/McpHandoffRegistry.java` | new |
| Reuse session minting | `identity/session/SessionService.java` | **unchanged** (called, not edited) |
| 1–2 public path entries | `identity/auth/AuthInterceptor.java` | tiny edit |
| (optional) `GET /api/auth/mcp/authorize` | `McpLoginController.java` | new, optional |

Nothing in `analytics/`, `catalogue/`, or planner/SQL/warehouse code is touched.

---

## 6. Minimum frontend change

One conditional branch in the existing `SignInPage.jsx` (plus a tiny helper in
`authApi.js`). Pseudocode:

```js
// after successful workspaceLogin(...) and saveSession(...)
const params = new URLSearchParams(window.location.search);
if (params.get("mcp") === "1") {
  const redirectUri = params.get("redirect_uri");
  const state = params.get("state");
  const { redirectUri: target } = await mcpComplete({ redirectUri, state }); // POST /api/auth/mcp/complete (Bearer)
  window.location.assign(target); // hand off to the loopback
  return; // do NOT navigate to dashboard
}
// ...existing dashboard navigation
```

This is the **only** frontend modification, and it leaves the normal web login
path untouched. (If you'd rather not touch the React app at all, adopt the
optional §5.1 `authorize` step plus a minimal backend-rendered confirmation
page — at the cost of more backend code.)

---

## 7. MCP client changes (this repo)

The Phase 0 auth foundation already has the right seams; we swap the login
*strategy* and reuse everything else:

| Component | Action |
| --- | --- |
| `LoginFlow` (interface) | **Reused as-is.** |
| `BrowserOAuthLoginFlow` | **Replaced** by a new `BrowserHandoffLoginFlow` implementing `LoginFlow` (no PKCE; opens `${frontend}/signin?mcp=1&redirect_uri&state`, waits for loopback `code`, calls `/mcp/exchange`). |
| `LoopbackServer` | **Reused** (already validates `state`, serves success page). The `code_challenge`/PKCE bits are simply not used. |
| `BrowserLauncher` | **Reused** unchanged. |
| `TokenClient` | **Adapted**: `exchange(code)` hits `POST /api/auth/mcp/exchange`; `refresh()` hits the existing `POST /api/auth/refresh`. |
| `SessionStore` / encryption | **Reused** unchanged (encrypted local storage). |
| `AuthenticationManager` | **Reused** unchanged — `login()/isLoggedIn()/refreshToken()/getAccessToken()` all still apply. |
| Config | Replace OAuth endpoint fields with: `loginPageUrl` (`${frontend}/signin`), `mcpExchangeEndpoint`, `refreshEndpoint`. |

Net effect: the public `AuthenticationManager` API the rest of the MCP depends on
**does not change**; only the internal login strategy and two endpoint URLs do.

---

## 8. Security considerations

- **Loopback only.** `redirectUri` host is restricted to `127.0.0.1`/`localhost`
  server-side; the loopback binds to `127.0.0.1` (never `0.0.0.0`).
- **One-time code, short TTL.** ~60s, single-use, high-entropy. The code, not the
  token, is the only secret that transits the browser URL.
- **`complete` is authenticated.** A handoff code can only be created by the user
  who just logged in, and is bound to their new session — this is what lets us
  safely skip PKCE.
- **`state` round-trip.** MCP-generated, verified at the loopback; prevents a
  malicious local page from injecting a foreign code.
- **Independent revocation.** The MCP session is a distinct `auth_sessions` row
  (`device_label = "Claude Desktop (MCP)"`) and can be revoked without affecting
  the web session.
- **Tokens at rest.** Stored by the MCP encrypted (AES-256-GCM, `0600`).
- **Pre-existing issues (out of scope, flagged):** the current `AuthInterceptor`
  is fail-open for header-less requests, refresh tokens are not rotated, and
  `application.properties` contains committed secrets. None are introduced by
  this design; recommend addressing separately.

---

## 9. Alternatives considered

| Alternative | Why not (for now) |
| --- | --- |
| **Tokens in URL fragment** (`/callback#accessToken=…`) | Simplest, zero handoff endpoint, but puts long-lived refresh tokens in browser history/URL. Rejected on security grounds. |
| **Manual copy-paste code** (user pastes a code into Claude) | No loopback needed, works in headless/remote setups, but poor UX. Keep as a *fallback* if the loopback can't bind. |
| **Device-code style polling** | MCP polls backend until login completes. More backend state and polling; unnecessary when a loopback is available locally. |
| **Full OAuth + PKCE** | Explicitly out of scope, and overkill given the existing opaque-session model. |

---

## 10. Phased implementation checklist

1. **Backend**
   - [ ] `McpHandoffRegistry` (in-memory, TTL eviction).
   - [ ] `McpLoginController` with `/mcp/complete` (auth) + `/mcp/exchange` (public).
   - [ ] Add `/api/auth/mcp/exchange` to `AuthInterceptor.PUBLIC_PREFIXES`.
   - [ ] Unit tests: redirect-uri validation, code single-use/expiry, state binding.
2. **Frontend**
   - [ ] MCP-mode branch in `SignInPage.jsx` + `mcpComplete()` in `authApi.js`.
3. **MCP client (this repo)**
   - [ ] `BrowserHandoffLoginFlow` implementing `LoginFlow`.
   - [ ] Adapt `TokenClient` (`exchange`, `refresh`) and config fields.
   - [ ] Wire `isLoggedIn()` to validate/refresh against `/api/auth/refresh`.
4. **Validation**
   - [ ] Cold start → ask question → browser login → answer returns.
   - [ ] Expired access token → silent refresh.
   - [ ] Revoked MCP session → re-login prompt.

---

## 11. Open decisions (need product/eng confirmation)

1. **Single-instance vs HA backend?** Determines in-memory vs shared handoff
   store (§4.4 / §5.2).
2. **Touch the React login page, or add a backend-rendered MCP page?** (§6 vs the
   optional §5.1 `authorize` step). Recommendation: the one-branch SPA change —
   it's smaller and reuses the real login UI.
3. **`workspaceId` selection in MCP mode.** The web login takes a `workspaceId`;
   confirm the MCP login page collects the same field (it does today) so no new
   UI is needed.
4. **MCP session TTL policy.** Keep the standard 60-min/14-day, or give MCP
   device sessions a longer refresh window for less frequent re-auth?
```
