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

### Phase 1 — MVP Hardening 🔧 IN PROGRESS

**Duration:** 2–3 weeks  
**Goal:** Make the MVP production-safe and demonstrably better for the CTO demo.

**Deliverables and tasks:**

| # | Task | Priority | Effort |
|---|---|---|---|
| 1.1 | Fix `verify=False` — add `REQUESTS_CA_BUNDLE` support | Critical | 1h |
| 1.2 | Upgrade model to `google/gemini-2.0-flash-001` or `qwen/qwen2.5-coder-32b` | Critical | 1h |
| 1.3 | Parse real line numbers from diff `@@` headers (diff line map) | High | 4h |
| 1.4 | Add deduplication — delete stale AI notes before posting | High | 2h |
| 1.5 | Split diff by file and review each chunk separately | High | 4h |
| 1.6 | Add retry decorator with exponential backoff | Medium | 2h |
| 1.7 | Use JSON Schema structured output (`response_format`) | Medium | 3h |
| 1.8 | Post review summary comment with severity table | Medium | 2h |
| 1.9 | Add `MIN_SEVERITY` environment variable to filter findings | Low | 1h |
| 1.10 | Write unit tests for diff parser and severity filter | Low | 4h |

**Milestone:** CTO demo-ready Python script with inline comments landing correctly on changed lines.

---

### Phase 2 — Spring AI Microservice 📋 PLANNED

**Duration:** 6–8 weeks  
**Goal:** Production-grade microservice architecture presented to the Java Chapter.

#### Sprint 1 — Foundation (weeks 1–2)

- [ ] Spring Boot 4.0 + Spring AI 2.0 project scaffold
- [ ] GitLab webhook endpoint (`POST /api/v1/webhook`)
- [ ] Webhook signature verification (X-Gitlab-Token header)
- [ ] GitLabApiClient — fetch diff, metadata, and commit SHAs
- [ ] Application configuration via `application.yml` and environment variables
- [ ] Docker image and `docker-compose.yml` for local development

#### Sprint 2 — Core review engine (weeks 3–4)

- [ ] DiffChunker — per-file split with line map extraction
- [ ] Spring AI ChatClient wired with OpenAI provider
- [ ] System prompt and user prompt templating (`PromptTemplate`)
- [ ] Structured output via Spring AI's `BeanOutputConverter`
- [ ] RulesEngine — severity filtering, company policy rules
- [ ] CommentPublisher — inline + fallback general comment logic

#### Sprint 3 — Data, resilience, and providers (weeks 5–6)

- [ ] PostgreSQL schema: `mr_reviews`, `findings` tables
- [ ] Spring Data JPA repositories
- [ ] Review history — skip re-reviewing unchanged files
- [ ] `@Retryable` on LLM calls (Spring Retry)
- [ ] Anthropic Claude provider (ChatClient config profile)
- [ ] Ollama on-premise provider (ChatClient config profile)
- [ ] Multi-provider fallback chain

#### Sprint 4 — Operations and presentation (weeks 7–8)

- [ ] Micrometer metrics: review count, latency, error rate, inline success rate
- [ ] Health check endpoints (`/actuator/health`)
- [ ] Slack / Teams webhook notification (optional, toggle via config)
- [ ] Kubernetes manifest (`Deployment`, `Service`, `ConfigMap`, `Secret`)
- [ ] README, architecture doc, ADRs
- [ ] Java Chapter demo preparation

**Milestone:** Live demo to Java Chapter. Spring AI microservice reviewing a real MR in under 60 seconds from webhook receipt.

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
