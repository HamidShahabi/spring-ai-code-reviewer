# Technical Specification — AI Code Reviewer

**Version:** 1.0  
**Author:** Engineering Team  
**Last updated:** June 2026  
**Status:** Draft

---

## 1. Scope

This document specifies the functional and non-functional requirements, API contracts, data models, integration points, and configuration reference for the AI Code Reviewer system. It covers both the Phase 1 Python MVP and the Phase 2 Spring AI microservice.

---

## 2. System overview

The AI Code Reviewer analyses GitLab Merge Request diffs using a Large Language Model (LLM) and publishes structured review findings back to the MR as inline comments or a general summary comment. The system operates automatically on every MR create or update event.

---

## 3. Actors

| Actor | Type | Description |
|---|---|---|
| GitLab | External system | Source of MR events and target for comment publication |
| Developer | Human | Opens or updates a Merge Request; reads AI findings |
| Reviewer | Human | Human code reviewer who works alongside AI findings |
| LLM Provider | External system | OpenAI, Anthropic, or Ollama; produces review findings |
| Administrator | Human | Configures the system; manages API keys and rules |

---

## 4. Functional requirements

### 4.1 Trigger and intake

| ID | Requirement |
|---|---|
| FR-001 | The system MUST trigger a code review on every Merge Request `open` event in GitLab. |
| FR-002 | The system MUST trigger a code review on every subsequent `push` to an open Merge Request. |
| FR-003 | The system MUST skip review if the MR diff contains no reviewable files (all deleted, all ignored extensions). |
| FR-004 | The system MUST ignore files matching the configured `IGNORE_EXTENSIONS` list (`.lock`, `.pb.go`, `.svg`, `.png`, `.md`, `go.sum`, `go.mod`, `swagger.yaml`). |
| FR-005 | The system MUST ignore deleted files in the diff. |

### 4.2 Diff processing

| ID | Requirement |
|---|---|
| FR-010 | The system MUST fetch the full diff of the MR via the GitLab API. |
| FR-011 | The system MUST split the diff into per-file chunks before sending to the LLM. |
| FR-012 | The system MUST parse valid new-file line numbers from diff `@@` hunk headers and include them in the LLM prompt as a constraint. |
| FR-013 | The system MUST pass the MR title and description as context to the LLM alongside the diff. |

### 4.3 AI review

| ID | Requirement |
|---|---|
| FR-020 | The system MUST send each file chunk to the configured LLM with a system prompt instructing it to review for: security vulnerabilities, performance issues, SOLID principle violations, and bugs. |
| FR-021 | The system MUST request the LLM response in structured JSON format with fields: `file`, `line`, `severity`, `comment`. |
| FR-022 | The `severity` field MUST be one of: `High`, `Medium`, `Low`, `Nitpick`. |
| FR-023 | The system MUST retry failed LLM API calls up to 3 times with exponential backoff (base 2 seconds). |
| FR-024 | The system MUST discard LLM responses that cannot be parsed as valid JSON after all retry attempts. |

### 4.4 Comment publication

| ID | Requirement |
|---|---|
| FR-030 | The system MUST post a summary comment on the MR before posting inline findings. The summary MUST include a severity breakdown table and an overall verdict. |
| FR-031 | For each finding, the system MUST first attempt to post it as an inline comment on the specific file and line number via the GitLab Discussions API. |
| FR-032 | If an inline comment fails (wrong line, GitLab API error), the system MUST include the finding in a general fallback comment instead. |
| FR-033 | The system MUST delete all previous AI review comments on the MR before posting new ones (deduplication on pipeline re-run). |
| FR-034 | The system MUST filter findings below the configured `MIN_SEVERITY` threshold and not post them. |
| FR-035 | Inline comments MUST be formatted as Markdown with severity icon, severity label, and actionable suggestion. Code snippets SHOULD be included where applicable. |

### 4.5 Labels and metadata

| ID | Requirement |
|---|---|
| FR-040 | The system SHOULD add a GitLab label `ai-reviewed` to the MR after completing a review. |
| FR-041 | The system SHOULD add a GitLab label `ai-high-severity` to the MR if any `High` severity findings are posted. |

### 4.6 Storage (Phase 2)

| ID | Requirement |
|---|---|
| FR-050 | The system MUST persist each review session (MR ID, model used, timestamp, finding count by severity) in PostgreSQL. |
| FR-051 | The system MUST persist each individual finding (file, line, severity, comment text, was_posted_inline) in PostgreSQL. |
| FR-052 | The system SHOULD skip re-reviewing a file if its content hash has not changed since the last review session. |

---

## 5. Non-functional requirements

| ID | Category | Requirement |
|---|---|---|
| NFR-001 | Performance | The system MUST complete a review and post all comments within 120 seconds for an MR with up to 20 changed files. |
| NFR-002 | Performance | LLM API calls MUST have a per-request timeout of 120 seconds. |
| NFR-003 | Reliability | The system MUST tolerate transient LLM API failures via retry with backoff (see FR-023). |
| NFR-004 | Reliability | A failure to post one inline comment MUST NOT prevent posting other findings. |
| NFR-005 | Security | The system MUST verify the GitLab webhook signature (`X-Gitlab-Token`) on every incoming request (Phase 2). |
| NFR-006 | Security | The system MUST NOT log API keys, diff content, or LLM responses at INFO level or above. |
| NFR-007 | Security | SSL certificate verification MUST NOT be disabled (`verify=False` is prohibited). |
| NFR-008 | Maintainability | The LLM provider MUST be swappable via configuration change with no code change (Spring AI ChatClient). |
| NFR-009 | Observability | The system MUST expose review count, latency histogram, and error rate as Micrometer metrics (Phase 2). |
| NFR-010 | Portability | The system MUST be deployable as a Docker container. |
| NFR-011 | Scalability | The system MUST handle concurrent reviews of up to 10 simultaneous MRs without degradation. |

---

## 6. API specification

### 6.1 Inbound — GitLab Webhook (Phase 2)

**Endpoint:** `POST /api/v1/webhook`

**Headers:**

| Header | Required | Description |
|---|---|---|
| `X-Gitlab-Token` | Yes | Shared secret configured in GitLab webhook settings |
| `X-Gitlab-Event` | Yes | Must be `Merge Request Hook` |
| `Content-Type` | Yes | `application/json` |

**Request body (relevant fields):**

```json
{
  "object_kind": "merge_request",
  "project": {
    "id": 42,
    "web_url": "https://gitlab.example.com/team/repo"
  },
  "object_attributes": {
    "iid": 17,
    "title": "Add user authentication",
    "description": "Implements JWT-based auth",
    "state": "opened",
    "action": "open"
  }
}
```

**Accepted `action` values:** `open`, `update`  
**Ignored `action` values:** `close`, `merge`, `reopen`, `approved`, `unapproved`

**Response:**

| Status | Meaning |
|---|---|
| `202 Accepted` | Review queued successfully |
| `200 OK` | Event ignored (not an MR open/update) |
| `401 Unauthorized` | Invalid or missing `X-Gitlab-Token` |
| `400 Bad Request` | Malformed request body |

---

### 6.2 Outbound — GitLab API calls

All outbound calls use `PRIVATE-TOKEN` header authentication.

| Call | Method | Endpoint |
|---|---|---|
| Get MR metadata | GET | `/api/v4/projects/{id}/merge_requests/{iid}` |
| Get MR diff | GET | `/api/v4/projects/{id}/merge_requests/{iid}/changes` |
| Get commit SHAs | GET | `/api/v4/projects/{id}/merge_requests/{iid}/versions` |
| Post inline comment | POST | `/api/v4/projects/{id}/merge_requests/{iid}/discussions` |
| Post general comment | POST | `/api/v4/projects/{id}/merge_requests/{iid}/notes` |
| List existing notes | GET | `/api/v4/projects/{id}/merge_requests/{iid}/notes` |
| Delete note | DELETE | `/api/v4/projects/{id}/merge_requests/{iid}/notes/{note_id}` |
| Update MR labels | PUT | `/api/v4/projects/{id}/merge_requests/{iid}` |

---

### 6.3 LLM request / response contract

**Request (OpenAI-compatible):**

```json
{
  "model": "google/gemini-2.0-flash-001",
  "temperature": 0.1,
  "messages": [
    { "role": "system", "content": "<system_prompt>" },
    { "role": "user",   "content": "<mr_title + mr_description + diff_chunk>" }
  ],
  "response_format": {
    "type": "json_schema",
    "json_schema": {
      "name": "code_review_findings",
      "strict": true,
      "schema": {
        "type": "object",
        "properties": {
          "reviews": {
            "type": "array",
            "items": {
              "type": "object",
              "properties": {
                "file":     { "type": "string" },
                "line":     { "type": "integer" },
                "severity": { "type": "string", "enum": ["High", "Medium", "Low", "Nitpick"] },
                "comment":  { "type": "string" }
              },
              "required": ["file", "line", "severity", "comment"],
              "additionalProperties": false
            }
          }
        },
        "required": ["reviews"],
        "additionalProperties": false
      }
    }
  }
}
```

**Expected response shape:**

```json
{
  "reviews": [
    {
      "file": "src/main/java/com/example/AuthService.java",
      "line": 42,
      "severity": "High",
      "comment": "### SQL Injection risk\n\nThe query on this line concatenates user input directly...\n\n```java\n// Fix: use parameterized query\npreparedStatement.setString(1, userId);\n```"
    }
  ]
}
```

---

## 7. Data models (Phase 2)

### 7.1 `mr_reviews` table

| Column | Type | Description |
|---|---|---|
| `id` | `UUID` PK | Review session identifier |
| `project_id` | `BIGINT` | GitLab project ID |
| `mr_iid` | `BIGINT` | Merge Request internal ID |
| `model_used` | `VARCHAR(120)` | LLM model identifier |
| `reviewed_at` | `TIMESTAMPTZ` | When the review was completed |
| `files_reviewed` | `INT` | Number of files sent to LLM |
| `findings_high` | `INT` | Count of High severity findings |
| `findings_medium` | `INT` | Count of Medium severity findings |
| `findings_low` | `INT` | Count of Low severity findings |
| `findings_nitpick` | `INT` | Count of Nitpick findings |
| `duration_ms` | `BIGINT` | Total wall-clock time in milliseconds |

### 7.2 `findings` table

| Column | Type | Description |
|---|---|---|
| `id` | `UUID` PK | Finding identifier |
| `review_id` | `UUID` FK | References `mr_reviews.id` |
| `file_path` | `VARCHAR(512)` | File path as reported in the diff |
| `line_number` | `INT` | New-file line number |
| `severity` | `VARCHAR(16)` | `High`, `Medium`, `Low`, or `Nitpick` |
| `comment` | `TEXT` | Full Markdown review comment |
| `posted_inline` | `BOOLEAN` | Whether the comment was posted inline |
| `gitlab_note_id` | `BIGINT` | GitLab note ID if posted |

---

## 8. Configuration reference

### Phase 1 — Environment variables (Python script)

| Variable | Required | Default | Description |
|---|---|---|---|
| `CI_SERVER_URL` | Yes | `http://localhost` | GitLab instance URL |
| `CI_PROJECT_ID` | Yes | — | GitLab project ID |
| `CI_MERGE_REQUEST_IID` | Yes | — | MR internal ID (set by GitLab CI) |
| `GITLAB_TOKEN` | Yes | — | GitLab personal/project access token |
| `AI_API_KEY` | Yes | — | LLM provider API key |
| `AI_BASE_URL` | No | `https://openrouter.ai/api/v1/chat/completions` | LLM API endpoint |
| `AI_MODEL` | No | `google/gemini-2.0-flash-001` | Model identifier |
| `PROXY_URL` | No | `http://127.0.0.1:2080` | Corporate proxy URL |
| `REQUESTS_CA_BUNDLE` | Yes | System certs | Path to CA bundle or `true` |
| `MIN_SEVERITY` | No | `Low` | Minimum severity to post (`High`, `Medium`, `Low`, `Nitpick`) |

### Phase 2 — Spring AI application.yml

```yaml
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:5432/ai_reviewer
    username: ${DB_USER}
    password: ${DB_PASS}

  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      chat:
        options:
          model: ${AI_MODEL:gpt-4o-mini}
          temperature: 0.1

gitlab:
  url: ${GITLAB_URL:https://gitlab.example.com}
  token: ${GITLAB_TOKEN}
  webhook-secret: ${GITLAB_WEBHOOK_SECRET}

reviewer:
  min-severity: ${MIN_SEVERITY:Low}
  max-files-per-review: 30
  request-timeout-seconds: 120
  retry-max-attempts: 3
  retry-backoff-seconds: 2
  ignore-extensions:
    - .lock
    - go.sum
    - go.mod
    - .pb.go
    - swagger.yaml
    - .svg
    - .png
    - .md
```

---

## 9. Security considerations

- All GitLab API tokens MUST be stored as CI/CD variables or Kubernetes Secrets — never committed to source control.
- The webhook endpoint MUST validate the `X-Gitlab-Token` header before processing any payload.
- Diff content (which may contain proprietary code) MUST NOT be sent to a public LLM without explicit approval. Use on-premise Ollama for sensitive repositories.
- SSL verification MUST remain enabled. Corporate CA bundles should be provided via `REQUESTS_CA_BUNDLE`.
- LLM response content MUST be treated as untrusted input and sanitised before inclusion in GitLab comment bodies.
- Access to the PostgreSQL database MUST be restricted to the microservice's service account.

---

## 10. Coding conventions (Phase 2)

- Java 21, records for value objects, sealed interfaces for result types
- Spring AI `ChatClient.Builder` for all LLM interactions (no direct HTTP client calls)
- Immutable options classes — use builders, no setters
- All Spring components use constructor injection (no `@Autowired` on fields)
- Test coverage target: 80% for service and domain layer
- Integration tests use Testcontainers (PostgreSQL, WireMock for GitLab API)
