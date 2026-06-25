"""
AI Code Reviewer — GitLab CI Merge Request reviewer.

Fetches an MR diff, sends each changed file to an LLM for review, and posts
findings back as inline comments (with a general-comment fallback).

Triggered as a GitLab CI job on merge_request_event pipelines.
"""

import os
import re
import json
import time
import logging
from functools import wraps
from typing import List, Dict, Optional

import anthropic
import requests

# ==========================================
# 1. Logging
# ==========================================
logging.basicConfig(level=logging.INFO, format="%(asctime)s - %(levelname)s - %(message)s")
logger = logging.getLogger("AICodeReviewer")

# ==========================================
# 2. Configuration
# ==========================================
GITLAB_URL = os.getenv("CI_SERVER_URL", "http://localhost")
PROJECT_ID = os.getenv("CI_PROJECT_ID")
MR_IID = os.getenv("CI_MERGE_REQUEST_IID")
GITLAB_TOKEN = os.getenv("GITLAB_TOKEN")

# LLM — native Anthropic (Claude) via the official SDK. The SDK reads
# ANTHROPIC_API_KEY from the environment; AI_API_KEY is accepted as an alias so
# existing CI variable names keep working.
AI_API_KEY = os.getenv("ANTHROPIC_API_KEY") or os.getenv("AI_API_KEY")
AI_MODEL = os.getenv("AI_MODEL", "claude-haiku-4-5")  # fast + cheap; claude-sonnet-4-6 for higher quality
AI_MAX_TOKENS = int(os.getenv("AI_MAX_TOKENS", "8000"))

# SSL: a path to a CA bundle, or the literal "true"/"false" to toggle system-cert
# verification. Never disable verification in production. Defaults to system certs.
_ca = (os.getenv("REQUESTS_CA_BUNDLE") or "").strip()
if _ca.lower() in ("", "true", "1", "yes"):
    CA_BUNDLE: object = True
elif _ca.lower() in ("false", "0", "no"):
    CA_BUNDLE = False
else:
    CA_BUNDLE = _ca  # treat as a filesystem path to a CA bundle

# Only post findings at or above this severity.
MIN_SEVERITY = os.getenv("MIN_SEVERITY", "Low")

REQUEST_TIMEOUT = int(os.getenv("REQUEST_TIMEOUT", "120"))
RETRY_MAX_ATTEMPTS = int(os.getenv("RETRY_MAX_ATTEMPTS", "3"))
RETRY_BACKOFF_BASE = float(os.getenv("RETRY_BACKOFF_BASE", "2"))

IGNORE_EXTENSIONS = {".lock", "go.sum", "go.mod", ".pb.go", "swagger.yaml", ".svg", ".png", ".md"}

SEVERITY_RANK = {"High": 0, "Medium": 1, "Low": 2, "Nitpick": 3}
SEVERITY_ICON = {"High": "🔴", "Medium": "🟠", "Low": "🔵", "Nitpick": "◻️"}

# Marker used to identify (and clean up) comments this bot has posted.
AI_MARKER = "🤖 AI Code Review"

# Maximum distance to snap an AI-guessed line to the nearest valid line.
SNAP_TOLERANCE = 5


# ==========================================
# 3. Resilience helpers
# ==========================================
def with_retry(max_attempts: int = RETRY_MAX_ATTEMPTS, backoff: float = RETRY_BACKOFF_BASE):
    """Retry a function on transient failures with exponential backoff."""
    def decorator(fn):
        @wraps(fn)
        def wrapper(*args, **kwargs):
            for attempt in range(max_attempts):
                try:
                    return fn(*args, **kwargs)
                except (requests.RequestException, KeyError, ValueError) as exc:
                    if attempt == max_attempts - 1:
                        logger.error("All %d attempts failed: %s", max_attempts, exc)
                        raise
                    wait = backoff ** attempt
                    logger.warning("Attempt %d failed (%s). Retrying in %.0fs...", attempt + 1, exc, wait)
                    time.sleep(wait)
        return wrapper
    return decorator


# ==========================================
# 4. GitLab API handlers
# ==========================================
def get_headers() -> Dict[str, str]:
    return {"PRIVATE-TOKEN": GITLAB_TOKEN}


def _mr_base() -> str:
    return f"{GITLAB_URL}/api/v4/projects/{PROJECT_ID}/merge_requests/{MR_IID}"


@with_retry()
def get_mr_metadata() -> Dict:
    res = requests.get(_mr_base(), headers=get_headers(), verify=CA_BUNDLE, timeout=REQUEST_TIMEOUT)
    res.raise_for_status()
    return res.json()


@with_retry()
def get_mr_diff() -> str:
    res = requests.get(f"{_mr_base()}/changes", headers=get_headers(), verify=CA_BUNDLE, timeout=REQUEST_TIMEOUT)
    res.raise_for_status()

    changes = res.json().get("changes", [])
    diff_text = ""
    for c in changes:
        new_path = c.get("new_path", "")
        diff_content = c.get("diff")
        # Skip deletions, binary/empty diffs, and ignored file types.
        if c.get("deleted_file") or not diff_content or any(new_path.endswith(ext) for ext in IGNORE_EXTENSIONS):
            continue
        diff_text += f"\n### File: {new_path}\n```diff\n{diff_content}\n```\n"
    return diff_text


def get_mr_shas() -> Optional[Dict]:
    """Commit hashes required for inline (line-anchored) comments."""
    try:
        res = requests.get(f"{_mr_base()}/versions", headers=get_headers(), verify=CA_BUNDLE, timeout=REQUEST_TIMEOUT)
        res.raise_for_status()
    except requests.RequestException as exc:
        logger.warning("Could not fetch MR versions: %s", exc)
        return None

    versions = res.json()
    if not versions:
        return None
    latest = versions[0]
    shas = {
        "base_sha": latest.get("base_commit_sha"),
        "start_sha": latest.get("start_commit_sha"),
        "head_sha": latest.get("head_commit_sha"),
    }
    # A version row can exist with null SHAs; sending those to GitLab produces
    # bogus inline failures, so treat any missing SHA as "no inline support".
    if not all(shas.values()):
        logger.warning("MR version is missing commit SHAs; inline comments disabled.")
        return None
    return shas


def post_simple_comment(text: str) -> None:
    try:
        res = requests.post(f"{_mr_base()}/notes", headers=get_headers(), data={"body": text},
                            verify=CA_BUNDLE, timeout=REQUEST_TIMEOUT)
        if res.status_code not in (200, 201):
            logger.warning("⚠️  Failed to post comment (%s): %s", res.status_code, res.text[:200])
    except requests.RequestException as exc:
        logger.error("❌ Failed to post comment: %s", exc)


def add_label_to_mr(label_name: str) -> None:
    try:
        res = requests.put(_mr_base(), headers=get_headers(), data={"add_labels": label_name},
                           verify=CA_BUNDLE, timeout=REQUEST_TIMEOUT)
        if res.status_code != 200:
            logger.warning("⚠️  Failed to add label '%s' to MR (%s)", label_name, res.status_code)
    except requests.RequestException as exc:
        logger.warning("⚠️  Failed to add label '%s' to MR: %s", label_name, exc)


def delete_existing_ai_reviews() -> None:
    """Remove previous AI review notes so pipeline re-runs don't duplicate comments."""
    notes_url = f"{_mr_base()}/notes"
    page = 1
    while True:
        try:
            res = requests.get(notes_url, headers=get_headers(),
                               params={"per_page": 100, "page": page},
                               verify=CA_BUNDLE, timeout=REQUEST_TIMEOUT)
            res.raise_for_status()
        except requests.RequestException as exc:
            logger.warning("Could not list existing notes for cleanup: %s", exc)
            return

        for note in res.json():
            body = note.get("body", "")
            if AI_MARKER in body or "AI Review [" in body:
                try:
                    del_res = requests.delete(f"{notes_url}/{note['id']}", headers=get_headers(),
                                              verify=CA_BUNDLE, timeout=REQUEST_TIMEOUT)
                    if del_res.status_code == 204:
                        logger.info("🗑️  Removed stale AI note %s", note["id"])
                    else:
                        logger.warning("⚠️  Failed to delete note %s: %s", note["id"], del_res.status_code)
                except requests.RequestException as exc:
                    logger.warning("⚠️  Failed to delete note %s: %s", note["id"], exc)

        # GitLab paginates notes; walk every page so large MRs are fully cleaned.
        next_page = res.headers.get("X-Next-Page")
        if not next_page:
            break
        page = int(next_page)


# ==========================================
# 5. Diff parsing
# ==========================================
def parse_valid_line_map(diff_text: str) -> Dict[str, List[int]]:
    """
    Parse `@@ -old +new,count @@` hunk headers to find the new-file line numbers
    GitLab will accept for inline comments. Returns {file_path: [valid_lines]}.
    """
    line_map: Dict[str, List[int]] = {}
    current_file: Optional[str] = None

    for line in diff_text.split("\n"):
        if line.startswith("### File:"):
            current_file = line.replace("### File:", "").strip()
            line_map[current_file] = []
        elif line.startswith("@@") and current_file:
            match = re.search(r"\+(\d+)(?:,(\d+))?", line)
            if match:
                start = int(match.group(1))
                count = int(match.group(2) or 1)
                line_map[current_file].extend(range(start, start + count))

    return line_map


def split_diff_by_file(diff_text: str) -> Dict[str, str]:
    """Split the combined diff string into per-file chunks."""
    chunks: Dict[str, str] = {}
    current_file: Optional[str] = None
    current_lines: List[str] = []

    for line in diff_text.split("\n"):
        if line.startswith("### File:"):
            if current_file and current_lines:
                chunks[current_file] = "\n".join(current_lines)
            current_file = line.replace("### File:", "").strip()
            current_lines = [line]
        elif current_file:
            current_lines.append(line)

    if current_file and current_lines:
        chunks[current_file] = "\n".join(current_lines)

    return chunks


def format_line_ranges(valid_lines: List[int]) -> str:
    """Compress valid line numbers into compact ranges (e.g. '12-18, 40, 55-60').

    `valid_lines` can be disjoint across hunks; a single min–max range would
    invite the model to pick a line in the gap that GitLab then rejects.
    """
    if not valid_lines:
        return ""
    lines = sorted(set(valid_lines))
    ranges: List[tuple] = []
    start = prev = lines[0]
    for n in lines[1:]:
        if n == prev + 1:
            prev = n
            continue
        ranges.append((start, prev))
        start = prev = n
    ranges.append((start, prev))
    return ", ".join(str(a) if a == b else f"{a}-{b}" for a, b in ranges)


def snap_to_valid_line(line: int, valid_lines: List[int]) -> Optional[int]:
    """Snap an AI-guessed line to the nearest valid line within SNAP_TOLERANCE."""
    if not valid_lines:
        return None
    if line in valid_lines:
        return line
    nearest = min(valid_lines, key=lambda v: abs(v - line))
    return nearest if abs(nearest - line) <= SNAP_TOLERANCE else None


# ==========================================
# 6. AI review
# ==========================================
SYSTEM_PROMPT_TEMPLATE = """You are an elite Senior Staff Software Engineer reviewing a GitLab Merge Request.
Review the code strictly for: Security, Performance, SOLID principles, and Bugs.
Do not comment on style trivia unless it causes a real problem.

CRITICAL INSTRUCTIONS:
- Respond ONLY with valid JSON matching the requested schema. No prose, no Markdown fences.
- "line" MUST be one of the valid line numbers listed below. Lines outside these ranges are REJECTED by GitLab.
- "comment" is Markdown with an actionable suggestion. Include a short corrected code snippet when helpful.

VALID LINE NUMBERS (use ONLY these):
{line_hint}
"""

# JSON Schema for Anthropic structured outputs (output_config.format). Guarantees
# the model returns an object matching this shape — no prose, no Markdown fences.
REVIEW_SCHEMA = {
    "type": "object",
    "properties": {
        "reviews": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "file": {"type": "string"},
                    "line": {"type": "integer"},
                    "severity": {"type": "string", "enum": ["High", "Medium", "Low", "Nitpick"]},
                    "comment": {"type": "string"},
                },
                "required": ["file", "line", "severity", "comment"],
                "additionalProperties": False,
            },
        }
    },
    "required": ["reviews"],
    "additionalProperties": False,
}

_ai_client: Optional[anthropic.Anthropic] = None


def _get_ai_client() -> anthropic.Anthropic:
    """Lazily construct the Anthropic client so import never requires a key."""
    global _ai_client
    if _ai_client is None:
        _ai_client = anthropic.Anthropic(api_key=AI_API_KEY, timeout=REQUEST_TIMEOUT)
    return _ai_client


def _extract_json(content: str) -> dict:
    """Tolerant parse: handles raw JSON or ```json fenced blocks."""
    cleaned = content.strip()
    if cleaned.startswith("```"):
        cleaned = re.sub(r"^```[a-zA-Z]*\n?", "", cleaned)
        cleaned = re.sub(r"\n?```$", "", cleaned).strip()
    return json.loads(cleaned)


def _call_ai(system_prompt: str, user_prompt: str, use_schema: bool = True) -> dict:
    """Call Claude (Messages API) and return the parsed JSON object.

    The Anthropic SDK retries 429/5xx and connection errors automatically.
    """
    kwargs = {
        "model": AI_MODEL,
        "max_tokens": AI_MAX_TOKENS,
        "temperature": 0.1,
        "system": system_prompt,
        "messages": [{"role": "user", "content": user_prompt}],
    }
    if use_schema:
        kwargs["output_config"] = {"format": {"type": "json_schema", "schema": REVIEW_SCHEMA}}

    response = _get_ai_client().messages.create(**kwargs)
    text = "".join(block.text for block in response.content if block.type == "text")
    return _extract_json(text)


def review_file(mr_title: str, mr_desc: str, file_path: str, file_diff: str,
                valid_lines: List[int]) -> List[Dict]:
    """Send a single file's diff to the LLM and return its findings."""
    if not valid_lines:
        logger.info("  Skipping %s — no reviewable hunks.", file_path)
        return []

    line_hint = f"  - {file_path}: lines {format_line_ranges(valid_lines)}"
    system_prompt = SYSTEM_PROMPT_TEMPLATE.format(line_hint=line_hint)
    user_prompt = f"MR Title: {mr_title}\nMR Description: {mr_desc}\n\nCode Changes:\n{file_diff}"

    try:
        parsed = _call_ai(system_prompt, user_prompt, use_schema=True)
    except (anthropic.APIError, ValueError) as exc:
        # Structured-output call failed; fall back to a plain JSON request once.
        logger.warning("Structured review failed for %s (%s). Retrying without schema.", file_path, exc)
        try:
            parsed = _call_ai(system_prompt, user_prompt, use_schema=False)
        except (anthropic.APIError, ValueError) as exc2:
            logger.error("Review failed for %s: %s", file_path, exc2)
            return []

    # The no-schema fallback can return a bare JSON array instead of {"reviews": [...]}.
    if isinstance(parsed, list):
        return parsed
    if isinstance(parsed, dict):
        return parsed.get("reviews", [])
    return []


def review_all_files(mr_title: str, mr_desc: str, diff_text: str,
                     line_map: Dict[str, List[int]]) -> List[Dict]:
    """Review each changed file independently and aggregate findings."""
    all_reviews: List[Dict] = []
    chunks = split_diff_by_file(diff_text)

    for file_path, file_diff in chunks.items():
        logger.info("  Reviewing %s ...", file_path)
        reviews = review_file(mr_title, mr_desc, file_path, file_diff, line_map.get(file_path, []))
        # Trust the chunk's file path over whatever the model echoed back.
        for r in reviews:
            r["file"] = file_path
        all_reviews.extend(reviews)
        time.sleep(0.5)  # gentle on rate limits

    return all_reviews


# ==========================================
# 7. Severity filtering
# ==========================================
def filter_by_severity(reviews: List[Dict]) -> List[Dict]:
    threshold = SEVERITY_RANK.get(MIN_SEVERITY, 2)
    return [r for r in reviews if SEVERITY_RANK.get(r.get("severity", "Low"), 2) <= threshold]


# ==========================================
# 8. Comment publication
# ==========================================
def post_review_summary(reviews: List[Dict]) -> None:
    counts = {"High": 0, "Medium": 0, "Low": 0, "Nitpick": 0}
    for r in reviews:
        sev = r.get("severity", "Low")
        counts[sev] = counts.get(sev, 0) + 1

    if counts["High"] > 0:
        verdict = "🔴 **Request Changes** — high severity issues require attention"
    elif counts["Medium"] > 2:
        verdict = "🟠 **Needs Discussion** — multiple medium severity findings"
    else:
        verdict = "✅ **Looks Good** — minor issues only, review with care"

    body = (
        f"## {AI_MARKER} — `{AI_MODEL}`\n\n"
        f"| Severity | Count |\n"
        f"|----------|-------|\n"
        f"| 🔴 High     | {counts['High']} |\n"
        f"| 🟠 Medium   | {counts['Medium']} |\n"
        f"| 🔵 Low      | {counts['Low']} |\n"
        f"| ◻️ Nitpick  | {counts['Nitpick']} |\n\n"
        f"**Verdict:** {verdict}\n\n"
        f"<sub>Inline comments follow below. This review is AI-generated — "
        f"verify before acting.</sub>"
    )
    post_simple_comment(body)


def _format_inline_body(issue: Dict) -> str:
    severity = issue.get("severity", "Note")
    icon = SEVERITY_ICON.get(severity, "🔵")
    return f"**{icon} AI Review [{severity}]**\n\n{issue.get('comment', '')}"


def post_inline_comments_with_fallback(reviews: List[Dict], shas: Optional[Dict],
                                       line_map: Dict[str, List[int]]) -> None:
    """Try an inline comment per finding; collect failures into one general comment."""
    general_comments: List[Dict] = []

    for issue in reviews:
        file_path = issue.get("file")
        line = issue.get("line")
        posted_inline = False

        if shas and file_path and isinstance(line, int):
            # Snap to nearest valid line before giving up on inline placement.
            snapped = snap_to_valid_line(line, line_map.get(file_path, []))
            target_line = snapped if snapped is not None else line

            payload = {
                "body": _format_inline_body(issue),
                "position": {
                    "position_type": "text",
                    "base_sha": shas["base_sha"],
                    "start_sha": shas["start_sha"],
                    "head_sha": shas["head_sha"],
                    "new_path": file_path,
                    "new_line": target_line,
                },
            }
            try:
                res = requests.post(f"{_mr_base()}/discussions", headers=get_headers(),
                                    json=payload, verify=CA_BUNDLE, timeout=REQUEST_TIMEOUT)
                if res.status_code == 201:
                    posted_inline = True
                    logger.info("✅ Inline comment on %s:%s", file_path, target_line)
                else:
                    logger.warning("⚠️  Inline failed on %s:%s (%s). Falling back.",
                                   file_path, target_line, res.status_code)
            except requests.RequestException as exc:
                logger.warning("⚠️  Inline failed on %s:%s (%s). Falling back.",
                               file_path, target_line, exc)

        if not posted_inline:
            general_comments.append(issue)

    if general_comments:
        body = f"### {AI_MARKER} — General Findings\n\n"
        for issue in general_comments:
            icon = SEVERITY_ICON.get(issue.get("severity"), "🔵")
            body += (
                f"#### {icon} `{issue.get('file', 'Unknown')}` "
                f"(Line: {issue.get('line', '?')}) — {issue.get('severity', 'Note')}\n"
                f"{issue.get('comment', '')}\n\n---\n"
            )
        post_simple_comment(body)


# ==========================================
# 9. Entry point
# ==========================================
def main() -> None:
    logger.info("🚀 AI Code Review starting for MR !%s ...", MR_IID)

    if not all([PROJECT_ID, MR_IID, GITLAB_TOKEN, AI_API_KEY]):
        logger.error("Missing required configuration (PROJECT_ID, MR_IID, GITLAB_TOKEN, AI_API_KEY).")
        return

    delete_existing_ai_reviews()

    meta = get_mr_metadata()
    diff = get_mr_diff()

    if not diff.strip():
        logger.info("No reviewable changes found.")
        return

    line_map = parse_valid_line_map(diff)
    shas = get_mr_shas()

    reviews = review_all_files(meta.get("title", ""), meta.get("description", ""), diff, line_map)

    if not reviews:
        post_simple_comment(f"{AI_MARKER}: no issues found. ✅")
        logger.info("Review complete — no findings.")
        return

    reviews = filter_by_severity(reviews)
    if not reviews:
        post_simple_comment(f"{AI_MARKER}: only findings below `{MIN_SEVERITY}` threshold. ✅")
        logger.info("Review complete — all findings below severity threshold.")
        return

    post_review_summary(reviews)
    post_inline_comments_with_fallback(reviews, shas, line_map)

    if any(r.get("severity") == "High" for r in reviews):
        add_label_to_mr("ai-high-severity")
    add_label_to_mr("ai-reviewed")

    logger.info("✅ Review complete — %d findings posted.", len(reviews))


if __name__ == "__main__":
    main()
