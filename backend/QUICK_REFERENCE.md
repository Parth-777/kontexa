# Quick Reference Guide

## Pipeline Overview

```
English Query → QueryIntent → CanonicalQuery → SqlQuery → PostgreSQL SQL → Results
```

## Key Classes

| Class | Purpose | Location |
|-------|---------|----------|
| `EnglishQueryParser` | Parses English → QueryIntent | `analytics.query.parser` |
| `CqlIntentBuilder` | Builds CanonicalQuery from QueryIntent | `analytics.query.builder` |
| `SqlQueryBuilder` | Builds SqlQuery from CanonicalQuery | `analytics.sql.builder` |
| `PostgresSqlGenerator` | Generates SQL string | `analytics.sql.generator` |
| `SqlQueryExecutor` | Executes SQL | `analytics.sql.executor` |
| `EntityTableResolver` | Maps Entity → Table | `analytics.sql.resolver` |

## API Usage

### Endpoint
```
POST /api/analytics/query
```

### Request
```json
{
    "query": "What users clicked on the home button in the last 7 days?"
}
```

### Response
```json
[
    {
        "count": 1234
    }
]
```

## Supported Queries

### Time Ranges
- "last 7 days" → `LAST_7_DAYS`
- "today" → `TODAY`
- "last 30 days" → `LAST_30_DAYS` (if parsed)

### Metrics
- "how many" → `COUNT`
- "count" → `COUNT`

### Entities
- "user" → `USER` entity
- Default → `EVENT` entity

### Semantic Mappings
- "home button clicked" → `event_name='button_click'` AND `page_location='/home'`

## Database Tables

- `public.canonical_events` - Event data
- `public.canonical_users` - User data (future)

## Key Fields

### Event Table
- `event_name` - Event identifier
- `page_location` - Page path
- `event_time` - Client timestamp
- `ingested_at` - Server timestamp
- `user_id` - User identifier

## Enums

### EntityType
- `EVENT`
- `USER`

### MetricType
- `COUNT`
- `SUM`
- `AVG`

### TimeRangeType
- `TODAY`
- `YESTERDAY`
- `LAST_7_DAYS`
- `LAST_30_DAYS`
- `CUSTOM`
- `RELATIVE`

### Operator
- `EQUALS`
- `NOT_EQUALS`
- `CONTAINS`
- `GREATER_THAN`
- `LESS_THAN`
- `IN`
- `NOT_IN`

## Example SQL Output

```sql
SELECT COUNT(*) 
FROM public.canonical_events 
WHERE event_name = 'button_click' 
  AND page_location = '/home' 
  AND event_time >= now() - interval '7 days' 
LIMIT 100
```

## Adding New Semantic Rules

Edit `CqlIntentBuilder.build()`:

```java
if (normalized.contains("your_pattern")) {
    FilterCondition filter = new FilterCondition();
    filter.setField("field_name");
    filter.setOperator(Operator.EQUALS);
    filter.setValue("value");
    filters.add(filter);
}
```

## Validation Rules

- EVENT fields cannot be used with USER entity
- Metric type must be specified
- Entity type must be resolved to a table

## Common Issues

### Same count for all date ranges
- **Fixed:** TimeRange filtering now properly applied

### Invalid query errors
- Check entity-field compatibility
- Ensure filters match entity type

### SQL syntax errors
- Check PostgresSqlGenerator output
- Validate WHERE clause assembly






