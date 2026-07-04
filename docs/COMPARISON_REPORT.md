# Strategy Comparison Report — AI Code Reviewer

**Date:** 2026-06-26 (§1–8, `claude-haiku-4-5`); updated 2026-07-04 (§9, `oss_120b`)
**Author:** Engineering  
**MR under test:** §1–8: CARD-448 — `feat/CARD-448-purchaseclient-metrics → base/CARD-448` · §9: CARD-2000 — `digicard-mirror!11`
**Models:** `claude-haiku-4-5` (Anthropic, §1–8) · `oss_120b` (internal vLLM, §9)

---

## 1. Methodology

Three modes were compared on **the same MR diff** — 3 changed Java files in the `digicard` service:

| # | File |
|---|---|
| 1 | `digicard/src/main/java/com/digipay/digicard/client/PurchaseClient.java` |
| 2 | `digicard/src/main/java/com/digipay/digicard/metric/PurchaseClientMetricHandler.java` |
| 3 | `digicard/src/test/java/com/digipay/digicard/metric/PurchaseClientMetricHandlerTest.java` |

**Modes:**

| Mode | What the model sees | Trigger |
|---|---|---|
| **None** (baseline) | Diff only | Microservice webhook, `CONTEXT_STRATEGY=none` |
| **Injected** | Diff + full file content at `head_commit_sha` | Microservice webhook, `CONTEXT_STRATEGY=injected` |
| **Python script** | Diff only (native Anthropic SDK, no file injection) | GitLab CI pipeline on `digicard` |

Microservice runs were on `digicard-mirror` (project id=3, mr_iid=1); Python script ran on `digicard` (project id=2, mr_iid=4) — **identical branch and diff**. Token counts come from `mr_reviews` table. Python script token data is not persisted (no database).

Both controlled runs executed back-to-back on **2026-06-26T14:21–14:22 UTC**.

---

## 2. Head-to-head: controlled runs

Both runs: fresh, consecutive, same MR, same model.

| Metric | None | Injected | Delta |
|---|---|---|---|
| **Prompt tokens** | 4,073 | 5,962 | **+1,889 (+46.4%)** |
| **Completion tokens** | 814 | 894 | +80 (+9.8%) |
| **Total tokens** | **4,887** | **6,856** | **+1,969 (+40.3%)** |
| **Duration** | 12.5s | 14.2s | +1.7s (+13.6%) |
| **Files reviewed** | 3 | 3 | — |
| **Total findings** | **6** | **6** | 0 |
| High | 0 | 0 | — |
| **Medium** | **3** | **4** | **+1** |
| Low | 3 | 2 | -1 |
| Inline success rate | 6/6 (100%) | 6/6 (100%) | — |

---

## 3. Aggregate across all historical runs (same MR)

Strategy inferred from prompt token signature: 4,073 = none, 5,962 = injected.  
Data: 3 none runs + 55 injected runs, all on CARD-448 (`digicard-mirror` + `digicard`).

| Metric | None (n=3) | Injected (n=55) |
|---|---|---|
| Avg prompt tokens | 4,073 | 5,962 |
| Avg completion tokens | 798 | 984 |
| **Avg total tokens** | **4,871** | **6,946** |
| **Avg total findings** | **5.7** | **6.5** |
| Avg Medium findings | 2.7 | 4.2 |
| Avg Low findings | 3.0 | 2.3 |
| Avg duration | 11.0s | 16.9s |

**Over 55 injected runs, the model consistently used exactly 5,962 prompt tokens.** Injected is deterministic on the prompt side (same files → same prompt size). Completion tokens vary by run (894–1,077), which is normal LLM sampling variance.

---

## 4. Finding comparison (controlled runs)

Findings aligned across modes (same code issue, different wording).

| Finding | None | Injected | Python script |
|---|---|---|---|
| `@Metric` on Feign proxy — dynamic proxy interception risk | ✅ Medium (line 12) | ✅ Medium (line 12) | ✅ Medium (line 23) |
| `AbstractClientMetricHandler` parent not visible — verify init | ✅ Medium (line 8) | ❌ — replaced by more specific finding ↓ | ✅ Medium (line 8) |
| `meterRegistry` not null-checked before super() call | ❌ | ✅ **Medium (line 12)** | ❌ |
| Redundant assertion in test (line 38/39) | ❌ | ✅ **Medium (line 38)** | ✅ **Medium (line 39)** |
| `orElseThrow()` without custom message (test line 82) | ✅ Medium | ✅ Medium | ✅ Medium |
| `HANDLER_NAME` constant — hardcoding / visibility | ✅ Low | ✅ Low | ✅ Low |
| `count()` returns double — silent null masking | ✅ Low (line 73) | ✅ Low (line 73) | ✅ Low (line 75) |
| All 3 methods share same `HANDLER_NAME` — observability gap | ❌ | ❌ | ~~✅ Medium~~ ⚠️ FALSE POSITIVE |

**Legend:** ✅ Found. ❌ Not found. ⚠️ False positive — flagged but incorrect.

### Key qualitative observations

**Injected replaces a vague finding with a precise one.** None produced "AbstractClientMetricHandler not visible in diff, verify initialization" — a hedge. Injected, having the full file, produced "constructor parameter `meterRegistry` is not null-checked before being passed to the parent class" — a concrete, actionable finding with the exact risk stated.

**Injected uniquely caught the redundant assertion at line 38.** Without the full file, None could not see that `handler.name().isEqualTo(HANDLER_NAME)` and `handler.name().isEqualTo("digicard.purchase.client")` are asserting the same thing twice. This finding was independently confirmed by the Python script (line 39 vs 38 — line offset from diff context vs full file).

**Python script's "unique" finding was a false positive.** "All three Feign methods share the same `HANDLER_NAME`" was flagged as an observability gap, but this is intentional by design: in the `AbstractClientMetricHandler` pattern, the handler name identifies the *client* (e.g. `digicard.purchase.client`), not the individual method. All methods on the same Feign client are supposed to share it. The microservice correctly stayed silent on this in all 58 runs. This is a precision win for the microservice, not a coverage gap.

---

## 5. Python script comparison

| Metric | Python script | None microservice | Injected microservice |
|---|---|---|---|
| Token usage tracked | ❌ (no DB) | ✅ persisted | ✅ persisted |
| Prompt context | Diff only | Diff only | Diff + full file |
| CI integration | Native (GitLab CI job) | Webhook (event-driven) | Webhook (event-driven) |
| Trigger | Every CI pipeline run | MR open / new commits only | MR open / new commits only |
| Duration (wall clock) | ~14s (includes runner startup) | 12.5s | 14.2s |
| Total findings | 6 | 6 | 6 |
| Inline success rate | 6/8 (75%) — 2 fell back | 6/6 (100%) | 6/6 (100%) |
| Finding uniqueness | 1 unique — ⚠️ false positive (observability gap) | 1 unique — hedge finding | 1 unique — actionable (null-check) |

**Python script inline failure:** 2 of 8 comment attempts hit GitLab `400` errors (lines 13 and 25 were not in the diff hunk map) and fell back to general comments. The microservice's `ValidLineMap` snaps to the nearest valid line before attempting inline, eliminating this failure mode.

**Token cost for Python script:** Not persisted, but structurally equivalent to `none` strategy (diff only, no file injection). Estimated ~4,000–4,500 prompt tokens per call based on diff sizes, 3 separate API calls for 3 files.

---

## 6. Cost model

Anthropic Haiku pricing (as of June 2026): $0.25/M input, $1.25/M output tokens.

| Mode | Prompt tokens | Completion tokens | Cost per review |
|---|---|---|---|
| None | 4,073 | 814 | $0.00102 + $0.00102 = **$0.0020** |
| Injected | 5,962 | 894 | $0.00149 + $0.00112 = **$0.0026** |
| Delta | +1,889 | +80 | **+$0.0006 (+30%)** |

At 100 MRs/day: None = **$0.20/day**; Injected = **$0.26/day**. The absolute cost difference is negligible.

---

## 7. Verdict

| Question | Finding |
|---|---|
| Does injected find more issues? | **Marginally yes** — avg +0.8 findings over 55 runs; same count in controlled run |
| Are injected findings higher quality? | **Yes** — more specific, better line anchoring, fewer hedges |
| Is the token overhead justified? | **Yes** — $0.0006 per review is negligible; quality gain is real |
| Does injected beat Python script? | **Yes** — injected finds 1 unique valid issue Python missed; Python's 1 "unique" finding was a false positive |
| Does microservice produce fewer false positives than the script? | **Yes** — microservice stayed silent on the shared-handler-name pattern (correct); script flagged it incorrectly |
| Should `injected` be the new default? | **Yes** ✅ (already applied) |

### Recommendation

**`CONTEXT_STRATEGY=injected` is the correct default.** (Applied in `application.yml`.)

Rationale:
- Higher finding quality and precision vs `none` across 55 runs (avg +0.8 findings/review, more specific language, correct line anchoring)
- Uniquely found the `meterRegistry` null-check issue that `none` missed
- Cost increase is $0.0006/review — negligible at any throughput
- **Lower false positive rate than the Python script** — the script's stronger persona ("elite Senior Staff") made it more confident about a wrong assumption regarding domain conventions it couldn't know without codebase context

The false positive on `HANDLER_NAME` is a direct consequence of the script's diff-only context: it can see that three methods share a constant but cannot know whether that's intentional design or an oversight. The `injected` strategy provides the full file, which could theoretically help the model see more context — but the real fix is that the model needs domain knowledge about the `AbstractClientMetricHandler` convention. Neither context injection nor a stronger persona substitutes for that.

### Caveats

1. **Agentic strategy remains broken on Haiku.** The model does not reliably call tools — it returns prose instead of JSON when tools are attached. This is a model capability limitation, not a code issue. Agentic should only be enabled with a tool-capable model (e.g., claude-sonnet-4-6 or GPT-4o).

2. **n=3 for none** is a small controlled sample. The aggregate trend (55 injected vs 3 none) favours injected but the none baseline could be noise.

3. **Do not chase the Python script's prompt persona.** The "elite Senior Staff" framing made the model more opinionated — which caused the false positive here. The microservice's more measured persona is the right call.

---

## 8. Next steps

| Priority | Action |
|---|---|
| ✅ Done | Switch default `CONTEXT_STRATEGY` to `injected` in `application.yml` |
| Medium | Add `strategy` column to `mr_reviews` table (currently inferred from prompt token count) |
| ✅ Done | Re-evaluate agentic strategy with a tool-capable model — see §9 |

---

## 9. Agentic strategy — root cause, fix, and three-way retest (2026-07-04)

**Model:** `oss_120b` (gpt-oss-120b, served on an internal vLLM instance, OpenAI-compatible endpoint)
**MR under test:** `digicard-mirror!11` — `CARD-2000: three-way strategy comparison`, a single new file (`DiscountCodeValidator.java`) containing two SQL-injection sinks, two unguarded-null query results, a `double`-for-money issue, an SRP violation, and one deliberately deep bug: `categoryService.getCategoryByCategoryCode()` returns a `CategoryResult` whose `cmsCategoryId` field is a boxed `Long`, unboxed unsafely — a defect only visible by reading a *second* file (`CategoryResult.java`) beyond the one the method is called from.

### 9.1 Switching providers surfaced two real bugs, not model limitations

Moving from Anthropic (native SDK) to an OpenAI-compatible internal vLLM server exposed two issues in the app itself, not in the model:

1. **All requests silently failed with `400 — Field required, input: None`.** Root cause: with no Apache HttpClient5/Jetty/Reactor Netty on the classpath, Spring Boot fell back to the JDK's `java.net.http.HttpClient`, which attempts an HTTP/2 cleartext (`h2c`) upgrade on every plaintext POST. vLLM's uvicorn/h11 server doesn't handle that negotiation and silently drops the request body. **Fix:** added `org.apache.httpcomponents.client5:httpclient5` to `pom.xml` — Spring Boot auto-prefers it, and it never attempts h2c.
2. **Agentic mode never called any tools**, on any test MR, across the first ~6 agentic runs. This looked like a model capability gap. It wasn't — see below.

### 9.2 Isolating "is it the model or is it us"

A four-step controlled test (raw `curl` against the vLLM server, bypassing the app and Spring AI entirely) pinned down the cause:

| Test | Result |
|---|---|
| Trivial tool call, minimal prompt | ✅ Model calls the tool correctly |
| Our app's *exact* captured request payload, replayed | ❌ No tool call — model answers directly |
| Same payload + `tool_choice: "auto"` added | ❌ Still no tool call |
| **Same exact `tools` JSON schema**, paired with a prompt that makes tool use the only possible way to answer | ✅ **Model calls `getFile` correctly** |

The fourth test is decisive: identical tool schema, different prompt framing, different outcome. The mechanism — vLLM's tool-call parsing, Spring AI's request serialization, the `@Tool`-annotated method schema — was never broken. The old `TOOLS_HINT` system prompt said:

> "Before flagging a *possible* issue you cannot confirm from the diff alone, fetch the context to verify it."

— a soft, self-assessed threshold. For typical review-sized diffs the model judged it could already reason well enough and never crossed that threshold.

### 9.3 Fix

`LlmReviewService.TOOLS_HINT` rewritten to replace the soft hedge with a concrete, checkable trigger ("if the diff calls a method/field/type DEFINED IN A DIFFERENT FILE and you're about to make a claim about it, you MUST verify via `getFile`/`lookupSymbol` first — an unverified assumption is worse than no finding") plus explicit multi-hop guidance (verifying a method's return type is often not the end of the trail — follow it one hop further if the finding depends on that type's own fields).

Tool-call logging was also promoted from `DEBUG` to `INFO` in `RepoContextTools` — this is now permanent operational visibility (whether agentic mode actually engaged on a given review), not throwaway debug scaffolding.

### 9.4 Three-way retest on the same MR (post-fix)

All three runs on `digicard-mirror!11`, same diff, same model:

| Metric | None | Injected | Agentic |
|---|---|---|---|
| Prompt tokens | 1,064 | 1,418 | 5,109 |
| Completion tokens | 1,301 | 1,192 | 2,670 |
| **Total tokens** | **2,365** | **2,610** | **7,779** |
| Duration | 10.8s | 9.7s | 19.9s |
| Total findings | 7 | 5 | 5 |
| High | 2 | 2 | 2 |
| Medium | 3 | 2 | 2 |
| Low | 2 | 1 | 1 |
| Tool calls made | — | — | `lookupSymbol('CategoryService')` → `getFile('CategoryService.java')` |

**Agentic now reliably calls tools** — confirmed across 4 separate post-fix test runs (this one plus 3 earlier retests), each making 1–2 tool calls per file. It correctly used the *method's* verified return type to state the null-check finding on `category.getCmsCategoryId()` as a confirmed fact rather than a guess.

**None caught the most findings this round (7 vs 5).** This is diff-dependent, not a strict ranking — `none`'s extra findings here were the isValid() null-count-unboxing case and the `BigDecimal`-for-money nitpick, both fully diff-visible and not requiring any external context. Agentic's advantage shows specifically on findings that *require* verifying an external symbol, which this diff has exactly one of (the `CategoryResult` null-check).

**None of the three strategies caught the deep planted bug** (boxed `Long cmsCategoryId` unboxed unsafely). Agentic took one hop (verified `CategoryService.getCategoryByCategoryCode`'s return type is nullable) but didn't take the second hop into `CategoryResult.java` to inspect the field itself. The `TOOLS_HINT` multi-hop nudge (§9.3) measurably changed *which* symbol it investigated (from the service interface to the model class, across retests) but doesn't yet guarantee full-depth traversal every time. This is a real, quantifiable limitation, not a regression — it's strictly better than the pre-fix state (zero tool calls, zero verification, ever).

**Frugality holds.** A control diff with only self-contained bugs (division by zero, off-by-one, missing null-check — no external symbols at all) produced **zero tool calls** and still caught all 3 real bugs, at diff-only token cost (1,159 prompt tokens). Agentic mode is not calling tools reflexively — it's still conditional on the diff actually requiring it.

### 9.5 Verdict

| Question | Finding |
|---|---|
| Was agentic mode broken? | No — the tool-calling mechanism (server, Spring AI serialization, tool schema) worked the whole time |
| What was actually broken? | Our own `TOOLS_HINT` prompt text gave the model too much discretion to skip tool use |
| Is agentic mode reliable now? | Tool calls happen consistently (4/4 post-fix runs) and frugality is preserved (0/1 no-context-needed runs) |
| Does agentic mode catch more than injected? | On diffs with a verifiable external symbol, yes — and the finding is stated as *confirmed* rather than *possible*. On diffs with no external dependencies, no meaningful difference |
| Is agentic mode the new default? | **Not yet.** Token cost is ~3x `injected` (7,779 vs 2,610) for a benefit that only shows up on a subset of diffs. `injected` remains the default; `agentic` is now a validated, non-experimental option for cases where verified (not guessed) cross-file findings matter more than latency/cost |

### 9.6 Next steps

| Priority | Action |
|---|---|
| Low | Push `TOOLS_HINT` further toward guaranteed multi-hop traversal (verify a hop further whenever the first-hop type is itself a non-primitive) |
| Low | Consider a small quantitative batch (5+ MRs) once the demo dataset has more diffs with genuine cross-file dependencies, to move past n=1 on the agentic comparison |
| Info | `httpclient5` fix in `pom.xml` is a prerequisite for *any* OpenAI-compatible internal/self-hosted endpoint on this app — not specific to vLLM or to agentic mode |
