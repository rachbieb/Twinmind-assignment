package com.twinmind.coachcopilot.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record SuggestionsRequest(
        @Valid PromptSettings settings,
        @Valid @NotEmpty List<TranscriptEntry> transcriptEntries,
        int batchNumber
) {
}
