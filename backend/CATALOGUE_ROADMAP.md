# Kontexa — Generic NLP Analytics Engine
## Catalogue System Roadmap

---

## Vision

A self-service analytics platform where:
- Any client connects their database (any schema, any tables)
- The system understands their data automatically
- The client validates and approves that understanding
- Then anyone on their team can ask questions in plain English and get answers

This powers Kontexa's core value:
> "You don't need to know SQL. You don't need to know your schema. Just ask."

---

## Two Phases

```
PHASE 1: Catalogue Creation   → One-time setup per client
PHASE 2: Query Engine         → Ongoing, powers NLP → SQL per question
```

---

---

# PHASE 1: Catalogue Creation

---

## Step 1.1 — Client Connects Their Database

**What happens:**
The client provides a database connection string.
Your system connects and runs metadata discovery queries automatically
against the database's own system tables (e.g., `information_schema` in PostgreSQL).

**Queries run automatically:**
- What tables exist?
- What columns does each table have?
- What data types are each column?
- Are there foreign keys / relationships between tables?
- How many rows in each table?

**No manual work needed.** This is all available from system metadata.

**Output:**
A raw structural snapshot of the client's database.

```
Table: orders
  - order_id       (uuid)
  - order_status   (text)
  - order_total    (numeric)
  - created_at     (timestamp)
  - user_id        (uuid)

Table: users
  - user_id        (uuid)
  - country        (text)
  - signup_date    (timestamp)
  - email          (text)
```

---

## Step 1.2 — Sample the Actual Data

**What happens:**
Raw schema gives column names and types.
But it doesn't tell you what the values *mean*.

You automatically sample the top N distinct values per column:

**Example:**
```
event_name  → ["purchase", "page_view", "signup_started", "login"]
vendor      → ["AMPLITUDE", "MIXPANEL"]
country     → ["IN", "US", "UK", "DE"]
os          → ["android", "ios", "web"]
order_status → ["pending", "completed", "cancelled", "refunded"]
```

**Why this matters:**
The LLM needs real values — not just column names — to correctly map
English like "India" → `country = 'IN'`
or "completed orders" → `order_status = 'completed'`.

**Output:**
Each column enriched with real example values from the client's database.

---

## Step 1.3 — LLM Auto-generates Descriptions and Synonyms

**What happens:**
You take the raw schema + sampled data and send it to the LLM.

**Prompt template:**
```
Here is a database table called "orders":
- order_id (uuid): sample values [abc123, def456]
- order_status (text): sample values [pending, completed, cancelled, refunded]
- order_total (numeric): sample values [299, 1499, 89.99]
- created_at (timestamp): sample values [2026-01-01, 2026-01-15]

For each column, generate:
1. A plain English description of what this column means
2. English synonyms a non-technical user might say to refer to it
3. Any business meaning hidden in the values
```

**LLM returns enriched metadata:**
```json
{
  "order_status": {
    "description": "Current status of the order in its lifecycle",
    "synonyms": ["order state", "status", "where is my order"],
    "value_meanings": {
      "completed": "successfully delivered",
      "refunded": "money returned to customer"
    }
  },
  "order_total": {
    "description": "Monetary value of the order",
    "synonyms": ["revenue", "sales", "amount", "money", "price"]
  }
}
```

**Output:**
A rich, human-readable catalogue draft — automatically generated.
Client saves 90% of the manual work.

---

## Step 1.4 — Client Reviews and Approves the Catalogue

**This is the most important step.**

You present the auto-generated catalogue to the client for review.
This can be a UI, a JSON file, or a spreadsheet — depending on the stage.

**What the client can do:**

| Column | Auto-generated description | Client edits | Status |
|---|---|---|---|
| `order_total` | "Total value of the order" | "Revenue per transaction in INR" | ✅ Approved |
| `user_id` | "Unique user identifier" | (no change) | ✅ Approved |
| `created_at` | "Timestamp when created" | "Date the order was placed" | ✅ Approved |
| `int_flag` | "Integer flag field" | "Internal system flag — exclude from queries" | ❌ Blocked |

**Client actions:**
- Edit descriptions to match their business language
- Add synonyms: "we call this 'revenue', not 'order_total'"
- Mark columns as sensitive (hide from NLP queries)
- Add business rules: "revenue = SUM(order_total) WHERE order_status = 'completed'"
- Approve or reject the whole catalogue
- Flag columns to exclude entirely

**Output:**
A signed-off, client-approved catalogue.

---

## Step 1.5 — Store the Approved Catalogue

**What happens:**
Once approved, the catalogue is stored in Kontexa's own database
as a structured document, linked to the client's account.

**Stored structure per client:**

```
Client: Acme Corp
Database: PostgreSQL @ their server

Tables:
  orders:
    description: "Transactional data for all customer orders"
    columns:
      order_total:
        db_column: order_total
        description: Revenue per transaction in INR
        data_type: numeric
        example_values: [299, 1499, 89.99]
        synonyms: [revenue, sales, money, amount]
      order_status:
        db_column: order_status
        description: Order lifecycle state
        data_type: text
        example_values: [pending, completed, cancelled, refunded]
        synonyms: [status, state, delivered, cancelled]
        value_meanings:
          completed: successfully delivered
          refunded: money returned to customer

  users:
    description: "User profile and identity data"
    columns:
      country:
        db_column: country
        description: Country of the user
        data_type: text
        example_values: [IN, US, UK, DE]
        synonyms: [location, region, where, India, USA]

Business Rules:
  - "revenue" = SUM(order_total) WHERE order_status = 'completed'
  - "active users" = COUNT(DISTINCT user_id) WHERE last_seen > now() - 30 days
```

This catalogue is the **permanent knowledge base** for that client.

---
---

# PHASE 2: Query Engine (Using the Catalogue)

---

## Step 2.1 — Load Client's Catalogue on Every Query

**What happens:**
When a PM asks a question, the first thing the system does is
load that specific client's approved catalogue from Kontexa's database.

Every client gets their own catalogue. No sharing between clients.

---

## Step 2.2 — Build Schema-Aware LLM Prompt

**What happens:**
The system builds a prompt that includes:
1. The client's approved catalogue
2. The PM's English question

**Example prompt:**

```
You are querying a database for Acme Corp.

Available tables and columns:

Table: orders
- order_total (numeric): Revenue per transaction in INR.
  A user might say: revenue, sales, money, amount.
- order_status (text): Order lifecycle state.
  Values: pending, completed, cancelled, refunded.
  A user might say: status, delivered, cancelled.
- created_at (timestamp): Date the order was placed.
  A user might say: date, when, last month, this week.
- user_country (text): Country of the user.
  Values: IN, US, UK. A user might say: India, location, country.

Business Rules:
- "revenue" means SUM(order_total) WHERE order_status = 'completed'

Question: "What was the total revenue from India last month?"

Return ONLY this JSON:
{
  "table": "...",
  "metric": {"type": "SUM or COUNT", "field": "column name or null"},
  "filters": [{"field": "...", "operator": "EQUALS/CONTAINS/GREATER_THAN", "value": "..."}],
  "time_range": {"type": "RELATIVE/TODAY/LAST_7_DAYS/LAST_30_DAYS", "value": "days if relative"},
  "group_by": "column name or null"
}
```

---

## Step 2.3 — LLM Returns Structured Query Intent

**What happens:**
LLM reads the catalogue + question and returns structured JSON:

```json
{
  "table": "orders",
  "metric": {"type": "SUM", "field": "order_total"},
  "filters": [
    {"field": "user_country", "operator": "EQUALS", "value": "IN"},
    {"field": "order_status", "operator": "EQUALS", "value": "completed"}
  ],
  "time_range": {"type": "RELATIVE", "value": "30"},
  "group_by": null
}
```

The LLM doesn't write SQL. It returns **structured intent**.
SQL generation is handled by the existing Java pipeline.

---

## Step 2.4 — Existing Pipeline Takes Over (Unchanged)

**What happens:**
The structured JSON from the LLM feeds directly into the existing pipeline:

```
LLM JSON Intent
    ↓
QueryIntent (Java object)
    ↓
CqlIntentBuilder → CanonicalQuery
    ↓
SqlQueryBuilder → SqlQuery
    ↓
PostgresSqlGenerator → SQL string
    ↓
SqlQueryExecutor → Results
    ↓
JSON response to PM
```

**Nothing in the existing pipeline changes.**
The catalogue + LLM layer is purely an upgrade to the input stage.

---

## Step 2.5 — Multi-Table Routing

**What happens:**
When a client has multiple tables, the catalogue tells the LLM
which table to query based on what the question is about.

**Catalogue includes table-level descriptions:**
```
- orders: contains transaction data, revenue, order status, payment info
- users: contains user profiles, location, signup date, demographics
- products: contains product catalog, pricing, categories, inventory
```

**LLM decides which table to use:**
```
Question: "How many new users signed up from India last week?"
→ Table: users (because the question is about user signups and demographics)
```

The catalogue is the **table router**.

---

## Step 2.6 — Feedback Loop (Catalogue Gets Smarter)

**What happens:**
Every query result is logged. After showing results to the PM:

```
"Did this answer your question?"   [Yes] [No]
```

- If **Yes** → log as successful query pattern
- If **No** → flag for catalogue review

Flagged queries are reviewed:
- Wrong column used? → Add synonym to catalogue
- Wrong value mapped? → Add value meaning to catalogue
- Missing business rule? → Add rule to catalogue
- Re-approve the updated catalogue

**Over time:**
The catalogue becomes more accurate for each client.
Common questions are answered correctly without any developer involvement.

---
---

# Full Architecture Diagram

```
CLIENT ONBOARDING (one-time per client)
─────────────────────────────────────────────────────────────
Client provides DB connection string
    ↓
Step 1.1: Auto Schema Discovery
    (reads information_schema — tables, columns, types, foreign keys)
    ↓
Step 1.2: Data Sampler
    (reads top N distinct values per column)
    ↓
Step 1.3: LLM Auto-enrichment
    (generates descriptions + synonyms + value meanings)
    ↓
Step 1.4: Client Review & Approval
    (UI or spreadsheet — client edits, approves, blocks sensitive columns)
    ↓
Step 1.5: Approved Catalogue stored in Kontexa DB
    (per-client, versioned, reusable)


QUERY ENGINE (per question, ongoing)
─────────────────────────────────────────────────────────────
PM types English question
    ↓
Step 2.1: Load client's approved catalogue from Kontexa DB
    ↓
Step 2.2: Build schema-aware LLM prompt
    (catalogue + question + business rules)
    ↓
Step 2.3: LLM returns structured JSON intent
    (table, filters, metric, time range, group by)
    ↓
Step 2.4: Existing Java pipeline (UNCHANGED)
    QueryIntent → CanonicalQuery → SqlQuery → SQL → Results
    ↓
Step 2.5: Multi-table routing (if client has multiple tables)
    ↓
Step 2.6: Feedback loop
    (PM rates result → catalogue improves)
    ↓
JSON results returned to PM
```

---
---

# Build Order

| Priority | Step | What to Build | Why |
|---|---|---|---|
| 1 | Step 1.1 | Schema Discovery Service | Foundation — nothing works without this |
| 2 | Step 1.2 | Data Sampler | LLM needs real values to map English correctly |
| 3 | Step 1.5 | Catalogue Data Model (DB schema) | Where the approved catalogue lives |
| 4 | Step 1.3 | LLM Auto-enrichment | Auto-generates descriptions to save client work |
| 5 | Step 1.4 | Review API (client approval endpoints) | The approval gate |
| 6 | Step 2.2 | Prompt Builder | Connects catalogue to LLM |
| 7 | Step 2.3 | LLM Intent Extractor | The brain — NLP to structured JSON |
| 8 | Step 2.5 | Multi-table Router | Needed when client has more than one table |
| 9 | Step 2.6 | Feedback Loop | Makes it better over time |

---

# What Stays the Same

The entire existing query execution pipeline is **reused as-is**:

```
CanonicalQuery → SqlQueryBuilder → PostgresSqlGenerator → SqlQueryExecutor
```

The catalogue system replaces only the **input stage** (EnglishQueryParser)
with a smarter LLM-powered intent extractor.

All existing queries (home button, netflix signups, etc.) continue to work
through the existing rules-based parser as a fallback.

---

# Technology Stack

| Component | Technology |
|---|---|
| Schema Discovery | Java + JDBC (information_schema queries) |
| Data Sampler | Java + JDBC (SELECT DISTINCT queries) |
| Catalogue Storage | PostgreSQL (Kontexa's own DB) |
| LLM Enrichment | Java HTTP client → OpenAI API |
| LLM Intent Extraction | Java HTTP client → OpenAI API |
| Review API | Spring Boot REST endpoints |
| Existing Pipeline | Java (unchanged) |

No Python required. Entire system stays in Java.

---

# Key Principle

> The catalogue is the contract between the client's data and Kontexa's engine.
> Once approved, no developer intervention is needed for any question
> that falls within the scope of the catalogued tables.
