package com.example.aireviewer.domain;

import jakarta.persistence.*;

import java.util.UUID;

/**
 * Persistent record of a single LLM finding within a review session.
 * Maps to the {@code findings} table.
 */
@Entity
@Table(name = "findings")
public class Finding {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_id", nullable = false)
    private MrReview review;

    @Column(name = "file_path", length = 512, nullable = false)
    private String filePath;

    @Column(name = "line_number")
    private int lineNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", length = 16, nullable = false)
    private Severity severity;

    @Column(name = "comment", columnDefinition = "TEXT")
    private String comment;

    @Column(name = "posted_inline")
    private boolean postedInline;

    @Column(name = "gitlab_note_id")
    private Long gitlabNoteId;

    /** JPA no-arg constructor. */
    protected Finding() {}

    public Finding(MrReview review, String filePath, int lineNumber,
                   Severity severity, String comment) {
        this.review     = review;
        this.filePath   = filePath;
        this.lineNumber = lineNumber;
        this.severity   = severity;
        this.comment    = comment;
    }

    // ─── Getters ────────────────────────────────────────────────────────────

    public UUID getId()             { return id; }
    public MrReview getReview()     { return review; }
    public String getFilePath()     { return filePath; }
    public int getLineNumber()      { return lineNumber; }
    public Severity getSeverity()   { return severity; }
    public String getComment()      { return comment; }
    public boolean isPostedInline() { return postedInline; }
    public Long getGitlabNoteId()   { return gitlabNoteId; }

    // ─── Setters ────────────────────────────────────────────────────────────

    public void setPostedInline(boolean postedInline)   { this.postedInline = postedInline; }
    public void setGitlabNoteId(Long gitlabNoteId)      { this.gitlabNoteId = gitlabNoteId; }
}
