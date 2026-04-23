package com.twinmind.coachcopilot.model;

import jakarta.validation.constraints.NotBlank;

public record TranscriptEntry(
        @NotBlank String timestamp,
        @NotBlank String text
) {
}
