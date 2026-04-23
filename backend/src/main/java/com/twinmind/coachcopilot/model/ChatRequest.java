package com.twinmind.coachcopilot.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record ChatRequest(
        @Valid PromptSettings settings,
        @Valid List<TranscriptEntry> transcriptEntries,
        @Valid List<ChatMessage> chatHistory,
        String suggestionType,
        @NotBlank String userMessage
) {
}
