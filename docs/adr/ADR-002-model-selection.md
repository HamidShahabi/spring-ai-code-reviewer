# ADR-002 — Model selection for code review

**Date:** June 2026  
**Status:** Accepted  
**Authors:** Engineering Team

---

## Context

The quality of the AI code review is directly determined by the model. The initial MVP used `meta-llama/llama-3-8b-instruct:free` (OpenRouter free tier), which produced low-quality findings: hallucinated line numbers, shallow security comments, and inconsistent JSON formatting.

A model must be selected for the Phase 1 hardened MVP and a default must be established for Phase 2. The selection criteria are:

- Code comprehension quality (Java, Go, Python)
- JSON output reliability and structured output support
- Context window size (large MRs can have long diffs)
- Cost per review
- On-premise availability (data privacy option)
- Latency (target: review complete in under 60 seconds)

---

## Decision

**Primary (cloud):** `google/gemini-2.0-flash-001`  
**Alternative (cloud):** `qwen/qwen2.5-coder-32b-instruct`  
**On-premise:** `qwen2.5-coder:32b` via Ollama

The model is configurable via the `AI_MODEL` environment variable. No code changes are required to switch providers.

---

## Options evaluated

### `meta-llama/llama-3-8b-instruct:free` (rejected — current MVP)

- Free tier via OpenRouter
- 8B parameters — insufficient reasoning depth for code review
- Line number hallucination rate: very high
- Inconsistent JSON even with structured prompting
- **Decision:** Discard immediately. Cost saving does not justify quality loss.

### `google/gemini-2.0-flash-001` (chosen as primary)

- Fast (typically < 30s for a full file review)
- 1M token context window — handles even very large diffs as a single call
- Strong structured output support (JSON Schema `response_format`)
- Excellent at Java, Go, and Python code
- Cost: approx $0.0375 per 1M input tokens (very low per-review cost)
- Available via OpenRouter and directly via Google AI

### `anthropic/claude-3-haiku` (chosen as alternative)

- Excellent JSON reliability — rarely deviates from schema
- Strong reasoning for security and SOLID violations
- Slightly slower than Gemini Flash for large contexts
- Higher cost than Gemini Flash
- Good fallback when Gemini is unavailable

### `qwen/qwen2.5-coder-32b-instruct` (chosen as on-premise option)

- Trained specifically on code — strong for less common patterns
- Available as `qwen2.5-coder:32b` via Ollama (self-hosted, no data leaves the network)
- Required for repositories with strict data residency requirements
- Latency higher on modest hardware; GPU recommended

### `openai/gpt-4o-mini` (not chosen as default)

- Good quality and low cost
- Familiar to the team
- Not chosen as default because Gemini Flash matches quality at lower cost and with larger context window
- Remains a valid alternative for OpenAI-first environments

### `deepseek/deepseek-r1` (considered, not default)

- Strong reasoning capability via chain-of-thought
- High latency due to reasoning trace
- Cost-effective for complex logic-heavy reviews
- Recommended for specific high-value security review scenarios rather than every MR

---

## Model comparison summary

| Model | Code quality | JSON reliability | Context | Latency | Cost |
|---|---|---|---|---|---|
| Llama 3 8B (old) | ❌ Poor | ❌ Poor | 8K | Fast | Free |
| Gemini Flash 2.0 | ✅ Very good | ✅ Very good | 1M | Fast | Very low |
| Claude Haiku | ✅ Very good | ✅ Excellent | 200K | Medium | Low |
| Qwen2.5-Coder 32B | ✅ Excellent | ✅ Good | 128K | Medium | Low/Free |
| GPT-4o mini | ✅ Good | ✅ Good | 128K | Fast | Low |
| DeepSeek R1 | ✅ Excellent | ✅ Good | 128K | Slow | Low |

---

## Consequences

- The `AI_MODEL` environment variable is the single point of control for model selection
- Spring AI's `ChatClient` abstraction means model selection is independent of application code
- A multi-provider fallback chain will be implemented in Phase 2: Gemini Flash → Claude Haiku → Ollama
- Teams with data residency requirements must set `spring.profiles.active=ollama` and provision an Ollama instance with `qwen2.5-coder:32b`
- Model performance should be re-evaluated quarterly as the model landscape evolves rapidly
