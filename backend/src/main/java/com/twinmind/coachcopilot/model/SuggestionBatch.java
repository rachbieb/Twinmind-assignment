package com.twinmind.coachcopilot.model;

import java.util.List;

public record SuggestionBatch(
        String batchLabel,
        String timestamp,
        List<SuggestionCard> suggestions
) {
}
