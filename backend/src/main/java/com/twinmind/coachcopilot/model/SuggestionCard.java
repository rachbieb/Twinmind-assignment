package com.twinmind.coachcopilot.model;

import jakarta.validation.constraints.NotBlank;

public record SuggestionCard(
        @NotBlank String type,
        @NotBlank String title,
        @NotBlank String preview
) {
}
