# Local MVP Demo Runbook

End-to-end demo of the Spring AI code reviewer against a self-hosted GitLab. Goal: open an MR
and watch the service post a summary + inline comments and label the MR.

---

## 0. Prerequisites

- Docker + Docker Compose, JDK 17, Maven.
- An **Anthropic API key** (the default provider). To run fully offline instead, use Ollama — see §3.
- ~4 GB free RAM if you run GitLab CE in the compose stack.

---

## 1. Start the infrastructure

```bash
# Postgres (required — the app won't start without it)
docker compose up -d
```

The LLM is Anthropic (cloud) by default, so no local model container is needed. If you
already have a GitLab instance, skip the next block. To run GitLab CE here too:

```bash
docker compose --profile gitlab up -d
# First boot takes several minutes. Then:
docker exec -it reviewer-gitlab cat /etc/gitlab/initial_root_password   # login: root
```

Open http://localhost:8080 and log in.

---

## 2. One-time GitLab setup

1. **Allow webhooks to localhost** (critical — GitLab blocks local-network webhooks by default):
   *Admin → Settings → Network → Outbound requests →* check
   **"Allow requests to the local network from webhooks and integrations"** → Save.
2. Create a project and push some code (any Java/Python repo works for a richer review).
3. Create an access token with **`api`** scope:
   *Project → Settings → Access Tokens* (or a personal token). Copy it.

---

## 3. Configure & run the app

```bash
cp .env.example .env
# Edit .env: set GITLAB_TOKEN + ANTHROPIC_API_KEY, set GITLAB_WEBHOOK_SECRET=demo-secret.
# (To go fully offline instead, switch to provider block (b) with Ollama.)
set -a; source .env; set +a

mvn spring-boot:run
```

The app listens on **http://localhost:8080**… which collides with GitLab. If GitLab is on 8080,
run the app on another port:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=9000
```

Health check: `curl http://localhost:9000/actuator/health` → `{"status":"UP"}`.

---

## 4. Register the webhook in GitLab

*Project → Settings → Webhooks → Add new webhook*

- **URL:** `http://host.docker.internal:9000/api/v1/webhook`
  (from inside the GitLab container, `host.docker.internal` reaches the host-run app;
  on Linux this resolves via the compose `extra_hosts`/default gateway — if it fails, use the host's LAN IP.)
- **Secret token:** `demo-secret` (must equal `GITLAB_WEBHOOK_SECRET`)
- **Trigger:** check **Merge request events**
- Save, then **Test → Merge request events** to fire a sample event.

---

## 5. Run the demo

1. Create a branch, introduce an obvious issue (e.g. SQL built by string concatenation, a hardcoded
   secret, an N+1 loop), and open a Merge Request.
2. Within a few seconds the service:
   - posts a **summary comment** with the severity table + verdict (header shows the model name),
   - posts **inline comments** on the changed lines (falls back to a general comment if a line is invalid),
   - adds the **`ai-reviewed`** label (and **`ai-high-severity`** if any High finding).
3. Re-push to the MR → old AI comments are deleted and replaced (no duplicates).

---

## 6. Talking points / verification

- **Metrics:** `curl http://localhost:9000/actuator/metrics/reviewer.mr.review.duration`
  and `.../reviewer.comment.inline.success` vs `.../reviewer.comment.inline.fallback`.
- **Persistence:** `docker exec -it reviewer-postgres psql -U reviewer -d ai_reviewer -c 'select project_id, mr_iid, model_used, findings_high, duration_ms from mr_reviews;'`
- **Provider swap (privacy story):** stop the app, switch `.env` from provider block (a) Anthropic to
  (b) a local Ollama (`AI_PROVIDER=openai`, `AI_BASE_URL=http://localhost:11434`), restart — no code
  change. Same `ChatClient` abstraction, different provider; with Ollama no code leaves the network.

---

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| App won't start, `Connection refused :5432` | Postgres not up — `docker compose ps`, `docker compose up -d postgres`. |
| Webhook returns 401 | `GITLAB_WEBHOOK_SECRET` ≠ the webhook's Secret token. |
| Webhook "blocked / local network not allowed" | Enable the outbound-requests setting in step 2.1. |
| Webhook delivered but no comments | Check app logs; usually the LLM call failed (bad `ANTHROPIC_API_KEY` / wrong model) or the GitLab token lacks `api` scope. |
| Inline comments all fall back to a general comment | Line numbers outside the diff — expected for findings on unchanged lines; the valid-line constraint minimises it. |
| `host.docker.internal` unreachable on Linux | Use the host's LAN IP in the webhook URL instead. |
