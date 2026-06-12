# ADR-001 — Technology choice: Spring AI microservice as target architecture

**Date:** June 2026  
**Status:** Accepted  
**Authors:** Engineering Team

---

## Context

The project started as a Python CI script to deliver an MVP quickly. As the tool matures toward production, a technology choice must be made: continue building on Python, or migrate to a Spring AI microservice.

The development team primarily works in Java / Spring Boot. The project is intended to be demonstrated to the Java Chapter and the CTO as a long-term internal platform investment.

---

## Decision

We will build the production system as a **Spring Boot 4 + Spring AI 2.0 microservice**, triggered by a GitLab webhook. The Python script will continue running as the MVP in Phase 1 while the microservice is built in Phase 2.

---

## Options considered

### Option A — Expand the Python script

Keep and extend the Python script. Add persistence (SQLite or PostgreSQL via psycopg2), expose a simple Flask/FastAPI webhook endpoint, and containerise it.

**Pros:**
- Less migration effort
- Python ecosystem is strong for AI tooling (LangChain, LlamaIndex)
- Faster iteration on prompts and AI-specific logic

**Cons:**
- Does not align with team's primary language (Java)
- Hard to present to a Java Chapter as a credible long-term platform
- Spring AI's `ChatClient` abstraction (provider-swappability, on-premise Ollama) is not available natively
- Lacks Spring Boot observability stack (Micrometer, Actuator) which team already uses
- FastAPI webhook requires more boilerplate than Spring MVC for security, validation, and async handling

### Option B — Spring AI microservice (chosen)

Build a Spring Boot 4 + Spring AI 2.0 microservice. Use Spring AI's `ChatClient` for provider abstraction, Spring Data JPA for persistence, Spring Retry for resilience, and Micrometer for metrics.

**Pros:**
- Full alignment with team's Java/Spring expertise
- Spring AI `ChatClient` gives provider-agnostic LLM calls — swap OpenAI → Ollama in config
- First-class MCP support in Spring AI 2.0 (extensibility for agentic workflows)
- Spring Boot Actuator + Micrometer for production-grade observability out of the box
- Easier to onboard Java developers to contribute
- Strong story for CTO: same tech stack as production services, enterprise patterns, tested by the Spring community

**Cons:**
- More initial setup than Python
- Spring AI 2.0 still reaching GA at time of planning (risk managed by pinning BOM version)
- Jackson 3 and Spring Boot 4 migration adds some complexity

### Option C — Third-party SaaS (CodeRabbit, Sourcery, Amazon CodeGuru)

Use an existing commercial AI code review product.

**Pros:**
- No engineering effort
- Mature products with good UI

**Cons:**
- No customisation for internal libraries and coding standards
- Proprietary code sent to third-party servers (data privacy concern)
- Recurring cost; no ownership
- Does not serve the internal upskilling and promotion objectives of this project

---

## Consequences

- Phase 1 (Python) and Phase 2 (Spring AI) run in parallel for the duration of Phase 2 development
- Phase 2 must target Spring Boot 4.0 + Spring AI 2.0 from day one (Spring Boot 3.5 reaches EOL June 30, 2026)
- The Python script is considered a temporary asset and will be retired once Phase 2 is production-stable
- Team members unfamiliar with Spring AI should review the [Spring AI 2.0 reference documentation](https://docs.spring.io/spring-ai/reference/) before Sprint 1
