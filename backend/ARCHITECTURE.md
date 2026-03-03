# Natural Language Analytics Engine - Architecture Documentation

## Table of Contents
1. [Overview](#overview)
2. [Core Architecture](#core-architecture)
3. [Data Pipeline](#data-pipeline)
4. [Key Components](#key-components)
5. [Data Models](#data-models)
6. [Implementation Details](#implementation-details)
7. [Current Status](#current-status)
8. [Future Enhancements](#future-enhancements)

---

## Overview

### What We're Building

A **natural-language analytics engine** that converts plain English queries into production-grade SQL for PostgreSQL analytics databases. This enables non-technical users (founders, PMs, growth teams) to query event data without writing SQL.

**Example Query:**
```
"What users clicked on the home button in the last 30 days?"
```

**Result:** Accurate SQL query executed against `public.canonical_events` table.

### Why an AI/Intelligence Layer?

Raw analytics data presents challenges:
- **Large scale**: Millions of events
- **Event-based**: Time-series data structure
- **Inconsistent**: Different vendors use different schemas
- **Domain knowledge required**: English concepts ≠ database schema

**Example Problem:**
- User says: "home button clicked"
- Database stores: `event_name = 'button_click'` AND `page_location = '/home'`

The intelligence layer bridges this semantic gap.

### Target Users

- Startup founders
- Product managers
- Growth & marketing teams
- Analysts who prefer not to write SQL
- Internal teams needing quick event data queries

**Think:** *"Amplitude / Mixpanel power — but with English queries."*

---

## Core Architecture

### Design Philosophy

> **English is ambiguous. SQL is not. CanonicalQuery is the bridge.**

The system never directly generates SQL from English. Instead, it uses a **Canonical Query Language (CQL)** that decouples:
- English parsing
- Business logic
- SQL dialect specifics

### Pipeline Flow

```
English Query
    ↓
QueryIntent (semantic intent extraction)
    ↓
CanonicalQuery (schema-agnostic representation)
    ↓
SqlQuery (intermediate SQL model)
    ↓
PostgreSQL SQL (dialect-specific)
    ↓
Execution + Results
```

---

## Data Pipeline

### Stage 1: English → QueryIntent

**Component:** `EnglishQueryParser`

Extracts semantic intent from natural language:
- **Entity detection**: EVENT vs USER
- **Time range parsing**: "last 7 days", "today", etc.
- **Metric detection**: "how many", "count"
- **Event name extraction**: "home", "button click", etc.

**Example:**
```java
Input: "What users clicked on the home button in the last 7 days?"
Output: QueryIntent {
    entity: EVENT,
    eventName: "Home Clicked",
    metric: COUNT,
    timeRange: LAST_7_DAYS
}
```

### Stage 2: QueryIntent → CanonicalQuery

**Component:** `CqlIntentBuilder`

The **brain** of the system. Applies semantic rules and business logic:

1. **Defaults entity to EVENT** (unless explicitly USER)
2. **Applies semantic mappings**:
   - "home button clicked" → `event_name = 'button_click'` AND `page_location = '/home'`
3. **Builds filter conditions**
4. **Sets metrics** (default: COUNT)
5. **Applies time range**

**Key Semantic Rule Example:**
```java
if (eventName.contains("home") && eventName.contains("click")) {
    filters.add(event_name = 'button_click');
    filters.add(page_location = '/home');
}
```

### Stage 3: CanonicalQuery → SqlQuery

**Component:** `SqlQueryBuilder`

Validates and builds intermediate SQL model:

1. **Entity-field compatibility validation**:
   - Prevents EVENT fields on USER tables
   - Throws `IllegalStateException` on invalid combinations

2. **Table resolution**:
   - `EntityTableResolver.resolve(EntityType.EVENT)` → `"public.canonical_events"`
   - `EntityTableResolver.resolve(EntityType.USER)` → `"public.canonical_users"`

3. **Applies translators**:
   - `MetricTranslator`: Converts metrics to SELECT clauses
   - `FilterTranslator`: Converts filters to WHERE conditions
   - `TimeRangeTranslator`: Converts time ranges to WHERE conditions
   - `GroupByTranslator`: Converts group by to GROUP BY clauses

### Stage 4: SqlQuery → PostgreSQL SQL

**Component:** `PostgresSqlGenerator`

Generates valid PostgreSQL SQL:
- Assembles SELECT, FROM, WHERE, GROUP BY, LIMIT clauses
- Handles SQL syntax specifics

**Example Output:**
```sql
SELECT COUNT(*) 
FROM public.canonical_events 
WHERE event_name = 'button_click' 
  AND page_location = '/home' 
  AND event_time >= now() - interval '7 days' 
LIMIT 100
```

### Stage 5: Execution

**Component:** `SqlQueryExecutor`

Executes SQL via Spring's `JdbcTemplate` and returns results as `List<Map<String, Object>>`.

---

## Key Components

### 1. QueryIntent (`analytics.query.intent.QueryIntent`)

**Purpose:** Represents what the user wants, not how to query it.

**Fields:**
- `EntityType entity`: EVENT or USER
- `String eventName`: Extracted event name (e.g., "Home Clicked")
- `MetricType metric`: COUNT, SUM, AVG
- `TimeRangeType timeRange`: TODAY, LAST_7_DAYS, LAST_30_DAYS, etc.

**Key Method:**
- `usesEventFields()`: Checks if intent uses event-specific fields

### 2. CqlIntentBuilder (`analytics.query.builder.CqlIntentBuilder`)

**Purpose:** The semantic intelligence layer. Maps English meaning → canonical filters.

**Responsibilities:**
- Defaults entity to EVENT
- Applies semantic rules (e.g., "home button" → button_click + /home)
- Builds filter conditions
- Sets default metrics (COUNT)
- Applies time ranges

**Key Semantic Rules:**
- "home" + "click" → `event_name='button_click'` AND `page_location='/home'`
- Fallback: exact event name matching

### 3. CanonicalQuery (`analytics.query.CanonicalQuery`)

**Purpose:** Pure data object representing schema-agnostic query.

**Fields:**
- `EntityType entity`: EVENT or USER
- `String schemaVersion`: Schema version (for future use)
- `TimeRange timeRange`: Time filtering
- `List<FilterCondition> filters`: Field filters
- `List<Metric> metrics`: Aggregation metrics
- `List<String> groupBy`: Grouping dimensions
- `Integer limit`: Result limit (default: 100)

**No SQL here** — purely canonical representation.

### 4. EntityTableResolver (`analytics.sql.resolver.EntityTableResolver`)

**Purpose:** Maps entity types to database table names.

**Mappings:**
- `EntityType.EVENT` → `"public.canonical_events"`
- `EntityType.USER` → `"public.canonical_users"`

**Prevents bugs** where EVENT fields are queried on USER tables.

### 5. SqlQueryBuilder (`analytics.sql.builder.SqlQueryBuilder`)

**Purpose:** Validates and builds intermediate SQL model.

**Validations:**
- Entity-field compatibility (EVENT fields on USER entity → exception)
- Metric correctness
- Illegal combination detection

**Builds:**
- FROM clause (via EntityTableResolver)
- SELECT clause (via MetricTranslator)
- WHERE clauses (via FilterTranslator + TimeRangeTranslator)
- GROUP BY clause (via GroupByTranslator)
- LIMIT clause

### 6. PostgresSqlGenerator (`analytics.sql.generator.PostgresSqlGenerator`)

**Purpose:** Converts SqlQuery → valid PostgreSQL SQL string.

**Responsibilities:**
- SQL syntax assembly
- Clause ordering (SELECT, FROM, WHERE, GROUP BY, LIMIT)
- String concatenation with proper spacing

**Critical Fix:** TimeRange filtering was missing initially, causing identical counts for all date ranges. Now fixed.

### 7. Translators

#### MetricTranslator
Converts `Metric` objects to SELECT clauses:
- `COUNT` → `COUNT(*)`
- `SUM` → `SUM(field)`
- `AVG` → `AVG(field)`

#### FilterTranslator
Converts `FilterCondition` objects to WHERE clauses:
- `EQUALS` → `field = 'value'`
- `NOT_EQUALS` → `field != 'value'`
- `CONTAINS` → `field LIKE '%value%'`
- `GREATER_THAN` → `field > 'value'`
- `LESS_THAN` → `field < 'value'`

#### TimeRangeTranslator
Converts `TimeRange` to WHERE conditions:
- `TODAY` → `event_time >= CURRENT_DATE`
- `LAST_7_DAYS` → `event_time >= now() - interval '7 days'`
- `LAST_30_DAYS` → `event_time >= now() - interval '30 days'`

**Note:** Uses `event_time` for filtering (client-side timestamp).

#### GroupByTranslator
Converts `groupBy` list to GROUP BY clause (for future use).

---

## Data Models

### Canonical Tables

#### `public.canonical_events`

Primary table for event analytics.

**Key Columns:**
- `event_name` (text): e.g., `button_click`, `page_view`
- `page_location` (text): e.g., `/home`, `/dashboard`
- `event_time` (timestamp): Client-side event timestamp
- `ingested_at` (timestamp): Server ingestion time
- `vendor` (text): Source vendor (Amplitude, Mixpanel, etc.)
- `schema_version` (text): Schema version
- `raw_payload` (jsonb): Original vendor payload

**Additional columns** mapped via `CanonicalFieldRegistry`:
- User identity: `user_id`, `anonymous_id`, `device_id`
- Device/OS: `os`, `os_version`, `device_model`, `device_brand`, `device_type`
- Browser/App: `browser`, `browser_version`, `app_version`, `platform`
- Location: `country`, `region`, `city`, `ip`, `language`
- Page context: `page_url`, `page_title`, `referrer`, `screen_name`
- Campaign: `utm_source`, `utm_medium`, `utm_campaign`, `utm_term`, `utm_content`

#### `public.canonical_users`

User-level attributes table (for future use).

### Enums

#### EntityType
- `EVENT`: Event-based queries
- `USER`: User-based queries

#### MetricType
- `COUNT`: Count of records
- `SUM`: Sum of numeric field
- `AVG`: Average of numeric field

#### TimeRangeType
- `TODAY`
- `YESTERDAY`
- `LAST_7_DAYS`
- `LAST_30_DAYS`
- `CUSTOM`
- `RELATIVE`

#### Operator
- `EQUALS`
- `NOT_EQUALS`
- `GREATER_THAN`
- `GREATER_THAN_EQUALS`
- `LESS_THAN`
- `LESS_THAN_EQUALS`
- `IN`
- `NOT_IN`
- `CONTAINS`

### Core Classes

#### FilterCondition
```java
{
    field: String,      // e.g., "event_name"
    operator: Operator, // e.g., EQUALS
    value: Object       // e.g., "button_click"
}
```

#### Metric
```java
{
    type: MetricType,   // e.g., COUNT
    field: String       // null for COUNT, required for SUM/AVG
}
```

#### TimeRange
```java
{
    type: TimeRangeType, // e.g., LAST_7_DAYS
    value: String        // Optional custom value
}
```

---

## Implementation Details

### API Endpoint

**Endpoint:** `POST /api/analytics/query`

**Request Body:**
```json
{
    "query": "What users clicked on the home button in the last 7 days?"
}
```

**Response:**
```json
[
    {
        "count": 1234
    }
]
```

**Flow:**
1. `AnalyticsQueryController.query()` receives request
2. `EnglishQueryParser.parse()` extracts intent
3. `CqlIntentBuilder.build()` creates CanonicalQuery
4. `SqlQueryBuilder.build()` creates SqlQuery
5. `PostgresSqlGenerator.generate()` creates SQL string
6. `SqlQueryExecutor.execute()` runs query
7. Returns results

### Time Range Logic

**Critical Fix:** Time filtering was missing, causing identical counts for all date ranges.

**Current Implementation:**
- Uses `event_time >= now() - interval 'X days'` for relative ranges
- Uses `event_time >= CURRENT_DATE` for TODAY
- Applied via `TimeRangeTranslator` in WHERE clause

**Note:** Consider using `ingested_at` for server-side filtering if needed.

### Semantic Mapping System

**Current Rules (in CqlIntentBuilder):**

1. **"home button clicked"**:
   ```java
   if (eventName.contains("home") && eventName.contains("click")) {
       filters.add(event_name = 'button_click');
       filters.add(page_location = '/home');
   }
   ```

2. **Fallback**: Exact event name matching

**Extensibility:** Add more rules in `CqlIntentBuilder.build()` method.

### Validation System

**Entity-Field Compatibility:**
- `SqlQueryBuilder` validates that EVENT fields are not used with USER entity
- Throws `IllegalStateException` on invalid combinations

**Example:**
```java
if (cql.getEntity() == EntityType.USER && hasEventFilters(cql)) {
    throw new IllegalStateException(
        "Invalid query: EVENT fields used with USER entity"
    );
}
```

### Canonical Field Registry

**Component:** `CanonicalFieldRegistry`

Maps canonical field names to vendor-specific field names:
- Supports multiple vendors (Amplitude, Mixpanel)
- Categorized by field type (EVENT_IDENTITY, USER_IDENTITY, DEVICE_OS, etc.)
- Version-aware (SchemaVersion.V1)

**Example:**
```java
CanonicalField("eventName", ...)
    → Mixpanel: "event"
    → Amplitude: "event_type"
```

---

## Current Status

### ✅ What Works

1. **English → Intent parsing**
   - Basic entity detection (EVENT/USER)
   - Time range extraction ("last 7 days", "today")
   - Metric detection ("how many", "count")
   - Event name extraction

2. **Semantic mapping**
   - "home button clicked" → `button_click` + `/home`
   - Fallback to exact matching

3. **Table resolution**
   - Correct mapping: EVENT → `canonical_events`
   - Entity validation

4. **Time range filtering**
   - Fixed: Now properly applies time filters
   - Supports TODAY, LAST_7_DAYS, LAST_30_DAYS

5. **SQL generation**
   - Valid PostgreSQL SQL
   - Proper WHERE clause assembly
   - LIMIT support

6. **API integration**
   - Postman API returns correct counts
   - SQL validated directly in pgAdmin

### ⚠️ Known Limitations

1. **English Parser**
   - Basic keyword matching (not NLP-based)
   - Limited time range patterns
   - No complex query parsing

2. **Semantic Rules**
   - Only one rule implemented ("home button")
   - No fuzzy matching
   - No synonym support

3. **Metrics**
   - Only COUNT fully supported
   - SUM/AVG defined but not tested
   - No DISTINCT support

4. **Group By**
   - Infrastructure exists but not used
   - No daily breakdowns
   - No dimension grouping

5. **Operators**
   - Some operators defined but not implemented (IN, NOT_IN, etc.)

6. **Time Range**
   - Uses `event_time` (client-side)
   - Consider `ingested_at` for server-side filtering
   - No custom date ranges

---

## Future Enhancements

### 1. Enhanced English Parsing

**Current:** Basic keyword matching  
**Future:** 
- NLP-based intent extraction
- Support for complex queries
- Better time range parsing ("last month", "this week")
- Multi-event queries

### 2. Expanded Semantic Rules

**Current:** One rule ("home button")  
**Future:**
- Rule-based system with configurable mappings
- Synonym support ("click" = "tap" = "press")
- Fuzzy matching for event names
- Pattern-based rules (regex support)

### 3. Advanced Metrics

**Current:** COUNT only  
**Future:**
- DISTINCT users: `COUNT(DISTINCT user_id)`
- SUM/AVG with field validation
- Percentiles, medians
- Custom aggregations

### 4. Group By & Breakdowns

**Current:** Infrastructure only  
**Future:**
- Daily breakdowns: `GROUP BY DATE(event_time)`
- Dimension grouping: `GROUP BY page_location`
- Multi-dimensional grouping
- Time-series aggregation

### 5. Query Intent Model Cleanup

**Current:** Basic structure  
**Future:**
- Remove commented code
- Add validation methods
- Better type safety
- Builder pattern

### 6. SQL Generator Robustness

**Current:** Basic SQL generation  
**Future:**
- SQL injection prevention (parameterized queries)
- Query optimization hints
- Support for JOINs (event + user tables)
- Subquery support

### 7. Testing

**Current:** Manual testing via Postman  
**Future:**
- Unit tests for CanonicalQuery → SQL
- Integration tests for full pipeline
- Test cases for edge cases
- Performance testing

### 8. Error Handling

**Current:** Basic exceptions  
**Future:**
- User-friendly error messages
- Query validation before execution
- Suggestions for invalid queries
- Logging and monitoring

### 9. Extensibility

**Current:** Hardcoded rules  
**Future:**
- Plugin system for semantic rules
- Configurable field mappings
- Vendor-agnostic query building
- Multi-database support (beyond PostgreSQL)

### 10. Performance

**Current:** Direct SQL execution  
**Future:**
- Query caching
- Result pagination
- Query timeout handling
- Connection pooling optimization

---

## What This Is NOT

**Important Boundaries:**

- ❌ **Not a chatbot**: No conversational interface
- ❌ **Not a BI dashboard**: No visualization layer
- ❌ **Not hardcoded SQL templates**: Dynamic query generation
- ❌ **Not tied to one analytics vendor**: Vendor-agnostic via canonical schema

**This is a query intelligence engine.**

---

## Technology Stack

- **Language:** Java 25
- **Framework:** Spring Boot 4.0.1
- **Database:** PostgreSQL
- **ORM:** Spring Data JPA
- **Build Tool:** Maven
- **Utilities:** Lombok

---

## Project Structure

```
src/main/java/com/example/BACKEND/
├── analytics/
│   ├── cannonical/          # Canonical event models
│   ├── dictionary/          # Field registry and mappings
│   ├── mapping/             # Vendor field mappings
│   ├── query/               # Query pipeline
│   │   ├── builder/         # CqlIntentBuilder
│   │   ├── enums/           # EntityType, MetricType, etc.
│   │   ├── intent/          # QueryIntent
│   │   └── parser/           # EnglishQueryParser
│   ├── sql/                 # SQL generation
│   │   ├── builder/         # SqlQueryBuilder
│   │   ├── executor/        # SqlQueryExecutor
│   │   ├── generator/       # PostgresSqlGenerator
│   │   ├── model/           # SqlQuery
│   │   ├── resolver/        # EntityTableResolver
│   │   └── translator/      # Filter, Metric, TimeRange translators
│   ├── translator/          # Event translation
│   ├── vendor/              # Vendor types
│   └── version/             # Schema versioning
├── controller/              # REST controllers
│   └── AnalyticsQueryController.java
└── BackendApplication.java  # Main application
```

---

## Conclusion

This natural-language analytics engine provides a robust foundation for converting English queries to SQL. The canonical query layer decouples intent from implementation, enabling extensibility and maintainability. Current implementation supports basic queries with semantic mapping, and the architecture is designed for future enhancements.

**Key Achievement:** Successfully bridges the semantic gap between English concepts and database schemas through a multi-stage pipeline with validation and intelligence layers.






