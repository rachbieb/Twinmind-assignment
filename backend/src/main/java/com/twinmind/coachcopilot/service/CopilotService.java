package com.twinmind.coachcopilot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.twinmind.coachcopilot.config.AppProperties;
import com.twinmind.coachcopilot.model.ChatMessage;
import com.twinmind.coachcopilot.model.ChatRequest;
import com.twinmind.coachcopilot.model.ChatResponse;
import com.twinmind.coachcopilot.model.PromptSettings;
import com.twinmind.coachcopilot.model.SuggestionBatch;
import com.twinmind.coachcopilot.model.SuggestionCard;
import com.twinmind.coachcopilot.model.SuggestionsRequest;
import com.twinmind.coachcopilot.model.TranscriptEntry;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class CopilotService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("hh:mm:ss a", Locale.ENGLISH);
    private static final Set<String> QUESTION_STARTERS = Set.of(
            "what", "why", "how", "when", "where", "who", "which", "can", "could", "should",
            "would", "do", "does", "did", "is", "are", "will", "whats", "what's"
    );
    private static final Map<Pattern, String> TRANSCRIPTION_CORRECTIONS = buildTranscriptionCorrections();
    private static final String TRANSCRIPT_CLEANUP_PROMPT = """
            You are a transcript cleanup assistant.
            A speech-to-text model has produced a rough transcript chunk from a live conversation.

            Your task:
            - Correct spelling, punctuation, and obvious transcription mistakes.
            - Fix likely product and company names when context makes them clear.
            - Preserve the original meaning.
            - Do not add new facts.
            - Do not summarize.
            - Do not explain your edits.
            - Return only the corrected transcript text.

            Common examples:
            - "charge gpt", "chat gpd", "charge ept" -> "ChatGPT"
            - "open a i" -> "OpenAI"
            - "gimini" -> "Gemini"
            - "whats app" -> "WhatsApp"
            """;

    private final GroqClient groqClient;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;

    public CopilotService(GroqClient groqClient, ObjectMapper objectMapper, AppProperties appProperties) {
        this.groqClient = groqClient;
        this.objectMapper = objectMapper;
        this.appProperties = appProperties;
    }

    public String generateTranscription(String apiKey, String model, byte[] audioBytes, String originalFilename, String contentType) {
        String filename = StringUtils.hasText(originalFilename) ? originalFilename : "chunk.webm";
        String mediaType = StringUtils.hasText(contentType) ? contentType : "audio/webm";
        String transcript = groqClient.transcribe(apiKey, model, audioBytes, filename, mediaType);
        String normalized = normalizeTranscriptText(transcript);
        String refined = refineTranscriptWithAi(apiKey, normalized);
        return normalizeTranscriptText(refined);
    }

    public SuggestionBatch generateSuggestions(SuggestionsRequest request) {
        PromptSettings settings = request.settings();
        String timestamp = nowStamp();
        List<TranscriptEntry> transcriptEntries = request.transcriptEntries() == null ? List.of() : request.transcriptEntries();
        if (settings.apiKey().isBlank()) {
            return mockSuggestions(request, timestamp);
        }

        String transcriptContext = buildSuggestionTranscriptContext(transcriptEntries, settings.suggestionsContextChars());
        boolean answerIsNeeded = needsAnswerSuggestion(transcriptEntries);
        String prompt = """
                Answer card needed right now: %s

                Instruction:
                Focus primarily on the most recent topic in the transcript.
                If the conversation has changed topics, ignore older topics unless the newest transcript clearly returns to them.

                Transcript context:
                %s
                """.formatted(answerIsNeeded ? "YES" : "NO", transcriptContext);

        var payload = objectMapper.createObjectNode()
                .put("model", settings.suggestionsModel())
                .put("temperature", 0.4);
        payload.putObject("response_format").put("type", "json_object");
        payload.putArray("messages")
                .add(message("system", settings.suggestionsPrompt()))
                .add(message("user", prompt));

        String content = groqClient.chatCompletion(settings.apiKey(), settings.suggestionsModel(), payload);

        try {
            JsonNode root = objectMapper.readTree(content);
            JsonNode cards = root.path("suggestions");
            List<SuggestionCard> suggestions = new ArrayList<>();
            if (cards.isArray()) {
                for (JsonNode card : cards) {
                    String normalizedType = normalizeType(card.path("type").asText("clarifying_info"));
                    suggestions.add(new SuggestionCard(
                            normalizedType,
                            canonicalTitle(normalizedType),
                            card.path("preview").asText("No preview generated.")
                    ));
                }
            }

            suggestions = rebalanceSuggestions(suggestions, transcriptEntries);

            while (suggestions.size() < 3) {
                suggestions.add(fallbackSuggestion(suggestions.size()));
            }

            return new SuggestionBatch("Batch " + request.batchNumber(), timestamp, suggestions.subList(0, 3));
        } catch (Exception exception) {
            return mockSuggestions(request, timestamp);
        }
    }

    public ChatResponse answer(ChatRequest request) {
        PromptSettings settings = request.settings();
        String timestamp = nowStamp();
        List<TranscriptEntry> transcriptEntries = request.transcriptEntries() == null ? List.of() : request.transcriptEntries();
        List<ChatMessage> chatHistory = request.chatHistory() == null ? List.of() : request.chatHistory();
        if (settings.apiKey().isBlank()) {
            return new ChatResponse(mockAnswer(request), timestamp);
        }

        boolean directQuestion = request.suggestionType() == null || "direct_question".equals(request.suggestionType());
        int contextWindow = directQuestion ? settings.chatContextChars() : settings.expandedContextChars();
        String transcriptContext = trimTranscript(transcriptEntries, contextWindow);
        StringBuilder history = new StringBuilder();
        for (ChatMessage message : chatHistory) {
            history.append(message.role()).append(": ").append(message.content()).append('\n');
        }

        String userPrompt = """
                Suggestion type: %s
                Transcript context:
                %s

                Existing chat:
                %s

                User request:
                %s
                """.formatted(
                request.suggestionType() == null ? "direct_question" : request.suggestionType(),
                transcriptContext,
                history,
                request.userMessage()
        );

        var payload = objectMapper.createObjectNode()
                .put("model", settings.chatModel())
                .put("temperature", 0.3);
        payload.putArray("messages")
                .add(message("system", settings.chatPrompt() + "\n\n" + settings.clickPrompt()))
                .add(message("user", userPrompt));

        String answer = groqClient.chatCompletion(settings.apiKey(), settings.chatModel(), payload);
        if (answer.isBlank()) {
            answer = mockAnswer(request);
        }
        return new ChatResponse(answer, timestamp);
    }

    private JsonNode message(String role, String content) {
        return objectMapper.createObjectNode()
                .put("role", role)
                .put("content", content);
    }

    private SuggestionBatch mockSuggestions(SuggestionsRequest request, String timestamp) {
        String latest = request.transcriptEntries().get(request.transcriptEntries().size() - 1).text();
        List<SuggestionCard> cards = List.of(
                new SuggestionCard("question_to_ask", "QUESTION TO ASK", "Ask for the biggest bottleneck behind: " + summarize(latest)),
                new SuggestionCard("talking_point", "TALKING POINT", "Frame the tradeoff, then suggest one concrete next step tied to the latest topic."),
                new SuggestionCard("clarifying_info", "CLARIFYING INFO", "Highlight the assumption hiding inside the recent transcript so the speaker can verify it.")
        );
        return new SuggestionBatch("Batch " + request.batchNumber(), timestamp, cards);
    }

    private SuggestionCard fallbackSuggestion(int index) {
        return switch (index) {
            case 0 -> new SuggestionCard("question_to_ask", "QUESTION TO ASK", "What metric would prove whether this is the real bottleneck?");
            case 1 -> new SuggestionCard("talking_point", "TALKING POINT", "Anchor the discussion in risk, cost, and the next experiment.");
            default -> new SuggestionCard("clarifying_info", "CLARIFYING INFO", "Clarify the unknowns before committing to an implementation path.");
        };
    }

    private List<SuggestionCard> rebalanceSuggestions(List<SuggestionCard> suggestions, List<TranscriptEntry> transcriptEntries) {
        List<SuggestionCard> normalized = new ArrayList<>();
        for (SuggestionCard suggestion : suggestions) {
            String type = normalizeType(suggestion.type());
            normalized.add(new SuggestionCard(type, canonicalTitle(type), suggestion.preview()));
        }

        boolean answerNeeded = needsAnswerSuggestion(transcriptEntries);
        if (answerNeeded && normalized.stream().noneMatch(card -> "answer".equals(card.type()))) {
            String answerPreview = buildAnswerPreview(transcriptEntries);
            int replaceIndex = findReplaceIndex(normalized);
            SuggestionCard answerCard = new SuggestionCard("answer", "ANSWER", answerPreview);
            if (replaceIndex >= 0 && replaceIndex < normalized.size()) {
                normalized.set(replaceIndex, answerCard);
            } else {
                normalized.add(answerCard);
            }
        }

        if (answerNeeded) {
            normalized = ensureSingleAnswer(normalized, transcriptEntries);
            normalized = ensureQuestionCoverage(normalized, transcriptEntries);
            normalized = prioritizeAnswerFirst(normalized);
        }

        normalized = deduplicateTypes(normalized, transcriptEntries, answerNeeded);
        return normalized;
    }

    private int findReplaceIndex(List<SuggestionCard> suggestions) {
        for (int index = suggestions.size() - 1; index >= 0; index--) {
            String type = suggestions.get(index).type();
            if ("question_to_ask".equals(type)) {
                return index;
            }
        }
        for (int index = suggestions.size() - 1; index >= 0; index--) {
            String type = suggestions.get(index).type();
            if ("clarifying_info".equals(type) || "talking_point".equals(type)) {
                return index;
            }
        }
        return suggestions.isEmpty() ? -1 : suggestions.size() - 1;
    }

    private boolean needsAnswerSuggestion(List<TranscriptEntry> transcriptEntries) {
        if (transcriptEntries == null || transcriptEntries.isEmpty()) {
            return false;
        }

        int start = Math.max(0, transcriptEntries.size() - 3);
        for (int index = transcriptEntries.size() - 1; index >= start; index--) {
            String text = transcriptEntries.get(index).text();
            if (isLowSignal(text)) {
                continue;
            }
            if (looksLikeQuestion(text) || asksForRecommendation(text)) {
                return true;
            }
        }
        return false;
    }

    private boolean looksLikeQuestion(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String lower = text.trim().toLowerCase(Locale.ENGLISH);
        if (lower.contains("?")) {
            return true;
        }
        String compact = lower.replaceAll("[^a-z0-9' ]", " ").trim();
        if (compact.isEmpty()) {
            return false;
        }
        String firstWord = compact.split("\\s+")[0];
        return QUESTION_STARTERS.contains(firstWord);
    }

    private boolean asksForRecommendation(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ENGLISH);
        return lower.contains("what should we do")
                || lower.contains("what do you recommend")
                || lower.contains("what's the best")
                || lower.contains("how should")
                || lower.contains("can you suggest")
                || lower.contains("can you explain")
                || lower.contains("if you can explain")
                || lower.contains("what is the cost")
                || lower.contains("what would be the cost")
                || lower.contains("cost range")
                || lower.contains("price range")
                || lower.contains("estimate")
                || lower.contains("how much")
                || lower.contains("architecture")
                || lower.contains("give me")
                || lower.contains("need an answer");
    }

    private String buildAnswerPreview(List<TranscriptEntry> transcriptEntries) {
        String latest = latestMeaningfulTranscript(transcriptEntries);
        String summarized = summarizeQuestion(latest);
        return "Give a direct, concise response about: " + summarized;
    }

    private List<SuggestionCard> prioritizeAnswerFirst(List<SuggestionCard> suggestions) {
        List<SuggestionCard> reordered = new ArrayList<>(suggestions);
        reordered.sort(Comparator.comparingInt(this::suggestionPriority));
        return reordered;
    }

    private int suggestionPriority(SuggestionCard card) {
        return switch (card.type()) {
            case "answer" -> 0;
            case "fact_check" -> 1;
            case "talking_point" -> 2;
            case "clarifying_info" -> 3;
            default -> 4;
        };
    }

    private List<SuggestionCard> ensureSingleAnswer(List<SuggestionCard> suggestions, List<TranscriptEntry> transcriptEntries) {
        List<SuggestionCard> adjusted = new ArrayList<>();
        boolean keptAnswer = false;
        for (SuggestionCard card : suggestions) {
            if ("answer".equals(card.type())) {
                if (!keptAnswer) {
                    adjusted.add(strengthenAnswerCard(card, transcriptEntries));
                    keptAnswer = true;
                } else {
                    adjusted.add(new SuggestionCard(
                            "talking_point",
                            "TALKING POINT",
                            buildTalkingPointPreview(transcriptEntries)
                    ));
                }
            } else {
                adjusted.add(card);
            }
        }

        if (!keptAnswer) {
            adjusted.add(0, new SuggestionCard("answer", "ANSWER", buildAnswerPreview(transcriptEntries)));
        }

        return adjusted;
    }

    private List<SuggestionCard> ensureQuestionCoverage(List<SuggestionCard> suggestions, List<TranscriptEntry> transcriptEntries) {
        boolean hasQuestion = suggestions.stream().anyMatch(card -> "question_to_ask".equals(card.type()));
        if (hasQuestion || !shouldIncludeQuestion(transcriptEntries)) {
            return suggestions;
        }

        List<SuggestionCard> adjusted = new ArrayList<>(suggestions);
        int replaceIndex = findReplacementForQuestion(adjusted);
        SuggestionCard questionCard = new SuggestionCard(
                "question_to_ask",
                "QUESTION TO ASK",
                buildQuestionPreview(transcriptEntries)
        );

        if (replaceIndex >= 0 && replaceIndex < adjusted.size()) {
            adjusted.set(replaceIndex, questionCard);
        } else {
            adjusted.add(questionCard);
        }
        return adjusted;
    }

    private List<SuggestionCard> deduplicateTypes(List<SuggestionCard> suggestions, List<TranscriptEntry> transcriptEntries, boolean answerNeeded) {
        List<SuggestionCard> deduped = new ArrayList<>();
        boolean hasAnswer = false;
        boolean hasQuestion = false;
        boolean hasFact = false;
        boolean hasTalking = false;

        for (SuggestionCard card : suggestions) {
            switch (card.type()) {
                case "answer" -> {
                    if (!hasAnswer) {
                        deduped.add(card);
                        hasAnswer = true;
                    }
                }
                case "question_to_ask" -> {
                    if (!hasQuestion) {
                        deduped.add(card);
                        hasQuestion = true;
                    }
                }
                case "fact_check" -> {
                    if (!hasFact) {
                        deduped.add(card);
                        hasFact = true;
                    }
                }
                case "talking_point" -> {
                    if (!hasTalking) {
                        deduped.add(card);
                        hasTalking = true;
                    }
                }
                default -> deduped.add(card);
            }
        }

        if (answerNeeded && deduped.stream().noneMatch(card -> "answer".equals(card.type()))) {
            deduped.add(0, new SuggestionCard("answer", "ANSWER", buildAnswerPreview(transcriptEntries)));
        }
        if (shouldIncludeQuestion(transcriptEntries) && deduped.stream().noneMatch(card -> "question_to_ask".equals(card.type()))) {
            deduped.add(new SuggestionCard("question_to_ask", "QUESTION TO ASK", buildQuestionPreview(transcriptEntries)));
        }
        if (deduped.stream().noneMatch(card -> "fact_check".equals(card.type()))) {
            deduped.add(new SuggestionCard("fact_check", "FACT-CHECK", buildFactCheckPreview(transcriptEntries)));
        }

        return prioritizeAnswerFirst(deduped);
    }

    private String normalizeType(String rawType) {
        if (!StringUtils.hasText(rawType)) {
            return "clarifying_info";
        }
        String normalized = rawType.trim().toLowerCase(Locale.ENGLISH)
                .replace('-', '_')
                .replace(' ', '_');
        return switch (normalized) {
            case "question", "question_to_ask", "questiontoask" -> "question_to_ask";
            case "talkingpoint", "talking_point", "point" -> "talking_point";
            case "factcheck", "fact_check", "fact" -> "fact_check";
            case "answer", "response" -> "answer";
            case "clarifyinginfo", "clarifying_info", "clarification" -> "clarifying_info";
            default -> "clarifying_info";
        };
    }

    private String canonicalTitle(String type) {
        return switch (type) {
            case "question_to_ask" -> "QUESTION TO ASK";
            case "talking_point" -> "TALKING POINT";
            case "fact_check" -> "FACT-CHECK";
            case "answer" -> "ANSWER";
            default -> "CLARIFYING INFO";
        };
    }

    private String mockAnswer(ChatRequest request) {
        return """
                Here's a ready-to-use response you can say next:

                "%s"

                - Tie it back to the most recent transcript point so it lands naturally.
                - Add one concrete metric, dependency, or risk to make the answer stronger.
                - If the room is uncertain, end with a crisp next-step question.
                """.formatted(request.userMessage());
    }

    private String trimTranscript(List<TranscriptEntry> entries, int maxChars) {
        if (entries == null || entries.isEmpty()) {
            return "(no transcript captured yet)";
        }
        StringBuilder builder = new StringBuilder();
        for (int index = entries.size() - 1; index >= 0; index--) {
            TranscriptEntry entry = entries.get(index);
            String line = "[" + entry.timestamp() + "] " + entry.text() + "\n";
            if (builder.length() + line.length() > maxChars) {
                break;
            }
            builder.insert(0, line);
        }
        return builder.toString();
    }

    private String buildSuggestionTranscriptContext(List<TranscriptEntry> entries, int maxChars) {
        if (entries == null || entries.isEmpty()) {
            return "(no transcript captured yet)";
        }

        List<TranscriptEntry> meaningful = new ArrayList<>();
        for (TranscriptEntry entry : entries) {
            if (!isLowSignal(entry.text())) {
                meaningful.add(entry);
            }
        }

        if (meaningful.isEmpty()) {
            return trimTranscript(entries, maxChars);
        }

        int recentCount = Math.min(4, meaningful.size());
        int backgroundCount = Math.min(2, Math.max(0, meaningful.size() - recentCount));

        List<TranscriptEntry> recent = meaningful.subList(meaningful.size() - recentCount, meaningful.size());
        List<TranscriptEntry> background = meaningful.subList(
                Math.max(0, meaningful.size() - recentCount - backgroundCount),
                meaningful.size() - recentCount
        );

        StringBuilder builder = new StringBuilder();
        builder.append("Recent focus (highest priority):\n");
        for (TranscriptEntry entry : recent) {
            builder.append("[").append(entry.timestamp()).append("] ").append(entry.text()).append('\n');
        }

        if (!background.isEmpty()) {
            builder.append("\nOlder context (lower priority, use only if still relevant):\n");
            for (TranscriptEntry entry : background) {
                builder.append("[").append(entry.timestamp()).append("] ").append(entry.text()).append('\n');
            }
        }

        String context = builder.toString();
        return context.length() <= maxChars ? context : context.substring(Math.max(0, context.length() - maxChars));
    }

    private String summarize(String latest) {
        return latest.length() > 75 ? latest.substring(0, 72) + "..." : latest;
    }

    private String summarizeQuestion(String latest) {
        String normalized = latest == null ? "" : latest.trim();
        if (normalized.isEmpty()) {
            return "provide a concise response the speaker can say immediately.";
        }
        if (normalized.length() > 120) {
            normalized = normalized.substring(0, 117) + "...";
        }
        return normalized;
    }

    private SuggestionCard strengthenAnswerCard(SuggestionCard card, List<TranscriptEntry> transcriptEntries) {
        if (!StringUtils.hasText(card.preview()) || card.preview().toLowerCase(Locale.ENGLISH).startsWith("direct answer to the latest question")) {
            return new SuggestionCard("answer", "ANSWER", buildAnswerPreview(transcriptEntries));
        }
        return new SuggestionCard("answer", "ANSWER", card.preview());
    }

    private boolean shouldIncludeQuestion(List<TranscriptEntry> transcriptEntries) {
        String latest = latestMeaningfulTranscript(transcriptEntries).toLowerCase(Locale.ENGLISH);
        return !latest.isBlank() && !isLowSignal(latest);
    }

    private int findReplacementForQuestion(List<SuggestionCard> suggestions) {
        for (int index = suggestions.size() - 1; index >= 0; index--) {
            String type = suggestions.get(index).type();
            if ("clarifying_info".equals(type) || "talking_point".equals(type)) {
                return index;
            }
        }
        for (int index = suggestions.size() - 1; index >= 0; index--) {
            if (!"answer".equals(suggestions.get(index).type())) {
                return index;
            }
        }
        return -1;
    }

    private String buildQuestionPreview(List<TranscriptEntry> transcriptEntries) {
        String latest = latestMeaningfulTranscript(transcriptEntries);
        String lower = latest.toLowerCase(Locale.ENGLISH);
        if (lower.contains("cost") || lower.contains("price") || lower.contains("estimate") || lower.contains("how much")) {
            return "What model size, token volume, and hosting approach should we assume so the cost estimate is realistic?";
        }
        if (lower.contains("architecture") || lower.contains("how does") || lower.contains("explain")) {
            return "Which part should we go one level deeper on next: architecture, training flow, or deployment cost?";
        }
        return "What is the one follow-up question that would make the speaker's next answer more concrete?";
    }

    private String buildTalkingPointPreview(List<TranscriptEntry> transcriptEntries) {
        String latest = latestMeaningfulTranscript(transcriptEntries).toLowerCase(Locale.ENGLISH);
        if (latest.contains("cost") || latest.contains("price") || latest.contains("how much")) {
            return "Frame the estimate around three buckets: training cost, monthly inference cost, and the biggest drivers that change the number.";
        }
        return "Anchor the response in the most practical next point the speaker can make immediately.";
    }

    private String buildFactCheckPreview(List<TranscriptEntry> transcriptEntries) {
        String latest = latestMeaningfulTranscript(transcriptEntries).toLowerCase(Locale.ENGLISH);
        if (latest.contains("cost") || latest.contains("price") || latest.contains("token")) {
            return "Validate the assumed token volume, GPU pricing, and whether the estimate is for training, inference, or both.";
        }
        return "Validate the key technical claim or assumption before the speaker commits to it.";
    }

    private String latestMeaningfulTranscript(List<TranscriptEntry> transcriptEntries) {
        if (transcriptEntries == null || transcriptEntries.isEmpty()) {
            return "";
        }
        for (int index = transcriptEntries.size() - 1; index >= 0; index--) {
            String text = transcriptEntries.get(index).text();
            if (!isLowSignal(text)) {
                return text == null ? "" : text.trim();
            }
        }
        return transcriptEntries.get(transcriptEntries.size() - 1).text();
    }

    private boolean isLowSignal(String text) {
        if (!StringUtils.hasText(text)) {
            return true;
        }
        String lower = text.trim().toLowerCase(Locale.ENGLISH);
        return lower.equals("thank you")
                || lower.equals("thanks")
                || lower.equals("okay")
                || lower.equals("ok")
                || lower.equals("gracias.")
                || lower.equals("gracias")
                || lower.equals("дякую.")
                || lower.equals("дякую");
    }

    private String normalizeTranscriptText(String transcript) {
        if (!StringUtils.hasText(transcript)) {
            return "";
        }

        String normalized = transcript.trim();
        for (Map.Entry<Pattern, String> entry : TRANSCRIPTION_CORRECTIONS.entrySet()) {
            normalized = entry.getKey().matcher(normalized).replaceAll(entry.getValue());
        }
        return normalized;
    }

    private String refineTranscriptWithAi(String apiKey, String transcript) {
        if (!StringUtils.hasText(apiKey) || !StringUtils.hasText(transcript) || transcript.length() < 8) {
            return transcript;
        }

        try {
            var payload = objectMapper.createObjectNode()
                    .put("model", appProperties.chatModel())
                    .put("temperature", 0.0);
            payload.putArray("messages")
                    .add(message("system", TRANSCRIPT_CLEANUP_PROMPT))
                    .add(message("user", transcript));

            String cleaned = groqClient.chatCompletion(apiKey, appProperties.chatModel(), payload);
            if (!StringUtils.hasText(cleaned)) {
                return transcript;
            }

            return stripTranscriptCleanupWrapper(cleaned);
        } catch (Exception ignored) {
            return transcript;
        }
    }

    private String stripTranscriptCleanupWrapper(String cleaned) {
        String normalized = cleaned.trim()
                .replace("\r", "")
                .replaceAll("^```[a-zA-Z]*\\s*", "")
                .replaceAll("\\s*```$", "")
                .trim();

        if ((normalized.startsWith("\"") && normalized.endsWith("\""))
                || (normalized.startsWith("'") && normalized.endsWith("'"))) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }

        return normalized;
    }

    private static Map<Pattern, String> buildTranscriptionCorrections() {
        Map<Pattern, String> corrections = new LinkedHashMap<>();
        corrections.put(pattern("\\b(?:chat|charge|chad|chart)\\s*g\\s*p\\s*(?:t|d|b)\\b"), "ChatGPT");
        corrections.put(pattern("\\b(?:open\\s*ai|open a i|openayi)\\b"), "OpenAI");
        corrections.put(pattern("\\b(?:gimini|jimini)\\b"), "Gemini");
        corrections.put(pattern("\\bwhats\\s*app\\b"), "WhatsApp");
        corrections.put(pattern("\\b(?:co pilot|copilot)\\b"), "Copilot");
        return corrections;
    }

    private static Pattern pattern(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }

    private String nowStamp() {
        return OffsetDateTime.now().format(TIME_FORMATTER);
    }
}
