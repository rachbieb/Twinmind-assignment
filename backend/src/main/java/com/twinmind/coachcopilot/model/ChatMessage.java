package com.twinmind.coachcopilot.model;

import jakarta.validation.constraints.NotBlank;

public record ChatMessage(
        @NotBlank String role,
        @NotBlank String content,
        String timestamp,
        String suggestionType
) {
}
