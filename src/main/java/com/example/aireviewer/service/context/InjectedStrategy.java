package com.example.aireviewer.service.context;

import com.example.aireviewer.domain.FileChunk;
import com.example.aireviewer.domain.MrContext;
import com.example.aireviewer.infrastructure.GitLabClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deterministic "context floor": for each changed file, fetch its <b>full content at the
 * reviewed head SHA</b> and inject it into the prompt, so the model sees the whole method/class
 * around the diff hunks — not just the few context lines a diff carries.
 *
 * <p>Works on any model (no tool-calling required), is pinned to the exact revision (no branch
 * staleness, no re-indexing), and the application controls exactly what is sent (bounded tokens).
 * Per-MR instance: caches fetched files so re-reviewing the same file doesn't refetch.
 */
public class InjectedStrategy implements ContextStrategy {

    private static final Logger log = LoggerFactory.getLogger(InjectedStrategy.class);

    private final GitLabClient gitlab;
    private final long projectId;
    private final String ref;
    private final int maxLines;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public InjectedStrategy(GitLabClient gitlab, MrContext ctx, int maxLines) {
        this.gitlab    = gitlab;
        this.projectId = ctx.projectId();
        this.ref       = ctx.headCommitSha();
        this.maxLines  = maxLines;
    }

    @Override
    public String name() {
        return "injected";
    }

    @Override
    public String injectedContextFor(FileChunk chunk) {
        String content = cache.computeIfAbsent(chunk.filePath(), path -> {
            String raw = gitlab.fetchFileAtRef(projectId, path, ref);
            if (raw == null) {
                log.debug("injected: no content for {} @ {} (new file?)", path, ref);
                return "";
            }
            return truncate(raw);
        });
        if (content.isEmpty()) {
            return "";
        }
        return """
                Full content of `%s` at the revision under review (use it to judge the diff in
                its surrounding context — imports, fields, and the rest of the methods):
                ```
                %s
                ```""".formatted(chunk.filePath(), content);
    }

    private String truncate(String content) {
        String[] lines = content.split("\n", -1);
        if (lines.length <= maxLines) {
            return content;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxLines; i++) {
            sb.append(lines[i]).append('\n');
        }
        sb.append("… [truncated — file has ").append(lines.length)
          .append(" lines; first ").append(maxLines).append(" shown]");
        return sb.toString();
    }
}
