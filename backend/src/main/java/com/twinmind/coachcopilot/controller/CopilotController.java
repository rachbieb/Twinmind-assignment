package com.twinmind.coachcopilot.controller;

import com.twinmind.coachcopilot.model.ChatRequest;
import com.twinmind.coachcopilot.model.ChatResponse;
import com.twinmind.coachcopilot.model.SettingsResponse;
import com.twinmind.coachcopilot.model.SuggestionBatch;
import com.twinmind.coachcopilot.model.SuggestionsRequest;
import com.twinmind.coachcopilot.model.TranscriptionResponse;
import com.twinmind.coachcopilot.service.CopilotService;
import com.twinmind.coachcopilot.service.DefaultSettingsFactory;
import jakarta.validation.Valid;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class CopilotController {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("hh:mm:ss a", Locale.ENGLISH);

    private final DefaultSettingsFactory defaultSettingsFactory;
    private final CopilotService copilotService;

    public CopilotController(DefaultSettingsFactory defaultSettingsFactory, CopilotService copilotService) {
        this.defaultSettingsFactory = defaultSettingsFactory;
        this.copilotService = copilotService;
    }

    @GetMapping("/settings/defaults")
    public SettingsResponse defaults() {
        return new SettingsResponse(defaultSettingsFactory.create());
    }

    @PostMapping("/suggestions")
    public SuggestionBatch suggestions(@Valid @RequestBody SuggestionsRequest request) {
        return copilotService.generateSuggestions(request);
    }

    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return copilotService.answer(request);
    }

    @PostMapping(value = "/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public TranscriptionResponse transcribe(
            @RequestParam String apiKey,
            @RequestParam String model,
            @RequestPart("audio") MultipartFile audio
    ) throws IOException {
        String timestamp = OffsetDateTime.now().format(TIME_FORMATTER);
        if (apiKey.isBlank()) {
            return new TranscriptionResponse(
                    "Demo transcript chunk captured locally. Add your Groq API key in Settings for live Whisper transcription.",
                    timestamp,
                    true
            );
        }

        String text = copilotService.generateTranscription(
                apiKey,
                model,
                audio.getBytes(),
                audio.getOriginalFilename(),
                audio.getContentType()
        );
        return new TranscriptionResponse(text, timestamp, false);
    }
}
