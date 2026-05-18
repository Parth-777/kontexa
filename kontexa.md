# Kontexa — Agentic BI Platform Vision

## Overview

Kontexa is an AI-powered business intelligence platform that enables business analysts, sales analysts, product managers, and non-technical stakeholders to interact with enterprise data using natural language instead of relying on developers for SQL queries and reports.

The platform connects to cloud data warehouses such as:
- BigQuery
- Snowflake
- Redshift

Kontexa builds semantic catalogues for enterprise datasets and uses AI models to answer analytical questions grounded in company-approved business context.

---

# Current Product Vision

## Core Workflow

1. Connect to cloud data warehouse
2. Extract schemas/tables/metadata
3. Build semantic catalogues:
   - table meanings
   - column meanings
   - KPI definitions
   - business terminology
4. Tenant/company approves the catalogue
5. Users ask questions in natural language
6. AI generates insights based on:
   - approved catalogue
   - enterprise data
   - business context

---

# Current Progress

## Completed / In Progress

### Backend
- BigQuery connector implemented
- Snowflake connector nearly implemented
- Basic navigation flow completed
- Partial catalogue creation system implemented

### Upcoming Priorities
1. Agentic architecture
2. Frontend polish and UX refinement
3. Clean page layouts and formatting

---

# Strategic Positioning

Kontexa should NOT position itself as:

> “ChatGPT for SQL”

Instead, the positioning should focus on:

- reducing analytics bottlenecks
- democratizing data access
- accelerating business decision-making
- enabling self-serve analytics
- providing intelligent business insights

---

# Real Competitive Moat

The moat is NOT OpenAI APIs.

Anyone can call AI APIs.

The moat is:

## Semantic Enterprise Understanding

Kontexa understands:
- enterprise schemas
- KPI definitions
- business terminology
- relationships between tables
- organizational context
- approved business meanings

This becomes proprietary intelligence over time.

---

# Recommended System Architecture

## Controlled Analytical Workflow

```text
User Query
   ↓
Intent Parser
   ↓
Schema Context Retriever
   ↓
Query Planner
   ↓
SQL Generator
   ↓
SQL Validator
   ↓
Execution Layer
   ↓
Result Interpreter
   ↓
Visualization Layer
```

---

# Architecture Components

## 1. Intent Parser

Converts natural language into structured analytical intent:
- entities
- metrics
- filters
- dimensions
- time windows

---

## 2. Schema Context Retriever

Retrieves:
- relevant tables
- relevant columns
- KPI definitions
- approved business terminology

This acts as RAG for enterprise databases.

---

## 3. Query Planner

Responsible for:
- joins
- aggregations
- grouping
- cohort logic
- time-window analysis

---

## 4. SQL Generator

Generates SQL only after:
- intent understanding
- schema retrieval
- planning

---

## 5. SQL Validator

Critical trust layer.

Checks:
- hallucinated columns
- invalid joins
- dangerous queries
- inefficient scans
- permission violations

---

## 6. Result Interpreter

Transforms raw outputs into business insights.

Instead of:
```text
conversion_rate = 0.24
```

Return:
> “Users who watched crime content converted 24% higher than average.”

---

# Frontend Vision

Kontexa should look like a:

## Modern BI Workspace

NOT a ChatGPT clone.

Design inspiration:
- Notion
- Linear
- Tableau

---

# Suggested UI Layout

## Left Sidebar
- Data Sources
- Saved Queries
- Dashboards
- Team Spaces
- Query History

---

## Main Workspace
- NLP query input
- Charts
- Tables
- AI-generated insights
- SQL explanation toggle

---

## Right Panel
- Schema context
- AI reasoning
- Suggested follow-up questions
- Filters

---

# Critical UX Principle

## Transparency & Trust

Enterprise users must understand:
- which tables were used
- generated SQL
- KPI definitions
- reasoning path

Suggested feature:
## “Explain Why”

---

# Agentic Evolution Path

## Level 1 — NLP Interface

User asks:
> “Show revenue by month.”

System:
- generates SQL
- returns chart

Useful but commoditized.

---

## Level 2 — Analytical Copilot

System additionally:
- suggests trends
- proposes follow-up questions
- explains anomalies
- recommends deeper analysis

---

## Level 3 — Autonomous Analyst

System proactively:
- monitors KPIs
- detects anomalies
- investigates causes
- generates reports
- recommends actions

---

# High-Value Agentic Use Cases

## 1. Insight Discovery Agent

Automatically detects:
- unusual trends
- anomalies
- correlations
- KPI shifts

Example:
> “Conversion dropped 14% among Android users in Germany after app version 3.2 rollout.”

---

## 2. Root Cause Analysis Agent

User asks:
> “Why did retention drop?”

Agent investigates:
- cohorts
- channels
- regions
- versions
- engagement metrics

Then proposes likely causes.

---

## 3. Follow-Up Reasoning Agent

Instead of stopping after one answer:
- compares benchmarks
- identifies segments
- suggests next questions

Example:
> “Churn increased 8%.
> Most increase came from inactive users.
> Would you like cohort analysis?”

---

## 4. KPI Monitoring Agent

Continuously monitors:
- revenue
- DAU
- churn
- retention
- conversion

Then alerts:
- anomalies
- spikes
- drops

---

## 5. Report Generation Agent

Automatically generates:
- weekly reports
- sales reports
- product summaries
- growth analysis

---

## 6. Query Clarification Agent

Handles ambiguity.

Example:
> “Active users”

AI asks:
> “Do you mean:
> - users active in last 7 days
> - MAU
> - or users with purchases?”

---

## 7. Data Quality Agent

Detects:
- missing values
- broken pipelines
- abnormal spikes
- inconsistent metrics

---

## 8. Executive Summary Agent

Generates concise business summaries for leadership.

Example:
> “Q2 growth was driven primarily by returning customers while acquisition efficiency declined.”

---

## 9. Dashboard Builder Agent

User asks:
> “Create a retention dashboard.”

AI:
- creates queries
- selects charts
- arranges widgets
- adds filters

---

## 10. Forecasting Agent

Predicts:
- churn
- revenue
- demand
- retention trends

---

# Recommended Product Roadmap

## Phase 1 — Trustworthy NLP Analytics
Focus:
- accurate SQL
- semantic grounding
- trust
- transparency

---

## Phase 2 — AI Analytical Copilot
Focus:
- insight suggestions
- anomaly detection
- chart recommendations
- follow-up reasoning

---

## Phase 3 — Autonomous Intelligence
Focus:
- proactive alerts
- root cause analysis
- automated reporting
- KPI monitoring

---

# Core Product Principle

A chatbot answers questions.

An analyst investigates business problems.

Kontexa should evolve into an AI system that investigates, explains, monitors, and recommends — not merely translates English into SQL.
