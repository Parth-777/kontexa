# Kontexa table classification & migration order

Phase A foundation. Physical table names are unchanged; domain names map as:
`Tenant` ↔ `workspaces`, `User` ↔ `identities`, `Membership` ↔ `workspace_memberships`,
`tenantId` ↔ `workspace_id`, `userId` ↔ `identity_id`.

## Classification

| Table | Classification | Notes |
|-------|----------------|-------|
| `workspaces` | **Tenant-owned root / Global registry of tenants** | Becomes `Tenant` aggregate root. Global list of companies; each row IS a tenant. |
| `identities` | **Needs tenant_id migration** | Today has `workspace_id` (user embedded in tenant). Target: global User + Membership only. Phase A: treat as User; keep column for compatibility. |
| `workspace_memberships` | **Tenant-owned** | Becomes `Membership`. Phase A repos require `tenantId`. |
| `auth_sessions` | **Global (pinned to tenant+user)** | Already stores `identity_id` (userId) + `active_workspace_id` (tenantId). |
| `password_reset_tokens` | **Global (user-scoped)** | Belongs to identity, not tenant data plane. |
| `auth_audit_events` | **Tenant-owned** (nullable workspace for platform events) | Needs consistent `tenant_id` usage later. |
| `workspace_invites` | **Tenant-owned** | Out of Phase A scope (invites later). |
| `invite_tokens` | **Tenant-owned / legacy invite** | Out of Phase A; may consolidate with `workspace_invites`. |
| `workspace_connectors` | **Tenant-owned** | Out of Phase A (connectors later). |
| `identity_migration_log` | **Global (ops)** | Migration audit. |
| `identity_migration_versions` | **Global (ops)** | Migration versions. |
| `user_credentials` | **Needs tenant_id migration → then delete** | Legacy tenant admin + cloud_db_link. |
| `app_users` | **Needs tenant_id migration → then delete** | Legacy users. |
| `tenant_credentials` | **Can be deleted** (if unused) / else migrate | Early schema; superseded by connectors + identity. |
| `client_catalogues` | **Needs tenant_id migration** | Scoped by string `client_id` today. |
| `catalogue_tables` | **Tenant-owned (via catalogue)** | Inherit tenant via catalogue FK; later denormalize `tenant_id` optional. |
| `catalogue_columns` | **Tenant-owned (via table)** | Same. |
| `catalogue_table_relations` | **Tenant-owned (via catalogue)** | Same. |
| `catalogue_snapshots` | **Needs tenant_id migration** | Scoped by `client_id` string. |
| `tenant_business_profiles` | **Needs tenant_id migration** | Likely string tenant key today. |
| `agent_runs` | **Needs tenant_id migration** | Tenant-owned AI/agent history. |
| `agent_reports` | **Needs tenant_id migration** | Tenant-owned. |
| `insight_cards` | **Needs tenant_id migration** | Tenant-owned product artifacts. |
| `signals` | **Needs tenant_id migration** | Tenant-owned. |
| `daily_metric_rollups` | **Needs tenant_id migration** | Tenant-owned analytics cache. |
| `decision_memory` | **Needs tenant_id migration** | Tenant-owned. |
| `onboarding_leads` | **Global** | Pre-tenant marketing/leads. |
| `form_submissions` | **Global** | Public form capture (if present). |

## Migration order (approved)

1. **Phase A (this change):** TenantContext + auth resolution + session tenantId/userId invariants + membership/tenant repositories requiring tenantId. No catalogue/connector/planner changes.
2. **Phase B:** Add UUID `tenant_id` to catalogues, snapshots, connectors cutover; stop string `client_id` authority.
3. **Phase C:** Persist decision/investigation history with `tenant_id`; harden all product APIs fail-closed.
4. **Phase D:** Make `identities` global (drop required `workspace_id`); enforce one active membership per user.
5. **Phase E:** Dashboards, invites polish, SSO.
6. **Phase F:** Delete legacy `user_credentials` / `app_users` / unused `tenant_credentials` after dual-read removed.

## Phase B physical DB changes

Script: `backend/src/main/resources/phase_b_tenant_id_migration.sql`

1. Add nullable UUID `tenant_id` to all `client_id`-scoped product tables; backfill from `workspaces.slug`.
2. Add `app_users.workspace_id` UUID; backfill from slug `tenant_id`.
3. Copy remaining `user_credentials.cloud_db_link` into `workspace_connectors` (idempotent).
4. Application dual-writes UUID + legacy slug; dual-reads prefer `tenant_id`.
5. Connector **writes** go to `workspace_connectors` only; legacy cloud_db_link is read fallback.

Rollback: `phase_b_tenant_id_rollback.sql` (drops columns only; does not wipe connectors).

