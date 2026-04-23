package com.twinmind.coachcopilot.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record PromptSettings(
        String apiKey,
        @NotBlank String transcriptionModel,
        @NotBlank String suggestionsModel,
        @NotBlank String chatModel,
        @Min(10) @Max(300) int refreshIntervalSeconds,
        @Min(1000) @Max(12000) int suggestionsContextChars,
        @Min(1000) @Max(16000) int expandedContextChars,
        @Min(1000) @Max(24000) int chatContextChars,
        @NotBlank String suggestionsPrompt,
        @NotBlank String clickPrompt,
        @NotBlank String chatPrompt
) {
}
