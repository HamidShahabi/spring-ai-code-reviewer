# Setting up the AI Code Reviewer demo on a local GitLab

This runbook recreates, from scratch, the demo where opening a Merge Request in a
self-hosted GitLab triggers an AI review that posts inline comments, a severity
summary, and labels back onto the MR.

There are **two ways** to run the reviewer; both are covered:

- **Method A — run the script locally** against a real MR. No GitLab Runner needed.
  Fastest to get working; good for a first proof.
- **Method B — GitLab CI pipeline.** The review runs as a job inside GitLab on every
  merge-request pipeline. This is the "it just happens when you open an MR" story —
  more setup, but it's the real product experience.

```
  open MR  ──▶  merge-request pipeline  ──▶  GitLab Runner (docker executor)
                                               │
                                               ▼
                                     python:3.12 job container
                                               │  runs reviewer.py
                                               ▼
                          GitLab API  ◀──  Claude (Anthropic API)
                       (inline comments,
                        summary, labels)
```

The reviewer itself is `reviewer.py` — a self-contained Python script that fetches an
MR diff, sends each changed file to Claude, and posts findings back. It reads its
configuration from environment variables (the same `CI_*` names GitLab CI provides,
so the *same script* works locally and in CI).

---

## 0. Prerequisites

- Docker + Docker Compose, with a working image registry. This guide assumes a
  **Docker Hub mirror** at `registry.docker.ir` (the host used here can't reach
  `registry.gitlab.com` or Docker Hub directly — adjust the registry prefix to match
  your environment, or drop it entirely if you have direct Docker Hub access).
- Python 3 on the host (for Method A and for the provisioning snippets).
- An **Anthropic API key** (`sk-ant-…`). This is the reviewer's LLM.

> **Proprietary code note:** the reviewer sends each changed file's *diff* to the
> Anthropic API. For a private/proprietary repo, make sure that's acceptable, or
> switch to a local model (the script can target any provider you wire in).

---

## 1. Bring up the infrastructure

GitLab CE and Postgres run via `docker-compose.yml` (GitLab is behind a profile):

```bash
docker compose --profile gitlab up -d
# First boot takes several minutes while GitLab reconfigures.
```

### Verify GitLab is actually up — and avoid the health-check trap

```bash
# ✅ From the host, check the web UI (NOT /-/health):
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/             # expect 302
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/users/sign_in # expect 200

# Container health:
docker inspect --format '{{.State.Health.Status}}' reviewer-gitlab          # expect healthy
```

> ⚠️ **Gotcha:** `http://localhost:8080/-/health` returns **404 from the host** — those
> monitoring endpoints are restricted to a whitelisted IP. A 404 there does **not** mean
> GitLab is down. To use that endpoint, call it from *inside* the container:
> `docker exec reviewer-gitlab curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/-/health` → `200`.

---

## 2. Get an admin API token

The reviewer needs a token with **`api`** scope to read the diff and post comments.
The initial root-password file disappears after first boot, so mint a token directly
via the Rails runner instead of recovering the password:

```bash
docker exec reviewer-gitlab gitlab-rails runner "
  u = User.find_by_username('root')
  t = u.personal_access_tokens.create!(scopes: ['api'], name: 'demo', expires_at: 30.days.from_now)
  puts t.token
"
```

> ⚠️ **Gotcha — routable tokens contain dots.** GitLab 16+ issues tokens like
> `glpat-AAAA._BBBB.CC…`. If you grep the token out of the runner output, your regex
> **must include `.`** — use `glpat-[A-Za-z0-9._-]+`. A regex of `[A-Za-z0-9_-]+`
> silently truncates the token at the first dot, and the truncated value returns
> **401 Unauthorized** on every API call. Verify with:
> `curl -s -H "PRIVATE-TOKEN: <token>" http://localhost:8080/api/v4/user` → your user JSON.

Set a web-login password too, if you want to click around the UI:

```bash
docker exec reviewer-gitlab gitlab-rails runner "
  u = User.find_by_username('root')
  u.password = 'Demo-Reviewer-2026!'; u.password_confirmation = 'Demo-Reviewer-2026!'
  u.password_automatically_set = false; u.save!
"
# Then log in at http://localhost:8080 as  root / Demo-Reviewer-2026!
```

---

## 3. Configure the reviewer

`reviewer.py` calls Claude through the official Anthropic SDK. Install its deps and
provide a key. The script reads (env var → meaning):

| Variable | Meaning |
|---|---|
| `CI_SERVER_URL` | GitLab base URL (in CI, GitLab sets this automatically) |
| `CI_PROJECT_ID` | numeric project id |
| `CI_MERGE_REQUEST_IID` | the MR's IID (the `!N` number) |
| `GITLAB_TOKEN` | `api`-scope token from step 2 |
| `ANTHROPIC_API_KEY` | your Anthropic key (`AI_API_KEY` also accepted) |
| `AI_MODEL` *(opt)* | `claude-haiku-4-5` (default) or `claude-sonnet-4-6` |
| `MIN_SEVERITY` *(opt)* | `Low` (default) / `Medium` / `High` |

```bash
python3 -m venv .venv-reviewer && . .venv-reviewer/bin/activate
pip install -r requirements.txt          # requests + anthropic
```

---

## 4. Provision a demo project + MR

Any project with a reviewable diff works. This snippet creates a project, seeds a clean
baseline on `main`, then opens an MR that introduces obvious issues (SQL injection,
hardcoded secret, an N+1 loop) — guaranteed findings for the demo:

```bash
. .venv-reviewer/bin/activate
export GITLAB_URL=http://localhost:8080 GITLAB_TOKEN=<token-from-step-2>
python - <<'PY'
import os, requests, urllib.parse
G, H = os.environ["GITLAB_URL"], {"PRIVATE-TOKEN": os.environ["GITLAB_TOKEN"]}
api = f"{G}/api/v4"
# 1. project
pid = requests.post(f"{api}/projects", headers=H, json={
    "name": "ai-reviewer-demo", "initialize_with_readme": True, "visibility": "private"}).json()["id"]
fp = urllib.parse.quote_plus("app/service.py")
base = "def get_user(db, uid):\n    return db.query(User).filter(User.id == uid).first()\n"
vuln = ('API_KEY = "sk-live-DEADBEEF"  # hardcoded secret\n'
        'def get_user(db, uid):\n'
        '    return db.execute("SELECT * FROM users WHERE id = \'" + uid + "\'").fetchone()\n')
# 2. baseline on main
requests.post(f"{api}/projects/{pid}/repository/files/{fp}", headers=H,
    json={"branch":"main","content":base,"commit_message":"baseline"})
# 3. feature branch + vulnerable change
requests.post(f"{api}/projects/{pid}/repository/branches", headers=H,
    params={"branch":"feature/demo","ref":"main"})
requests.put(f"{api}/projects/{pid}/repository/files/{fp}", headers=H,
    json={"branch":"feature/demo","content":vuln,"commit_message":"add lookup"})
# 4. open MR
iid = requests.post(f"{api}/projects/{pid}/merge_requests", headers=H, json={
    "source_branch":"feature/demo","target_branch":"main","title":"demo"}).json()["iid"]
print(f"PROJECT_ID={pid}  MR_IID={iid}")
PY
```

> To demo a **real** repository instead, import it (Project → Import, or push it with
> `git push http://root:<token>@localhost:8080/root/<name>.git`) and open a normal MR.

---

## Method A — run the reviewer locally (no runner)

The quickest path. Point the `CI_*` vars at the MR and run the script:

```bash
. .venv-reviewer/bin/activate
export CI_SERVER_URL=http://localhost:8080
export CI_PROJECT_ID=<pid>  CI_MERGE_REQUEST_IID=<iid>
export GITLAB_TOKEN=<token>  ANTHROPIC_API_KEY=<sk-ant-...>
python reviewer.py
```

Within seconds the MR gets a summary comment, inline comments on the changed lines,
and `ai-reviewed` / `ai-high-severity` labels. Re-running deletes its own old notes
first, so there are no duplicates.

---

## Method B — run the reviewer as a GitLab CI job

This is the realistic product flow. It needs a **GitLab Runner**, the reviewer files
committed into the project, CI/CD variables, and a `.gitlab-ci.yml`.

The fiddly part is **networking**: this GitLab's `external_url` is `http://localhost:8080`,
which is *not reachable from inside a job container*. The steps below work around that.

### B.1 Register a GitLab Runner (docker executor)

```bash
# Find GitLab's docker network (compose names it "<dir>_default"):
NET=$(docker inspect -f '{{range $k,$v := .NetworkSettings.Networks}}{{$k}}{{end}}' reviewer-gitlab)

# Create a runner authentication token via the API (GitLab 16+ flow):
TOKEN=$(curl -s -X POST -H "PRIVATE-TOKEN: <admin-token>" \
  http://localhost:8080/api/v4/user/runners \
  --data "runner_type=project_type" --data "project_id=<pid>" \
  --data "run_untagged=true" --data "tag_list=ai-review" \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['token'])")   # glrt-...

# Run the runner ON GitLab's network, with the docker socket mounted:
docker run -d --name reviewer-runner --network "$NET" \
  -v /var/run/docker.sock:/var/run/docker.sock \
  registry.docker.ir/gitlab/gitlab-runner:latest

# Register it. KEY FLAGS:
#   --docker-network-mode "$NET"  → job containers join GitLab's network (so they can
#                                    resolve the GitLab container by name)
#   --clone-url http://reviewer-gitlab:8080 → git clone uses the reachable internal URL,
#                                    not the unreachable external_url (localhost:8080)
docker exec reviewer-runner gitlab-runner register --non-interactive \
  --url "http://reviewer-gitlab:8080" \
  --token "$TOKEN" \
  --executor docker \
  --docker-image "registry.docker.ir/python:3.12-slim" \
  --docker-network-mode "$NET" \
  --docker-pull-policy "if-not-present" \
  --clone-url "http://reviewer-gitlab:8080"
```

> ⚠️ **Gotcha — the runner helper image.** The docker executor pulls a
> `gitlab-runner-helper` image from `registry.gitlab.com` by default. If that registry
> is blocked, every job fails in ~1s with *"failed to pull … gitlab-runner-helper …
> connection refused."* Point it at your mirror by adding `helper_image` under
> `[runners.docker]` in the runner config, then restart the runner:
> ```bash
> docker exec reviewer-runner sh -c \
>   'sed -i "/\[runners.docker\]/a\    helper_image = \"registry.docker.ir/gitlab/gitlab-runner-helper:x86_64-v19.1.0\"" /etc/gitlab-runner/config.toml'
> docker restart reviewer-runner
> ```
> Use a helper tag that matches your runner version (`gitlab-runner --version`).

Confirm the runner is online:
```bash
curl -s -H "PRIVATE-TOKEN: <admin-token>" http://localhost:8080/api/v4/runners/<runner-id> \
  | python3 -c "import sys,json;r=json.load(sys.stdin);print(r['status'], r['online'])"   # online True
```

### B.2 Add the reviewer files to the project

The job clones the project, so `reviewer.py`, `requirements.txt`, and `.gitlab-ci.yml`
must live **in the repo being reviewed** (commit them to `main`). `.gitlab-ci.yml`:

```yaml
ai_review:
  image: registry.docker.ir/python:3.12-slim
  rules:
    - if: '$CI_PIPELINE_SOURCE == "merge_request_event"'
  variables:
    GIT_DEPTH: "50"
  before_script:
    - pip install --quiet --no-cache-dir -r requirements.txt
  script:
    # Override the unreachable external_url (localhost) with an address the job container
    # can resolve on the runner network. CI_SERVER_URL_OVERRIDE is a CI/CD variable.
    - export CI_SERVER_URL="${CI_SERVER_URL_OVERRIDE:-$CI_SERVER_URL}"
    - python reviewer.py
```

### B.3 Set CI/CD variables

Project → Settings → CI/CD → Variables (or via API). Set **Protected: off** so they're
available on feature branches, and **Masked: off** for values containing dots/special
chars that fail GitLab's masking rules (the `glpat-` token and `sk-ant-` key do):

| Key | Value |
|---|---|
| `GITLAB_TOKEN` | the `api`-scope token |
| `ANTHROPIC_API_KEY` | your Anthropic key |
| `CI_SERVER_URL_OVERRIDE` | `http://reviewer-gitlab:8080` |
| `AI_MODEL` *(opt)* | `claude-haiku-4-5` |

### B.4 Trigger and verify

Open an MR **from a branch that already contains `.gitlab-ci.yml`** (i.e. branch off
`main` *after* B.2). That starts a merge-request pipeline; the `ai_review` job runs the
reviewer and posts back. Check it:

```bash
curl -s -H "PRIVATE-TOKEN: <token>" \
  "http://localhost:8080/api/v4/projects/<pid>/merge_requests/<iid>/pipelines"   # status: success
```

The MR should show the summary, inline comments, and `ai-reviewed` /
`ai-high-severity` labels — all produced inside CI.

---

## Demoing a real existing repository (the "digicard" pattern)

The walkthrough above uses a throwaway project. To review a **real repo's MR** — without
adding any reviewer scaffolding to the branch under review — use this pattern.

### Reproduce a real MR faithfully

Pick a merged MR and recreate it from its merge commit's two parents, so the diff is
byte-identical to the original:

```bash
M=<merge-commit-sha>                       # the "Merge branch 'feat/X' into 'staging'" commit
git -C <repo> rev-parse ${M}^1 ${M}^2      # ^1 = target (staging) before merge, ^2 = feature tip
REMOTE="http://oauth2:<token>@localhost:8080/root/<project>.git"
git -C <repo> push "$REMOTE" ${M}^1:refs/heads/staging
git -C <repo> push "$REMOTE" ${M}^2:refs/heads/feat/X
```

Open an MR `feat/X → staging`; its diff is exactly what the original MR changed.
(Pushing the two branch tips brings all the ancestry the diff needs.)

### Run CI without polluting the MR diff (GitLab CE)

> ⚠️ **Merged-results pipelines are a GitLab _Premium_ feature.** On CE,
> `merge_pipelines_enabled` silently stays off, and a detached MR pipeline reads the
> **source branch's** `.gitlab-ci.yml` — which a real feature branch doesn't have. The
> result is a pipeline with **no jobs** that fails immediately. So you can't rely on the
> target branch's CI config being picked up automatically.

The CE-friendly fix keeps the feature branch free of CI files by pointing the project's
**CI config path** at a fixed branch, and making that config self-contained:

1. Put a self-contained `.gitlab-ci.yml` on a stable branch (e.g. `staging`). It does
   **not** check out the repo (`GIT_STRATEGY: none` — the reviewer only needs the GitLab
   API), and it fetches `reviewer.py` at runtime. The `slim` image has **no `curl`**, so
   fetch with Python (`requests` is already installed):

   ```yaml
   ai_review:
     image: registry.docker.ir/python:3.12-slim
     variables:
       GIT_STRATEGY: none
     rules:
       - if: '$CI_PIPELINE_SOURCE == "merge_request_event"'
     script:
       - export CI_SERVER_URL="${CI_SERVER_URL_OVERRIDE:-$CI_SERVER_URL}"
       - pip install --quiet --no-cache-dir requests anthropic
       - python -c "import os,requests; r=requests.get(os.environ['CI_SERVER_URL']+'/api/v4/projects/'+os.environ['CI_PROJECT_ID']+'/repository/files/reviewer.py/raw',params={'ref':'staging'},headers={'PRIVATE-TOKEN':os.environ['GITLAB_TOKEN']}); r.raise_for_status(); open('reviewer.py','w').write(r.text)"
       - python reviewer.py
   ```

2. Point the project at it so **every** pipeline (regardless of source branch) uses this
   config:

   ```bash
   curl -s -X PUT -H "PRIVATE-TOKEN: <token>" \
     "http://localhost:8080/api/v4/projects/<pid>" \
     --data-urlencode "ci_config_path=.gitlab-ci.yml@root/<project>"
   ```

3. Commit `reviewer.py` to the `staging` branch (it's fetched from there at runtime), and
   set the same CI/CD variables as Method B (`GITLAB_TOKEN`, `ANTHROPIC_API_KEY`,
   `CI_SERVER_URL_OVERRIDE`, `AI_MODEL`).

Now opening an MR from a pristine feature branch triggers the review, and the MR diff
contains only the real changes — no `.gitlab-ci.yml`, no `reviewer.py`.

### One runner for many projects

A `project_type` runner only serves the one project it was registered for. To serve
several demo projects, register an **instance** runner into the same container:

```bash
ITOK=$(curl -s -X POST -H "PRIVATE-TOKEN: <admin-token>" \
  http://localhost:8080/api/v4/user/runners \
  --data "runner_type=instance_type" --data "run_untagged=true" \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['token'])")
docker exec reviewer-runner gitlab-runner register --non-interactive \
  --url http://reviewer-gitlab:8080 --token "$ITOK" \
  --executor docker --docker-image registry.docker.ir/python:3.12-slim \
  --docker-helper-image registry.docker.ir/gitlab/gitlab-runner-helper:x86_64-v19.1.0 \
  --docker-network-mode <gitlab-net> --clone-url http://reviewer-gitlab:8080
```

### Triggering a review live (in front of an audience)

The reviewer needs a **merge-request** pipeline (it reads `CI_MERGE_REQUEST_IID`), so
always trigger from the **MR**, never from *Project → CI/CD → Pipelines* (a branch
pipeline has no MR context and the job exits early).

- **Re-run on an MR:** open the MR → **Pipelines** tab → **Run pipeline**. The reviewer
  deletes its own previous comments first, so re-runs cleanly replace them — repeatable,
  no duplicates.
- **Fresh-MR reveal (comments appear from nothing):** pre-stage an MR, let one pipeline
  run to warm the image cache, then **strip its AI output** so it looks un-reviewed —
  delete notes whose body contains `AI Code Review` / `AI Review [`, and
  `PUT /merge_requests/:iid` with `remove_labels=ai-reviewed,ai-high-severity`. During the
  demo, **Run pipeline** makes the review appear on a blank MR.
- **Backstage trigger (insurance):** `POST /projects/<pid>/merge_requests/<iid>/pipelines`.
- **Timing:** the first run pulls the image and is slow; warmed runs are ~45s — warm the
  cache before the demo.

---

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `/-/health` returns 404 from the host | Expected — monitoring endpoints are IP-whitelisted. Check `GET /` (302) or run the health check inside the container. |
| API calls return `401 Unauthorized` with a fresh token | Token was truncated at a `.` — routable tokens contain dots; capture with `glpat-[A-Za-z0-9._-]+`. |
| CI job fails in ~1s: "failed to pull gitlab-runner-helper" | Helper image registry blocked — set `helper_image` to your mirror (B.1 gotcha). |
| Job logs: clone fails / `localhost` connection refused | Job container can't reach `external_url`. Register the runner with `--clone-url http://reviewer-gitlab:8080` and `--docker-network-mode <gitlab-net>`. |
| reviewer.py in CI hits the wrong host for the API | Set `CI_SERVER_URL_OVERRIDE=http://reviewer-gitlab:8080` and export it in `.gitlab-ci.yml`. |
| No pipeline appears on the MR | The source branch lacks `.gitlab-ci.yml`, or no runner is online for the project. Branch off `main` after committing the CI file; confirm the runner status is `online`. |
| Inline comment falls back to a general comment | The finding's line is outside the diff's changed ranges — expected; the script snaps to the nearest valid line and falls back if it can't. |
| MR pipeline runs but has **no jobs** and fails instantly | Detached MR pipeline read the source branch's (missing) `.gitlab-ci.yml`. Merged-results pipelines are Premium and won't help on CE — use `ci_config_path=…@<branch>` (the digicard pattern). |
| Job fails with `curl: command not found` | `python:3.12-slim` has no curl — fetch files with `python -c "import requests…"`. |
| Reviewer exits early / "Missing required configuration" | You triggered a **branch** pipeline (Project → Pipelines), which has no `CI_MERGE_REQUEST_IID`. Trigger from the MR's **Pipelines** tab instead. |
| `merge_pipelines_enabled` won't turn on (stays null) | It's a Premium feature; not available on GitLab CE. Use the `ci_config_path` approach. |

---

## Cleanup

```bash
docker rm -f reviewer-runner          # remove the runner
rm -rf .venv-reviewer                  # remove the local venv
docker compose --profile gitlab down   # stop GitLab/Postgres (add -v to wipe volumes)
```
