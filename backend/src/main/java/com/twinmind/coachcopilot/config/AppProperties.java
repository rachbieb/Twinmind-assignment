package com.twinmind.coachcopilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String groqBaseUrl,
        String transcriptionModel,
        String suggestionsModel,
        String chatModel,
        int defaultRefreshIntervalSeconds
) {
}
