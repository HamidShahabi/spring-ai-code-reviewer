package com.example.aireviewer.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Deserialized payload of a GitLab {@code Merge Request Hook} webhook event.
 * Unknown fields are ignored so that future GitLab API additions don't break parsing.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MrEvent(
        @JsonProperty("object_kind") String objectKind,
        Project project,
        @JsonProperty("object_attributes") ObjectAttributes objectAttributes
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Project(
            long id,
            @JsonProperty("web_url") String webUrl
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ObjectAttributes(
            long iid,
            String title,
            String description,
            String state,
            String action,
            /** Previous head SHA — GitLab sets this only when the update carried new commits. */
            @JsonProperty("oldrev") String oldrev
    ) {}
}
