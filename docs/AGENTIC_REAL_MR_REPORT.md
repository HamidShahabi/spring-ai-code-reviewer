# Agentic Strategy — Real MR Test Report

**Date:** 2026-07-04
**Model:** `oss_120b` (gpt-oss-120b, internal vLLM, OpenAI-compatible endpoint)
**Strategy:** `agentic`
**MR under test:** `digicard-mirror!2` — "CARD-463 (dedup test)", `feat/CARD-463-client-metrics-status → staging`

---

## 1. Why this MR

Every prior agentic test (see `COMPARISON_REPORT.md` §9) used a small, single-file synthetic diff written specifically to require a tool call. This report tests the same fixed `agentic` strategy against a **real, pre-existing production MR** — no planted bugs, no crafted prompts, genuine code with genuine defensive patterns already in place.

The MR is a good stress test for agentic mode specifically because of its shape: it adds two new overridable hook methods (`resolveResult`, `additionalTags`) to a base class (`AbstractClientMetricHandler`), and one subclass (`CmsClientMetricHandler`) overrides both. Three *other* subclasses (`PurchaseClientMetricHandler`, `PurchaseAggregatorClientMetricHandler`, `UserClientMetricHandler`) have only their **test files** in the diff — their source is unchanged and therefore invisible to a diff-only reviewer. Verifying that the base-class contract change doesn't break those three requires either injecting all of them (expensive, and `injected` only ever fetches the file *currently* being reviewed, not its siblings) or fetching them on demand — the scenario agentic mode exists for.

7 files changed: `AbstractClientMetricHandler.java`, `CmsClientMetricHandler.java`, and 5 test files (one new: `CmsClientMetricHandlerTest.java`; four modified assertion updates).

---

## 2. Result summary

| Metric | Value |
|---|---|
| Files reviewed | 7 |
| Prompt tokens | 21,421 |
| Completion tokens | 7,410 |
| **Total tokens** | **28,831** |
| Duration | 61.5s |
| Findings | 5 (0 High, 3 Medium, 2 Low) |
| Tool calls made | 3 (of 6 budget) |
| Inline comments posted | 4 |
| Fell back to general comment | 1 (line outside diff hunk range) |

For comparison, the same MR was reviewed twice previously with `claude-haiku-4-5` + `injected` strategy: 14,053–15,477 prompt tokens, 9–13 findings. Different model, different strategy, different finding count — not a controlled comparison, included here only as prior-history context, not a verdict.

---

## 3. Tool-call trace

```
tool getFile     'digicard/src/test/java/com/digipay/digicard/metric/CmsClientMetricHandlerTest.java'
tool lookupSymbol 'com.digipay.digicard.service.cms.model.AbstractCmsServiceResponse'
tool getFile     'digicard/src/main/java/com/digipay/digicard/service/cms/model/AbstractCmsServiceResponse.java'
```

All 3 calls happened while reviewing `CmsClientMetricHandler.java` — the one file in the diff that both overrides the new base-class hooks *and* references a type (`AbstractCmsServiceResponse`) not shown anywhere in the diff. The other 6 files were reviewed with zero tool calls, consistent with the frugality behavior validated in `COMPARISON_REPORT.md` §9.4 — tools were used exactly where the diff was genuinely insufficient, not reflexively.

One call fetched `CmsClientMetricHandlerTest.java`, which technically *is* in the diff — but only as hunks. Fetching the full file let the model see the complete `count()` helper signature and the full `cmsResponse()` factory method, which the diff hunks alone didn't fully expose. Reasonable use, not wasted.

Budget: 3 of 6 calls used across the whole MR — comfortable headroom, no exhaustion.

---

## 4. Findings — verified against source

| # | File:Line | Severity | Verdict |
|---|---|---|---|
| 1 | `AbstractClientMetricHandler.java:83` | Medium | ✅ **Correct** |
| 2 | `CmsClientMetricHandler.java:51` | Medium | ✅ **Correct** (design judgment call) |
| 3 | `CmsClientMetricHandlerTest.java:42` | Low | ✅ **Correct** |
| 4 | `CmsClientMetricHandlerTest.java:87` | Medium | ❌ **False positive** |
| 5 | `CmsClientMetricHandlerTest.java:95` | Low | ⚠️ Debatable nitpick |

### #1 — `additionalTags(outputModel)` called with a potentially-null `outputModel` (✅ correct)

The base class's `handleException()` path calls `recordMetric(..., null)` — `outputModel` is `null` on the exception path. `recordMetric` unconditionally calls `additionalTags(outputModel)`. The base implementation is null-safe (`return List.of()`), but the finding correctly flags that **any subclass** overriding `additionalTags()` without a null guard will NPE on every failed call. Verified directly against the diff:

```java
public void handleException(InputModel inputModel, Exception ex) {
    ...
    recordMetric((ClientCallInput) inputModel, RESULT_FAILURE, exception, resolveResultStatus(ex), null);
}
private void recordMetric(..., OutputModel outputModel) {
    ...
    tags.addAll(additionalTags(outputModel));   // outputModel may be null here
}
```

This is exactly the kind of finding that benefits from full-file context — the null flows from `handleException()`, through `recordMetric()`, into the hook contract several lines away. Genuinely valuable.

### #2 — Unknown/missing CMS status silently treated as SUCCESS (✅ correct, judgment call)

```java
protected String resolveResult(OutputModel outputModel) {
    if (outputModel instanceof CmsClientOutput output && output.getStatus() != null) {
        return CMS_STATUS_SUCCESS.equals(output.getStatus()) ? RESULT_SUCCESS : RESULT_FAILURE;
    }
    return RESULT_SUCCESS;   // <- unknown/null status defaults to SUCCESS
}
```

Accurate reading of the code. Whether this is *actually* a bug depends on product intent (maybe a missing status genuinely means "assume success"), but flagging silent-success-on-unknown as a monitoring risk is a defensible, standard finding.

### #3 — Redundant duplicate assertion (✅ correct)

```java
assertThat(handler.name()).isEqualTo(CmsClientMetricHandler.HANDLER_NAME);
assertThat(handler.name()).isEqualTo("digicard.cms.client");
```

Both lines assert the same thing (the second restates the constant's value as a literal). Matches source exactly. Note: this is the same finding *pattern* independently flagged in the original `COMPARISON_REPORT.md` (§4) on a different MR — consistent signal, not a fluke.

### #4 — Micrometer tag-order fragility (❌ false positive)

The model claimed:

> "Micrometer treats the tag list as ordered, so if the production code registers the same tags in a different order the lookup will return `null`."

This is incorrect. Micrometer's `Tags` type is internally sorted and `MeterRegistry.find(name).tags(...)` matches by tag key/value set membership, not by argument order — `.tags("a", "1", "b", "2")` and `.tags("b", "2", "a", "1")` resolve to the same meter. Verified against the actual test helper:

```java
private double count(String method, String result, String exception, String resultStatus, String responseStatus) {
    Timer timer = meterRegistry.find(CmsClientMetricHandler.HANDLER_NAME)
        .tags("method", method, "result", result, "exception", exception, "result_status", resultStatus,
                "response_status", responseStatus)
        .timer();
    return timer == null ? 0 : timer.count();
}
```

This is a real, if narrow, false positive from a model that otherwise correctly investigated cross-file context. Worth being honest about — cross-file verification reduces guessing about *what a symbol is*, it doesn't make the model an authority on a third-party library's internals it never inspected. Notably, this is also the one finding that couldn't be inline-mapped (fell back to a general comment) — the line was outside the diff hunk's valid range.

### #5 — Anonymous subclass instead of a mock (⚠️ debatable)

```java
private AbstractCmsServiceResponse cmsResponse(String status) {
    AbstractCmsServiceResponse response = new AbstractCmsServiceResponse() {};
    response.setStatus(status);
    return response;
}
```

Suggesting `mock(AbstractCmsServiceResponse.class)` instead is a legitimate style preference, not a defect — plenty of teams prefer a real (even if minimal) object over a mock for a simple data holder. Reasonable nitpick, correctly scoped as Low.

---

## 5. Verdict

**3 of 5 findings are solid and worth a developer's attention** (#1, #2, #3); **1 is a legitimate but debatable style nitpick** (#5); **1 is a false positive** (#4) — a confident claim about a third-party library's behavior that the model never actually verified (it verified the call site, not Micrometer's `Tags` implementation itself).

This is a useful, honest data point on real code: agentic mode's cross-file verification measurably improved finding #1 (a genuine contract-safety issue spanning two files, unlikely to surface without seeing the full base class) — but it doesn't make every claim correct. The tool-calling mechanism worked exactly as designed (3 targeted calls, only on the file that needed them, no wasted budget) — the false positive is a reasoning error, not a plumbing failure, and is consistent with the known behavior of LLM-based review generally: verified-context reduces but does not eliminate confident-but-wrong claims about things outside the fetched context.

**Cost:** 28,831 tokens / 61.5s for a 7-file MR. Roughly double the token cost of the equivalent `injected` run would likely be (not directly measured here — `injected` fetches the *current* file only, so it would not have caught finding #1's cross-file case at all, or would have needed the reviewer to guess).
