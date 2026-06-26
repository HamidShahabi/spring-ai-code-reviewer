package com.example.aireviewer.controller;

import com.example.aireviewer.config.GitLabProperties;
import com.example.aireviewer.domain.MrEvent;
import com.example.aireviewer.service.ReviewOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Accepts GitLab {@code Merge Request Hook} webhook events.
 *
 * <ul>
 *   <li>Validates the {@code X-Gitlab-Token} shared secret.</li>
 *   <li>Ignores non-MR events and non-reviewable actions ({@code close}, {@code merge}, …).</li>
 *   <li>Dispatches qualifying events to {@link ReviewOrchestrator} asynchronously
 *       so GitLab receives a {@code 202 Accepted} immediately.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private static final String MERGE_REQUEST_HOOK = "Merge Request Hook";

    private final ReviewOrchestrator orchestrator;
    private final GitLabProperties   gitLabProperties;

    public WebhookController(ReviewOrchestrator orchestrator,
                             GitLabProperties gitLabProperties) {
        this.orchestrator     = orchestrator;
        this.gitLabProperties = gitLabProperties;
    }

    @PostMapping("/webhook")
    public ResponseEntity<Void> handleWebhook(
            @RequestHeader(value = "X-Gitlab-Token",  required = false) String token,
            @RequestHeader(value = "X-Gitlab-Event",  required = false) String event,
            @RequestBody MrEvent mrEvent) {

        // 1. Authenticate
        if (!gitLabProperties.getWebhookSecret().equals(token)) {
            log.warn("Webhook rejected — invalid X-Gitlab-Token");
            return ResponseEntity.status(401).build();
        }

        // 2. Filter event type
        if (!MERGE_REQUEST_HOOK.equals(event)) {
            return ResponseEntity.ok().build();
        }

        // 3. Decide whether this MR change warrants a (re-)review.
        //    Review on creation/reopen, and on updates ONLY when new commits were pushed
        //    (oldrev is set). This skips label/description/assignee edits and the trailing
        //    metadata 'update' GitLab fires right after 'open' (which would double-review).
        MrEvent.ObjectAttributes attrs = mrEvent.objectAttributes();
        if (attrs == null) {
            return ResponseEntity.ok().build();
        }
        String action = attrs.action();
        boolean newCommits = attrs.oldrev() != null && !attrs.oldrev().isBlank();
        boolean shouldReview =
                "open".equals(action)
                || "reopen".equals(action)
                || ("update".equals(action) && newCommits);

        if (!shouldReview) {
            log.debug("Ignoring MR webhook — action={} newCommits={}", action, newCommits);
            return ResponseEntity.ok().build();
        }

        // 4. Dispatch asynchronously — return 202 immediately
        log.info("Accepted review for project={} mrIid={} action={}",
                mrEvent.project().id(), mrEvent.objectAttributes().iid(), action);
        orchestrator.triggerReview(mrEvent);
        return ResponseEntity.accepted().build();
    }
}
