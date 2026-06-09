package com.example.aireviewer.service;

import com.example.aireviewer.config.ReviewerProperties;
import com.example.aireviewer.domain.FileChunk;
import com.example.aireviewer.infrastructure.GitLabApiClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts raw GitLab diff files into {@link FileChunk} objects.
 *
 * <p>For each file it:
 * <ol>
 *   <li>Skips deleted files and files matching the ignore-extensions list.</li>
 *   <li>Parses valid new-file line numbers from {@code @@} hunk headers.</li>
 *   <li>Limits the total number of chunks to {@code reviewer.max-files-per-review}.</li>
 * </ol>
 */
@Component
public class DiffChunker {

    /** Matches: {@code @@ -oldStart[,oldCount] +newStart[,newCount] @@} */
    private static final Pattern HUNK_HEADER =
            Pattern.compile("@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@");

    private final ReviewerProperties properties;

    public DiffChunker(ReviewerProperties properties) {
        this.properties = properties;
    }

    public List<FileChunk> split(List<GitLabApiClient.DiffFile> diffFiles) {
        return diffFiles.stream()
                .filter(f -> !f.deletedFile())
                .filter(f -> !isIgnored(f.newPath()))
                .filter(f -> f.diff() != null && !f.diff().isBlank())
                .map(f -> new FileChunk(f.newPath(), f.diff(), parseValidLines(f.diff())))
                .limit(properties.getMaxFilesPerReview())
                .toList();
    }

    // ─── Package-private for unit tests ──────────────────────────────────────

    List<Integer> parseValidLines(String diffText) {
        List<Integer> lines = new ArrayList<>();
        for (String line : diffText.split("\n")) {
            Matcher m = HUNK_HEADER.matcher(line);
            if (m.find()) {
                int start = Integer.parseInt(m.group(1));
                int count = m.group(2) != null ? Integer.parseInt(m.group(2)) : 1;
                for (int i = start; i < start + count; i++) {
                    lines.add(i);
                }
            }
        }
        return lines;
    }

    private boolean isIgnored(String filePath) {
        if (filePath == null) return true;
        return properties.getIgnoreExtensions().stream().anyMatch(filePath::endsWith);
    }
}
