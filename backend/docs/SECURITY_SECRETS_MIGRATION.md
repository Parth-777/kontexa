# Security Foundation — Secret Hygiene Migration Guide

**Component:** #1 Secret Hygiene  
**Scope:** Configuration only. No auth-flow, API, or AI pipeline changes.

## What changed

Committed secrets were removed from `src/main/resources/application.properties`.

Secrets are now supplied by:

1. **Environment variables** (required for production), or  
2. **`src/main/resources/application-local.properties`** (gitignored; local profile only)

The app still reads the **same property keys** (`openai.api.key`, `spring.datasource.password`, `spring.mail.*`, etc.). No Java code changes.

## Environment variables

| Environment variable | Maps to | Required | Safe default (committed) |
|----------------------|---------|----------|---------------------------|
| `SPRING_DATASOURCE_URL` | `spring.datasource.url` | Prod: yes | `jdbc:postgresql://localhost:5432/admindb` |
| `SPRING_DATASOURCE_USERNAME` | `spring.datasource.username` | Prod: yes | `postgres` |
| `SPRING_DATASOURCE_PASSWORD` | `spring.datasource.password` | **Yes** | _(none — never committed)_ |
| `OPENAI_API_KEY` | `openai.api.key` | For GPT features | _(none)_ |
| `OPENAI_MODEL` | `openai.model` | No | `gpt-4o-mini` |
| `KONTEXA_MAIL_ENABLED` | `kontexa.mail.enabled` | No | `false` |
| `KONTEXA_MAIL_FROM` | `kontexa.mail.from` | No | `invites@kontexa.io` |
| `KONTEXA_MAIL_PRODUCT_NAME` | `kontexa.mail.product-name` | No | `Kontexa` |
| `SPRING_MAIL_HOST` | `spring.mail.host` | If mail on | `smtp.gmail.com` |
| `SPRING_MAIL_PORT` | `spring.mail.port` | If mail on | `587` |
| `SPRING_MAIL_USERNAME` | `spring.mail.username` | If mail on | _(none)_ |
| `SPRING_MAIL_PASSWORD` | `spring.mail.password` | If mail on | _(none)_ |
| `KONTEXA_AUTH_FRONTEND_BASE_URL` | `kontexa.auth.frontend-base-url` | No | `http://localhost:3000` |
| `SPRING_PROFILES_ACTIVE` | active profiles | Prod: set `prod` | default profile `local` |

Spring Boot relaxed binding accepts these env names without any entry in `application.properties`.

## Local development setup

```bash
cd backend
cp src/main/resources/application-local.properties.example \
   src/main/resources/application-local.properties
# Edit application-local.properties with real values
```

Notes:

- `application-local.properties` is **gitignored**.
- Loaded when the `local` profile is active (`spring.profiles.default=local`).
- If a password contains `$`, use a Unicode escape (e.g. `pass\u0024`) or set `SPRING_DATASOURCE_PASSWORD` in the environment instead.
- Maven copies this file into `target/classes` when present, so tests and IDE runs pick it up.

## Production checklist

1. Set `SPRING_PROFILES_ACTIVE=prod` (do **not** ship `application-local.properties`).  
2. Inject at least `SPRING_DATASOURCE_PASSWORD` and `OPENAI_API_KEY` via the host secret store.  
3. If invite email is required: `KONTEXA_MAIL_ENABLED=true` plus `SPRING_MAIL_USERNAME` / `SPRING_MAIL_PASSWORD`.  
4. **Rotate** any credentials that were previously committed in git history.  
5. Confirm `git status` never shows `application-local.properties`.

## Backward compatibility

| Concern | Behavior |
|---------|----------|
| Property keys | Unchanged — `@Value("${openai.api.key}")` etc. still work |
| Auth / login / APIs | Unchanged |
| AI / Phase 1 / Phase 2 | Unchanged |
| Local with `application-local.properties` | Same credentials as before (moved out of git) |
| Prod with env vars | Same keys, different source |
| Default mail enabled | `false` in committed config; local profile file may enable mail |

## Verification

```bash
git check-ignore -v src/main/resources/application-local.properties

# Committed properties must not contain live keys
rg -n "sk-proj-|spring\\.datasource\\.password=" src/main/resources/application.properties

.\mvnw.cmd -q test -Dtest=BackendApplicationTests
```

Runtime behavior is unchanged when the same secret values are supplied via env or `application-local.properties`.
