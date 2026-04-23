package com.twinmind.coachcopilot.model;

public record TranscriptionResponse(
        String text,
        String timestamp,
        boolean mock
) {
}
