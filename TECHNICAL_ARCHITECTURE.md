# Technical Architecture - Natural Language Analytics Platform

## System Architecture Overview

### Component Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                         CLIENT LAYER                            │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  Next.js Frontend Application                             │  │
│  │  - Query Input Component                                  │  │
│  │  - Results Visualization (Charts/Tables)                  │  │
│  │  - Explanation Panel                                      │  │
│  │  - Query History                                          │  │
│  │  - Data Source Management                                 │  │
│  └──────────────────────────────────────────────────────────┘  │
└──────────────────────────────┬──────────────────────────────────┘
                               │
                               │ HTTPS/REST API
                               │
┌──────────────────────────────▼──────────────────────────────────┐
│                      API GATEWAY LAYER                          │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  Express.js / FastAPI Server                             │  │
│  │  - Authentication (JWT)                                   │  │
│  │  - Rate Limiting                                         │  │
│  │  - Request Validation                                     │  │
│  │  - Error Handling                                        │  │
│  └──────────────────────────────────────────────────────────┘  │
└──────────────────────────────┬──────────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────────┐
│                    PROCESSING PIPELINE                         │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  1. Natural Language Parser                              │  │
│  │     - LLM Integration (OpenAI/Anthropic)                 │  │
│  │     - Intent Extraction                                  │  │
│  │     - Output: Structured Intent JSON                      │  │
│  └──────────────────────────────────────────────────────────┘  │
│                               │                                 │
│                               ▼                                 │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  2. Intent Validator                                      │  │
│  │     - Ontology Validation                                │  │
│  │     - Ambiguity Detection                                │  │
│  │     - Clarification Question Generator                    │  │
│  │     - Output: Validated Intent                           │  │
│  └──────────────────────────────────────────────────────────┘  │
│                               │                                 │
│                               ▼                                 │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  3. Logical Plan Generator                               │  │
│  │     - Plan Schema Definition                            │  │
│  │     - Vendor-Agnostic Plan Creation                     │  │
│  │     - Plan Optimization                                 │  │
│  │     - Output: Logical Plan (YAML/JSON)                  │  │
│  └──────────────────────────────────────────────────────────┘  │
│                               │                                 │
│                               ▼                                 │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  4. Execution Adapter Router                             │  │
│  │     - Data Source Selection                             │  │
│  │     - Adapter Selection                                 │  │
│  │     - Plan → Adapter-Specific Query Translation         │  │
│  └──────────────────────────────────────────────────────────┘  │
│                               │                                 │
│          ┌────────────────────┼────────────────────┐           │
│          │                    │                    │           │
│          ▼                    ▼                    ▼           │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐    │
│  │  Amplitude   │    │   Mixpanel   │    │   PostHog    │    │
│  │   Adapter    │    │   Adapter    │    │   Adapter    │    │
│  └──────────────┘    └──────────────┘    └──────────────┘    │
│          │                    │                    │           │
│          └────────────────────┼────────────────────┘           │
│                               │                                │
│                               ▼                                │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  5. SQL Adapter (Warehouses)                             │  │
│  │     - SQL Query Generator                                │  │
│  │     - Connection Pooling                                 │  │
│  │     - Query Execution                                    │  │
│  └──────────────────────────────────────────────────────────┘  │
│                               │                                 │
│                               ▼                                 │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  6. Results Processor                                    │  │
│  │     - Data Formatting                                   │  │
│  │     - Explanation Generation                            │  │
│  │     - Assumption Extraction                             │  │
│  │     - Output: Structured Response                       │  │
│  └──────────────────────────────────────────────────────────┘  │
└──────────────────────────────┬──────────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────────┐
│                      DATA LAYER                                 │
│                                                                 │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────┐ │
│  │   PostgreSQL     │  │      Redis       │  │   Ontology   │ │
│  │                  │  │                  │  │   Files      │ │
│  │  - Users         │  │  - Query Cache   │  │  - YAML      │ │
│  │  - Data Sources  │  │  - LLM Cache    │  │  - JSON      │ │
│  │  - Query History │  │  - Session Store │  │              │ │
│  │  - Ontology      │  │                  │  │              │ │
│  └──────────────────┘  └──────────────────┘  └──────────────┘ │
└─────────────────────────────────────────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────────┐
│                  EXTERNAL DATA SOURCES                          │
│                                                                 │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐        │
│  │  Amplitude   │  │   Mixpanel   │  │   PostHog    │        │
│  │     API      │  │     API      │  │     API      │        │
│  └──────────────┘  └──────────────┘  └──────────────┘        │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  Data Warehouses                                          │  │
│  │  - Snowflake                                              │  │
│  │  - BigQuery                                               │  │
│  │  - ClickHouse                                             │  │
│  └──────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

---

## Data Flow

### Query Execution Flow

```
1. User Input
   "Who are the most active users by tenant?"
   │
   ▼
2. NL Parser (LLM)
   {
     "entity": "user",
     "metric": "activity",
     "aggregation": "ranked",
     "scope": "tenant"
   }
   │
   ▼
3. Intent Validator
   - Check ontology for "user", "activity", "tenant"
   - Validate metric definition
   - Output: Validated Intent
   │
   ▼
4. Plan Generator
   {
     "population": { "entity": "user" },
     "metric": { "type": "count", "source": "events" },
     "grouping": ["user_id", "tenant_id"],
     "ordering": [{"field": "metric", "direction": "desc"}],
     "limit": 10
   }
   │
   ▼
5. Adapter Selection
   - Determine data source (e.g., Amplitude)
   - Select Amplitude adapter
   │
   ▼
6. Query Translation
   Amplitude API Call:
   POST /2/cohorts
   {
     "event": "any",
     "groupBy": ["user_id", "tenant_id"],
     "orderBy": "count desc",
     "limit": 10
   }
   │
   ▼
7. Result Processing
   {
     "data": [...],
     "explanation": "Most active users calculated by event count...",
     "assumptions": [
       "Activity defined as total event count",
       "Time range: last 30 days",
       "Grouped by tenant"
     ]
   }
   │
   ▼
8. Response to Client
   - Chart visualization
   - Table data
   - Explanation text
   - Assumptions list
```

---

## Core Components

### 1. Natural Language Parser

**Technology**: LLM API (OpenAI GPT-4 / Anthropic Claude)

**Responsibilities**:
- Parse natural language questions
- Extract structured intent
- Handle variations in phrasing

**Input**:
```
"Who are the most active users at the tenant level?"
```

**Output**:
```json
{
  "entity": "user",
  "metric": "activity",
  "aggregation": "ranked",
  "scope": "tenant",
  "timeRange": { "relative": "30d" }
}
```

**Implementation**:
- Prompt engineering for consistent output
- JSON schema validation
- Error handling for unparseable queries

---

### 2. Semantic Ontology Engine

**Technology**: Custom TypeScript/Python service

**Responsibilities**:
- Define semantic concepts (Actor, Event, Feature, Journey)
- Map external schemas to canonical ontology
- Validate intent against ontology

**Structure**:
```yaml
# ontology.yaml
actors:
  - name: user
    identifiers: [user_id]
    attributes: [role, plan, created_at]

events:
  - name: run_query
    category: interaction
    actor: user
    properties:
      - name: query_type
        type: string

features:
  - name: query_execution
    related_events: [run_query, query_completed]
```

**Key Features**:
- Append-only (no overwrites)
- Version control
- Schema mapping (external → canonical)

---

### 3. Intent Validator

**Responsibilities**:
- Validate intent against ontology
- Detect ambiguities
- Generate clarification questions

**Validation Rules**:
- Entity must exist in ontology
- Metric must be defined
- Required properties must be available
- Time range must be valid

**Example Clarification**:
```
Intent: { "metric": "activity" }
Ambiguity: Activity can be "event_count" or "session_count"
Clarification: "Should activity be defined as event count or sessions?"
```

---

### 4. Logical Plan Generator

**Responsibilities**:
- Convert validated intent to vendor-agnostic plan
- Optimize plan structure
- Handle complex queries (journeys, funnels)

**Plan Schema**:
```yaml
Plan:
  population:
    entity: user
    filters:
      - active_in_last: 7d
  
  metric:
    type: count
    source: events
  
  grouping:
    - user_id
    - tenant_id
  
  ordering:
    - field: metric
      direction: desc
  
  limit: 10
```

**Plan Types**:
- Popularity queries
- Journey/path queries
- Funnel queries
- Segmentation queries

---

### 5. Execution Adapters

**Architecture**: Adapter Pattern

**Base Adapter Interface**:
```typescript
interface AnalyticsAdapter {
  execute(plan: LogicalPlan): Promise<QueryResult>;
  validateConnection(): Promise<boolean>;
  getAvailableSignals(): Promise<Signal[]>;
}
```

**Adapters**:

#### Amplitude Adapter
- **API**: REST API v2
- **Translation**: Plan → Amplitude cohort/event queries
- **Response**: Parse JSON response

#### Mixpanel Adapter
- **API**: JQL (JavaScript Query Language)
- **Translation**: Plan → JQL query
- **Response**: Parse JQL results

#### PostHog Adapter
- **API**: REST API
- **Translation**: Plan → PostHog insights queries
- **Response**: Parse JSON response

#### SQL Adapter
- **Targets**: Snowflake, BigQuery, ClickHouse
- **Translation**: Plan → SQL query
- **Features**: Connection pooling, query optimization

---

### 6. Results Processor

**Responsibilities**:
- Format raw results
- Generate natural language explanations
- Extract assumptions
- Select appropriate visualization type

**Output Format**:
```typescript
interface QueryResult {
  data: any[]; // Chart/table data
  explanation: string; // Natural language explanation
  assumptions: Assumption[]; // List of assumptions
  metadata: {
    executionTime: number;
    dataSource: string;
    queryType: string;
  };
}
```

---

## Technology Stack

### Frontend
- **Framework**: Next.js 14 (React 18)
- **Language**: TypeScript
- **Styling**: Tailwind CSS
- **UI Components**: shadcn/ui
- **Charts**: Recharts
- **State Management**: TanStack Query
- **Forms**: React Hook Form + Zod

### Backend
- **Runtime**: Node.js 20+ OR Python 3.11+
- **Framework**: Express.js (Node) OR FastAPI (Python)
- **Language**: TypeScript OR Python
- **LLM**: OpenAI SDK / Anthropic SDK
- **Database**: PostgreSQL 15+
- **Cache**: Redis 7+
- **Validation**: Zod (TypeScript) / Pydantic (Python)

### Infrastructure
- **Containerization**: Docker
- **Orchestration**: Docker Compose (dev) / Kubernetes (prod)
- **CI/CD**: GitHub Actions
- **Monitoring**: Sentry, DataDog
- **Logging**: Winston (Node) / Loguru (Python)

---

## Security Architecture

### Authentication & Authorization
```
User → JWT Token → API Gateway → Protected Routes
```

**JWT Claims**:
- User ID
- Email
- Role (user, admin)
- Data source access list

### Data Source Credentials
- **Storage**: Encrypted in PostgreSQL (JSONB)
- **Encryption**: AES-256
- **Access**: Decrypted only during query execution
- **Rotation**: Support for credential rotation

### API Security
- Rate limiting (per user, per endpoint)
- CORS configuration
- Input validation (Zod/Pydantic)
- SQL injection prevention (parameterized queries)

---

## Performance Optimization

### Caching Strategy

1. **LLM Response Cache**
   - Cache similar queries
   - Key: Normalized question + data source
   - TTL: 24 hours

2. **Query Result Cache**
   - Cache execution results
   - Key: Plan hash + data source
   - TTL: 1 hour (configurable)

3. **Ontology Cache**
   - Cache ontology definitions
   - In-memory (Redis)
   - TTL: 1 hour

### Query Optimization

1. **Plan Optimization**
   - Merge similar plans
   - Optimize grouping/ordering
   - Limit result sets

2. **Adapter Optimization**
   - Connection pooling
   - Batch queries where possible
   - Parallel execution for multiple data sources

---

## Error Handling

### Error Types

1. **Parse Errors**
   - Unparseable question
   - Ambiguous intent
   - Missing context

2. **Validation Errors**
   - Invalid entity
   - Missing signals
   - Invalid time range

3. **Execution Errors**
   - API failures
   - Timeout errors
   - Data source unavailable

### Error Response Format

```json
{
  "error": {
    "type": "validation_error",
    "message": "Entity 'feature_x' not found in ontology",
    "code": "ENTITY_NOT_FOUND",
    "suggestions": ["feature_y", "feature_z"]
  }
}
```

---

## Monitoring & Observability

### Metrics to Track

1. **Performance**
   - Query latency (p50, p95, p99)
   - LLM API latency
   - Adapter execution time

2. **Reliability**
   - Query success rate
   - Intent accuracy
   - Adapter success rate

3. **Usage**
   - Queries per user
   - Most common question types
   - Data source usage

### Logging

- Structured logging (JSON)
- Log levels: DEBUG, INFO, WARN, ERROR
- Correlation IDs for request tracing

---

## Deployment Architecture

### Development
```
Local Machine
├── Frontend (Next.js dev server)
├── Backend (Node.js/Python dev server)
├── PostgreSQL (Docker)
└── Redis (Docker)
```

### Production
```
Cloud Provider (AWS/GCP)
├── Frontend (Vercel / CloudFront + S3)
├── Backend API (ECS / Cloud Run)
├── PostgreSQL (RDS / Cloud SQL)
├── Redis (ElastiCache / Memorystore)
└── Monitoring (CloudWatch / Stackdriver)
```

---

## Scalability Considerations

### Horizontal Scaling
- Stateless API servers (scale horizontally)
- Database read replicas
- Redis cluster for caching

### Vertical Scaling
- LLM API rate limits (queue system)
- Database connection pooling
- Async task processing (Bull/Celery)

---

**Document Version**: 1.0  
**Last Updated**: 2026


