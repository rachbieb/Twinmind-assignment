package com.twinmind.coachcopilot.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class GroqClient {

    private final WebClient groqWebClient;

    public GroqClient(WebClient groqWebClient) {
        this.groqWebClient = groqWebClient;
    }

    public String transcribe(String apiKey, String model, byte[] audioBytes, String filename, String contentType) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        ByteArrayResource resource = new ByteArrayResource(audioBytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        body.part("file", resource)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.formData().name("file").filename(filename).build().toString())
                .contentType(MediaType.parseMediaType(contentType));
        body.part("model", model);

        JsonNode response = groqWebClient.post()
                .uri("/openai/v1/audio/transcriptions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .bodyValue(body.build())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(60))
                .block();

        return response != null && response.hasNonNull("text") ? response.get("text").asText() : "";
    }

    public String chatCompletion(String apiKey, String model, JsonNode payload) {
        JsonNode response = groqWebClient.post()
                .uri("/openai/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(90))
                .block();

        JsonNode choices = response == null ? null : response.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            return "";
        }
        JsonNode content = choices.get(0).path("message").path("content");
        return content.isMissingNode() ? "" : content.asText("");
    }
}
