# ADR-003 — Comment strategy: inline-first with general comment fallback

**Date:** June 2026  
**Status:** Accepted  
**Authors:** Engineering Team

---

## Context

GitLab supports two types of review comments on a Merge Request:

1. **Inline comments** (Discussions API) — anchored to a specific file and line number in the diff. Appear in the Changes tab directly alongside the code. Create a thread that can be resolved.
2. **General comments** (Notes API) — appear in the Overview tab. Not anchored to a specific line.

Inline comments are significantly more useful for developers because they provide context exactly where the issue is. However, GitLab's inline comment API is strict: the line number must exactly match a changed line in the diff as identified by the `base_sha`, `start_sha`, and `head_sha` commit hashes. If the line number is wrong, the API returns a non-201 response and the comment is rejected.

In the initial MVP, the LLM was asked to guess line numbers from the diff text. The success rate was low — most comments fell back to the general comment, producing a wall of text at the bottom of the MR rather than inline annotations.

---

## Decision

Use an **inline-first strategy with graceful fallback to a general comment**, combined with proactive line number constraining.

Specifically:
1. Parse the actual valid new-file line numbers from the diff `@@` hunk headers before sending to the LLM.
2. Include the valid line ranges in the system prompt as a hard constraint.
3. Attempt to post each finding as an inline comment.
4. If the inline attempt fails, collect the finding in a fallback list.
5. Post all fallback findings as a single formatted general comment.
6. Always post a summary comment first, regardless of inline success rate.

---

## Options considered

### Option A — General comments only

Post all findings as a single formatted Markdown block in the general Notes section.

**Pros:**
- Never fails due to line number issues
- Simple implementation

**Cons:**
- Developer must cross-reference the comment against the diff manually
- Context is lost — a general comment about line 147 of `AuthService.java` requires mental mapping
- Not how professional review tools work (CodeRabbit, GitHub Copilot review all post inline)
- CTO demo is significantly less impressive

### Option B — Inline only, skip on failure

Attempt inline only. If the inline API call fails, skip the finding entirely.

**Pros:**
- No cluttered fallback comment

**Cons:**
- Silently drops findings — developers may miss real issues
- Wrong line number guesses mean low success rate → many findings dropped

### Option C — Inline-first with fallback (chosen)

Attempt inline for every finding. On failure, add to a fallback list posted as a general comment.

**Pros:**
- No finding is ever silently dropped
- Best-case: all comments are inline (ideal developer experience)
- Worst-case: findings appear in a formatted general comment (still actionable)
- Combines reliability with quality

**Cons:**
- Slightly more complex implementation
- Fallback comment can be long for MRs with many issues on unchanged lines

### Option D — Inline with nearest-valid-line snapping

Before falling back to a general comment, snap the AI's line number to the nearest valid line in the `ValidLineMap`.

**Pros:**
- Increases inline success rate beyond what constraint-prompting alone achieves
- Reduces fallback comment length

**Cons:**
- Snapped line may not be the most relevant line for the finding
- Developer might be confused why a comment about one function appears on a different line

**Decision on Option D:** Implement as an optional step before fallback in Phase 2. Use snapping only when the nearest valid line is within ±5 lines of the AI's suggestion, to avoid confusing displacement.

---

## Implementation detail — diff line map parsing

```python
import re

def parse_valid_line_map(diff_text: str) -> dict[str, list[int]]:
    """
    Parse @@ -old_start,old_count +new_start,new_count @@ headers.
    Returns a map of file_path → list of valid new-file line numbers.
    These are the ONLY line numbers GitLab accepts for inline comments.
    """
    line_map = {}
    current_file = None

    for line in diff_text.split('\n'):
        if line.startswith('### File:'):
            current_file = line.replace('### File:', '').strip()
            line_map[current_file] = []
        elif line.startswith('@@') and current_file:
            match = re.search(r'\+(\d+)(?:,(\d+))?', line)
            if match:
                start = int(match.group(1))
                count = int(match.group(2) or 1)
                line_map[current_file].extend(range(start, start + count))

    return line_map
```

The valid line ranges are included in the LLM system prompt:

```
CRITICAL: Only use line numbers from the valid ranges below.
Any line outside these ranges will cause the comment to be rejected.

Valid line numbers:
  - src/AuthService.java: lines 38–67, 102–118
  - src/UserController.java: lines 12–45
```

---

## Consequences

- The `ValidLineMap` is computed from every diff before sending to the LLM
- The system prompt includes the valid line constraint for every file review call
- The `CommentPublisher` has two code paths: inline (`POST /discussions`) and general (`POST /notes`)
- The fallback comment uses a clearly marked header so developers can distinguish it from human review comments
- Inline success rate is tracked as a metric (`reviewer.comment.inline.success` vs `reviewer.comment.inline.fallback`) to monitor prompt effectiveness
- If the inline success rate drops below 50%, the team will revisit the prompting strategy
