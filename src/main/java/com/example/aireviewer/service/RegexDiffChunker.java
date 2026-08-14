package com.example.aireviewer.service;

import com.example.aireviewer.config.ReviewerProperties;
import com.example.aireviewer.domain.FileChunk;
import com.example.aireviewer.infrastructure.GitLabClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** {@link DiffChunker} that parses unified-diff {@code @@} hunk headers with a regex. */
@Component
public class RegexDiffChunker implements DiffChunker {

    /** Matches: {@code @@ -oldStart[,oldCount] +newStart[,newCount] @@} */
    private static final Pattern HUNK_HEADER =
            Pattern.compile("@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@");

    private final ReviewerProperties properties;

    public RegexDiffChunker(ReviewerProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<FileChunk> split(List<GitLabClient.DiffFile> diffFiles) {
        return diffFiles.stream()
                .filter(f -> !f.deletedFile())
                .filter(f -> !isIgnored(f.newPath()))
                .filter(f -> f.diff() != null && !f.diff().isBlank())
                .map(f -> new FileChunk(f.newPath(), f.diff(), parseValidLines(f.diff())))
                .limit(properties.getMaxFilesPerReview())
                .toList();
    }

    @Override
    public List<Integer> parseValidLines(String diffText) {
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
