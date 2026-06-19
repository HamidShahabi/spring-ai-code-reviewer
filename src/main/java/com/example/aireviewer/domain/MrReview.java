package com.example.aireviewer.domain;

import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Persistent record of a single AI review run against one Merge Request.
 * Maps to the {@code mr_reviews} table.
 */
@Entity
@Table(name = "mr_reviews")
public class MrReview {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private long projectId;

    @Column(name = "mr_iid", nullable = false)
    private long mrIid;

    @Column(name = "model_used", length = 120)
    private String modelUsed;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;

    @Column(name = "files_reviewed")
    private int filesReviewed;

    @Column(name = "findings_high")
    private int findingsHigh;

    @Column(name = "findings_medium")
    private int findingsMedium;

    @Column(name = "findings_low")
    private int findingsLow;

    @Column(name = "findings_nitpick")
    private int findingsNitpick;

    @Column(name = "duration_ms")
    private long durationMs;

    @OneToMany(mappedBy = "review", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Finding> findings = new ArrayList<>();

    /** JPA no-arg constructor. */
    protected MrReview() {}

    public MrReview(long projectId, long mrIid, String modelUsed) {
        this.projectId   = projectId;
        this.mrIid       = mrIid;
        this.modelUsed   = modelUsed;
        this.reviewedAt  = OffsetDateTime.now();
    }

    // ─── Getters ────────────────────────────────────────────────────────────

    public UUID getId()               { return id; }
    public long getProjectId()        { return projectId; }
    public long getMrIid()            { return mrIid; }
    public String getModelUsed()      { return modelUsed; }
    public OffsetDateTime getReviewedAt() { return reviewedAt; }
    public int getFilesReviewed()     { return filesReviewed; }
    public int getFindingsHigh()      { return findingsHigh; }
    public int getFindingsMedium()    { return findingsMedium; }
    public int getFindingsLow()       { return findingsLow; }
    public int getFindingsNitpick()   { return findingsNitpick; }
    public long getDurationMs()       { return durationMs; }
    public List<Finding> getFindings(){ return findings; }

    /**
     * Attaches a finding to this review. Cascade persists it with the parent.
     * The {@link Finding} constructor already sets the back-reference to this review.
     */
    public void addFinding(Finding finding) { this.findings.add(finding); }

    // ─── Setters ────────────────────────────────────────────────────────────

    public void setFilesReviewed(int filesReviewed)       { this.filesReviewed = filesReviewed; }
    public void setFindingsHigh(int findingsHigh)         { this.findingsHigh = findingsHigh; }
    public void setFindingsMedium(int findingsMedium)     { this.findingsMedium = findingsMedium; }
    public void setFindingsLow(int findingsLow)           { this.findingsLow = findingsLow; }
    public void setFindingsNitpick(int findingsNitpick)   { this.findingsNitpick = findingsNitpick; }
    public void setDurationMs(long durationMs)            { this.durationMs = durationMs; }
}
