# MVP Specification — Python CI Script

**Version:** 1.1  
**Author:** Engineering Team  
**Last updated:** June 2026  
**Status:** Active (Phase 1 target)

---

## 1. Overview

The MVP is a single Python 3.11+ script (`reviewer.py`) triggered as a GitLab CI job on every Merge Request pipeline. It fetches the MR diff, sends it to a configurable LLM via OpenRouter, and posts findings back to the MR as inline comments or a general summary.

This document covers configuration, GitLab CI setup, local testing, known limitations, and the Phase 1 hardening changelog.

---

## 2. Prerequisites

- GitLab project with CI/CD enabled
- Python 3.11+ available on the runner
- OpenRouter API key (or direct API key for OpenAI, Anthropic, Gemini)
- GitLab personal or project access token with `api` scope
- Corporate CA certificate bundle (if behind a corporate proxy)

---

## 3. GitLab CI configuration

Add the following job to your `.gitlab-ci.yml`:

```yaml
ai-code-review:
  image: python:3.11-slim
  stage: test
  rules:
    - if: '$CI_PIPELINE_SOURCE == "merge_request_event"'
  before_script:
    - pip install requests urllib3 --quiet
  script:
    - python reviewer.py
  variables:
    AI_MODEL: "google/gemini-2.0-flash-001"
    MIN_SEVERITY: "Low"
    REQUESTS_CA_BUNDLE: "/etc/ssl/certs/ca-certificates.crt"
  allow_failure: true   # Do not block merge if reviewer fails
```

**Required CI/CD variables** (set in GitLab project settings, not in YAML):

| Variable | Masked | Description |
|---|---|---|
| `GITLAB_TOKEN` | Yes | Project access token (api scope) |
| `AI_API_KEY` | Yes | OpenRouter or provider API key |

**Automatically injected by GitLab CI:**

| Variable | Description |
|---|---|
| `CI_SERVER_URL` | GitLab instance URL |
| `CI_PROJECT_ID` | Numeric project ID |
| `CI_MERGE_REQUEST_IID` | MR internal ID |

---

## 4. Configuration reference

| Variable | Default | Description |
|---|---|---|
| `AI_MODEL` | `google/gemini-2.0-flash-001` | LLM model identifier |
| `AI_BASE_URL` | `https://openrouter.ai/api/v1/chat/completions` | API endpoint |
| `AI_API_KEY` | — | API key (required) |
| `PROXY_URL` | `http://127.0.0.1:2080` | HTTP/HTTPS proxy (if needed) |
| `REQUESTS_CA_BUNDLE` | System certs | CA bundle path or `true` |
| `MIN_SEVERITY` | `Low` | Minimum finding severity to post |
| `GITLAB_TOKEN` | — | GitLab access token (required) |
| `CI_SERVER_URL` | `http://localhost` | GitLab URL (injected by CI) |
| `CI_PROJECT_ID` | — | Project ID (injected by CI) |
| `CI_MERGE_REQUEST_IID` | — | MR IID (injected by CI) |

---

## 5. Ignored file types

The following file patterns are automatically excluded from review:

```python
IGNORE_EXTENSIONS = {
    '.lock', 'go.sum', 'go.mod', '.pb.go',
    'swagger.yaml', '.svg', '.png', '.md'
}
```

Deleted files are always excluded.

---

## 6. Comment format

### Summary comment (posted first)

```markdown
## 🤖 AI Code Review — `google/gemini-2.0-flash-001`

| Severity | Count |
|----------|-------|
| 🔴 High     | 2 |
| 🟠 Medium   | 5 |
| 🔵 Low      | 3 |
| ◻️ Nitpick  | 1 |

**Verdict:** 🔴 **Request Changes** — high severity issues require attention

*Inline comments follow below.*
```

### Inline comment format

```markdown
**🔴 AI Review [High]** · `src/AuthService.java:42`

### SQL Injection risk

The query concatenates user input directly into the SQL string.

**Fix:**
```java
// Use parameterised query instead
PreparedStatement stmt = conn.prepareStatement(
    "SELECT * FROM users WHERE id = ?"
);
stmt.setString(1, userId);
```
```

---

## 7. Local testing

To test the script locally without running a full pipeline:

```bash
export CI_SERVER_URL="http://your-gitlab.example.com"
export CI_PROJECT_ID="42"
export CI_MERGE_REQUEST_IID="17"
export GITLAB_TOKEN="glpat-xxxxxxxxxxxx"
export AI_API_KEY="sk-or-xxxxxxxxxxxx"
export AI_MODEL="google/gemini-2.0-flash-001"
export REQUESTS_CA_BUNDLE="/etc/ssl/certs/ca-certificates.crt"
export MIN_SEVERITY="Low"

python reviewer.py
```

---

## 8. Phase 1 hardening changelog

Changes applied (or to be applied) on top of the initial MVP:

| # | Change | Status |
|---|---|---|
| 1.1 | Fix `verify=False` — use `REQUESTS_CA_BUNDLE` | ✅ Done |
| 1.2 | Upgrade model default to `google/gemini-2.0-flash-001` | ✅ Done |
| 1.3 | Parse valid line numbers from `@@` diff headers | ✅ Done |
| 1.4 | Delete stale AI notes before posting (deduplication) | ✅ Done |
| 1.5 | Split diff by file, review each chunk independently | ✅ Done |
| 1.6 | Retry decorator with exponential backoff (3 attempts, base 2s) | ✅ Done |
| 1.7 | Structured JSON output via `response_format` JSON Schema | ✅ Done |
| 1.8 | Post summary comment with severity table | ✅ Done |
| 1.9 | `MIN_SEVERITY` environment variable | ✅ Done |
| 1.10 | Unit tests for `parse_valid_line_map` and `filter_by_severity` | 🔧 In progress |

---

## 9. Known limitations

| Limitation | Impact | Mitigation / Roadmap |
|---|---|---|
| Triggered only on pipeline run, not on MR open | Review delayed until first CI push | Resolved in Phase 2 (webhook-based) |
| No persistence — no history or trending | Cannot track improvement over time | Resolved in Phase 2 (PostgreSQL) |
| Single script — no separation of concerns | Hard to test individual components | Acceptable for MVP; refactored in Phase 2 |
| No configuration UI | Admin must set CI variables manually | Resolved in Phase 2 web UI (Phase 3) |
| LLM line numbers may still miss on complex hunks | Some findings fall back to general comment | Diff line map (item 1.3) reduces this significantly |
| No multi-provider fallback | If OpenRouter is down, review fails | Resolved in Phase 2 (Spring AI provider chain) |
| Token limit not enforced | Very large files may exceed context window | File chunking (item 1.5) reduces risk; full guard in Phase 2 |

---

## 10. File structure

```
ai-reviewer/
├── reviewer.py          # Main script (all logic in one file for MVP)
├── requirements.txt     # requests, urllib3
├── .gitlab-ci.yml       # CI job definition
└── tests/
    └── test_reviewer.py # Unit tests for pure functions
```

### `requirements.txt`

```
requests>=2.31.0
urllib3>=2.0.0
```
