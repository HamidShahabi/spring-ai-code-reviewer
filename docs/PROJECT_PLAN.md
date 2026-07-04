# Project Plan — AI Code Reviewer

**Version:** 1.0  
**Author:** Engineering Team  
**Last updated:** June 2026  
**Status:** Active

---

## 1. Executive summary

This project delivers an AI-powered code review tool integrated into the company's GitLab workflow. The system analyses every Merge Request diff and posts actionable, severity-tagged findings as inline comments before a human reviewer even opens the MR.

The project follows a two-track delivery strategy: a working Python CI script was shipped as an MVP to prove value quickly, and a production-grade Spring AI microservice is the target architecture to be presented to the Java Chapter and CTO.

---

## 2. Goals and non-goals

### Goals
- Reduce the average back-and-forth review cycle time by catching common issues automatically
- Surface security, performance, and SOLID violations before human review
- Build a company-owned, customisable tool that reflects internal coding standards
- Demonstrate a pragmatic AI adoption path within the Java/Spring ecosystem

### Non-goals
- Replacing human code reviewers
- Auto-merging or auto-approving MRs
- Reviewing non-code assets (images, binaries, generated protobuf files)
- General-purpose chatbot or assistant functionality

---

## 3. Stakeholders

| Role | Name / Team | Interest |
|---|---|---|
| Project owner | Author | Delivery, quality, promotion case |
| Technical audience | Java Chapter | Architecture alignment, Spring AI adoption |
| Executive sponsor | CTO | ROI, security posture, AI strategy |
| End users | All developers | Faster, higher-quality reviews |

---

## 4. Delivery phases

### Phase 0 — MVP Python CI Script ✅ COMPLETE

**Duration:** 2 weeks (completed)  
**Goal:** Prove the concept. Ship something real that developers can see in their MRs.

**Deliverables:**
- Python script triggered by GitLab CI on every MR event
- Fetches diff via GitLab API, sends to LLM via OpenRouter
- Posts findings as inline comments with general comment fallback
- Filters generated files, lock files, and binary assets

**Outcome:** Working end-to-end flow on local GitLab instance. Demonstrated to team.

---

### Phase 1 — MVP Hardening ✅ COMPLETE

**Duration:** 2–3 weeks (completed June 2026)  
**Goal:** Make the MVP production-safe and demonstrably better for the CTO demo.

| # | Task | Status |
|---|---|---|
| 1.1 | Fix `verify=False` — add `REQUESTS_CA_BUNDLE` support | ✅ |
| 1.2 | Upgrade model to claude-haiku-4-5 (native Anthropic SDK) | ✅ |
| 1.3 | Parse real line numbers from diff `@@` headers | ✅ |
| 1.4 | Add deduplication — delete stale AI notes before posting | ✅ |
| 1.5 | Split diff by file and review each chunk separately | ✅ |
| 1.6 | Add retry decorator with exponential backoff | ✅ |
| 1.7 | Use JSON Schema structured output (`output_config`) | ✅ |
| 1.8 | Post review summary comment with severity table | ✅ |
| 1.9 | Add `MIN_SEVERITY` environment variable | ✅ |
| 1.10 | Hardening from PR #4 (network error handling, pagination, null guards) | ✅ |

**Outcome:** Script v1.2 deployed and running on `root/digicard@staging` in local GitLab.

---

### Phase 2 — Spring AI Microservice 🟢 CORE COMPLETE (pending comparison gate)

**Stack as built:** Spring Boot 3.5 + Spring AI 1.1.7 (not 4.0/2.0; API shape is compatible for future upgrade)  
**Goal:** Production-grade microservice architecture presented to the Java Chapter.

#### Sprint 1 — Foundation ✅ COMPLETE

- [x] Spring Boot 3.5 + Spring AI 1.1.7 project scaffold
- [x] GitLab webhook endpoint (`POST /api/v1/webhook`)
- [x] Webhook signature verification (X-Gitlab-Token header)
- [x] GitLabApiClient — diff, metadata, commit SHAs, file content, blob search
- [x] Application configuration via `application.yml` + env vars
- [x] `docker-compose.yml` for local GitLab + Postgres
- [x] **Event differentiation** — review only on `open`, `reopen`, or `update` with new commits (`oldrev`). Eliminates double-review from GitLab's trailing post-open `update`.

#### Sprint 2 — Core review engine ✅ COMPLETE

- [x] DiffChunker — per-file split with `@@` line-map extraction and extension filtering
- [x] Spring AI ChatClient wired with Anthropic provider (Haiku)
- [x] System prompt + user prompt templating
- [x] Structured output via `BeanOutputConverter` / `.entity(ReviewResponse.class)` with lenient JSON fallback
- [x] RulesEngine — severity filtering
- [x] CommentPublisher — inline-first, fallback general comment, deduplication

#### Sprint 3 — Data, resilience, context strategy ✅ COMPLETE

- [x] PostgreSQL schema: `mr_reviews` (with `prompt_tokens`, `completion_tokens`, `total_tokens`), `findings`
- [x] Spring Data JPA repositories
- [x] `@Retryable` on LLM calls (no retry on `IllegalStateException` parse failures)
- [x] Anthropic Claude provider; OpenAI-compatible config also wired
- [x] **Token usage auditing** — `extractUsage(ChatResponse)` → `LlmUsage`, persisted per review + `reviewer.llm.tokens.total` Micrometer counter
- [x] **Config-selected context strategy** (`CONTEXT_STRATEGY` env var):
  - `none` — diff only (baseline, default)
  - `injected` — prepends full file at `head_commit_sha` to each prompt (deterministic floor, SHA-pinned)
  - `agentic` — exposes `@Tool` methods to model (experimental; haiku does not drive tools reliably)
- [x] **A/B comparison infrastructure** — `digicard-mirror` project (id=3) on local GitLab, CI disabled, microservice webhook registered; `digicard` (id=2) remains untouched for Python script comparison

#### Sprint 4 — Operations and presentation 🔧 PENDING

- [x] Micrometer metrics (`reviewer.llm.tokens.total`, review count, latency)
- [x] Health check (`/actuator/health`)
- [ ] Java Chapter demo preparation (slides, live demo script)
- [ ] Kubernetes manifests
- [ ] Slack / Teams notification (optional)

**Merge gate (self-imposed):** Run none vs injected side-by-side on the same `digicard-mirror` MR and confirm injected finds-per-token beats baseline. Data decides the default strategy. (Task #10)

**Milestone:** Live demo to Java Chapter — microservice reviews a real MR under 60 seconds from webhook receipt. ✅ Verified locally (avg ~15–25s).

---

### Phase 3 — Advanced features 🔭 FUTURE

**Duration:** 8–12 weeks (post-promotion, team effort)  
**Goal:** Make the tool genuinely differentiated from commercial alternatives.

| Feature | Description | Value |
|---|---|---|
| RAG over codebase | Index company repos so the reviewer understands internal libraries and patterns | High |
| Custom rules DSL | Let teams define their own review rules without touching prompts | High |
| Web UI | Configuration portal: model selection, severity thresholds, per-repo settings | Medium |
| Analytics dashboard | Track finding trends, false positive rates, developer feedback | Medium |
| Auto-fix suggestions | Propose a corrected code snippet as a commit directly on the MR | High |
| Fine-tuning pipeline | Collect accepted/rejected reviews to fine-tune a private model | High |
| Auto-labelling | Automatically add GitLab labels based on finding categories | Low |

---

## 5. Timeline

```
June 2026       July 2026       August 2026     September 2026
│               │               │               │
├── Phase 1 ───►├── Phase 2 S1 ─┤
│   Hardening   │   Foundation  │
│               │               ├── Phase 2 S2 ─┤
│               │               │   Core engine │
│               │               │               ├── Phase 2 S3 ──►
│               │               │               │   Data & resil.
│               CTO Demo        Java Chapter    │
│                               Presentation    └── Phase 2 S4 ──►
│                                                   Ops & launch
```

---

## 6. Success metrics

| Metric | Target |
|---|---|
| Inline comment success rate | ≥ 80% of findings land as inline comments |
| Review latency (webhook to first comment) | ≤ 60 seconds |
| False positive rate (developer-reported) | ≤ 20% |
| High-severity findings caught per MR | ≥ 1 per 10 MRs that had real issues |
| Developer satisfaction (survey) | ≥ 4 / 5 |
| Pipeline re-run duplicate comments | 0 |

---

## 7. Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| AI model quality insufficient for Java code | Medium | High | Evaluate Qwen2.5-Coder and Claude Haiku side-by-side; use benchmark MRs |
| Corporate proxy blocks external AI API | Medium | High | Test `REQUESTS_CA_BUNDLE`; prepare Ollama self-hosted fallback |
| Large MRs exceed context window | High | Medium | File-level chunking already implemented; add token counting guard |
| GitLab webhook signature spoofing | Low | High | Verify `X-Gitlab-Token` on every request |
| Spring Boot 4 + Spring AI 2.0 API instability | Medium | Medium | Pin exact versions via BOM; monitor spring.io/blog for breaking changes |
| Spring Boot 3.5 EOL (June 30, 2026) | High | High | Target Spring Boot 4.0 from day one in Phase 2 |
| Developer pushback on AI comments | Low | Medium | Clearly label AI findings; make false-positive reporting easy |

---

## 8. Dependencies

- GitLab instance (self-hosted or cloud) with webhook support
- OpenRouter API key (Phase 1) or direct provider API keys (Phase 2)
- PostgreSQL 16+ (Phase 2)
- Java 21, Spring Boot 4.0, Spring AI 2.0 (Phase 2)
- Kubernetes cluster or Docker host (Phase 2 deployment)
- Corporate CA certificate bundle for SSL (Phase 1 fix)
