package com.spectrumforge;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class GeminiClient {
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build();

    Map<String, Object> generate(GenerateRequest request, AppConfig config) {
        if (!config.configured()) {
            throw new AppException(503, "Generation is not available right now.");
        }

        AppException lastError = null;

        for (String model : candidateModels(config)) {
            for (int attempt = 1; attempt <= 2; attempt++) {
                try {
                    Map<String, Object> response = requestContent(config, buildPayload(request), model);
                    String content = extractContent(response);
                    Object parsedContent = parseModelJson(content);
                    Map<String, Object> result = MiniJson.asObject(parsedContent, "The model returned an invalid JSON object.");
                    return normalize(result, request);
                } catch (AppException error) {
                    lastError = error;
                    if (!shouldRetry(error, attempt)) {
                        throw error;
                    }
                    pauseBeforeRetry(attempt);
                }
            }
        }

        throw lastError == null
            ? new AppException(502, "Unable to generate a solution right now.")
            : new AppException(lastError.statusCode(), "Unable to generate a solution right now.");
    }

    private Map<String, Object> buildPayload(GenerateRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("systemInstruction", Map.of(
            "parts", List.of(Map.of("text", systemPrompt()))
        ));
        payload.put("contents", List.of(Map.of(
            "role", "user",
            "parts", List.of(Map.of("text", userPrompt(request)))
        )));
        payload.put("generationConfig", Map.of(
            "temperature", 0.05,
            "maxOutputTokens", 6000,
            "thinkingConfig", Map.of("thinkingBudget", 0),
            "responseMimeType", "application/json",
            "responseJsonSchema", responseSchema()
        ));
        return payload;
    }

    private String systemPrompt() {
        return """
            You solve coding problems for competitive programming and interviews.
            Choose the algorithm from the constraints first.
            Return exactly one valid JSON object and nothing else.
            Fill every field in the schema. Use empty arrays only when truly necessary.
            Never place markdown fences inside code.
            Unless solutionMode is logic_only, the code field must contain a complete runnable solution.
            Write code that feels like a skilled human wrote it during real problem solving.
            Avoid stock AI phrasing, tutorial-style narration, and generic textbook comments.
            Prefer problem-specific naming over vague names when it keeps the code readable.
            Keep comments sparse and useful, not decorative.
            Avoid oversized templates and unnecessary helper classes.
            Do not explain the solution inside the code except for short practical comments when needed.
            Keep every string concise.
            Never include self-corrections, re-checks, or repeated reasoning.
            Keep tests short and practical.
            Keep the answer practical, correct, and concise.
            """;
    }

    private String userPrompt(GenerateRequest request) {
        return """
            Problem statement:
            %s

            Constraints:
            %s

            Examples:
            %s

            Primary language: %s
            Additional languages: %s
            Solution mode: %s
            Interface style: %s
            Explanation depth: %s

            Additional requirements:
            %s

            Output rules:
            - Restate the task precisely.
            - Explain why the chosen complexity fits the constraints.
            - Produce clean complete code.
            - Include at most 3 focused tests.
            - Keep each test reason short.
            - Do not leave placeholders.
            - Use short bullet items instead of long paragraphs.
            - Make the code look natural and problem-specific, not like a copied reference template.
            - Do not use obvious AI-style comments or repetitive naming patterns.
            """.formatted(
            nonEmptyOr(request.problemStatement(), "Not provided"),
            nonEmptyOr(request.constraints(), "Not provided"),
            nonEmptyOr(request.examples(), "Not provided"),
            nonEmptyOr(request.primaryLanguage(), "Not provided"),
            request.alternateLanguageList().isEmpty() ? "None" : String.join(", ", request.alternateLanguageList()),
            nonEmptyOr(request.solutionMode(), "logic_and_code"),
            nonEmptyOr(request.interfaceStyle(), "competitive_programming"),
            nonEmptyOr(request.explanationDepth(), "balanced"),
            nonEmptyOr(request.additionalRequirements(), "None")
        );
    }

    private Map<String, Object> responseSchema() {
        List<String> topLevelOrder = List.of(
            "title",
            "restatedProblem",
            "assumptions",
            "coreIdea",
            "algorithmSteps",
            "constraintFit",
            "complexity",
            "edgeCases",
            "pseudocode",
            "code",
            "alternateImplementations",
            "tests",
            "notes"
        );

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("title", stringSchema());
        properties.put("restatedProblem", stringSchema());
        properties.put("assumptions", boundedStringArraySchema(4));
        properties.put("coreIdea", stringSchema());
        properties.put("algorithmSteps", boundedStringArraySchema(6));
        properties.put("constraintFit", boundedStringArraySchema(4));
        properties.put("complexity", Map.of(
            "type", "object",
            "properties", Map.of(
                "time", stringSchema(),
                "space", stringSchema()
            ),
            "required", List.of("time", "space"),
            "propertyOrdering", List.of("time", "space")
        ));
        properties.put("edgeCases", boundedStringArraySchema(5));
        properties.put("pseudocode", boundedStringArraySchema(8));
        properties.put("code", stringSchema());
        properties.put("alternateImplementations", Map.of(
            "type", "array",
            "maxItems", 3,
            "items", Map.of(
                "type", "object",
                "properties", Map.of(
                    "language", stringSchema(),
                    "code", stringSchema()
                ),
                "required", List.of("language", "code"),
                "propertyOrdering", List.of("language", "code")
            )
        ));
        properties.put("tests", Map.of(
            "type", "array",
            "maxItems", 3,
            "items", Map.of(
                "type", "object",
                "properties", Map.of(
                    "input", stringSchema(),
                    "expectedOutput", stringSchema(),
                    "reason", stringSchema()
                ),
                "required", List.of("input", "expectedOutput", "reason"),
                "propertyOrdering", List.of("input", "expectedOutput", "reason")
            )
        ));
        properties.put("notes", boundedStringArraySchema(3));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", topLevelOrder);
        schema.put("propertyOrdering", topLevelOrder);
        return schema;
    }

    private Map<String, Object> stringSchema() {
        return Map.of("type", "string");
    }

    private Map<String, Object> stringArraySchema() {
        return Map.of(
            "type", "array",
            "items", stringSchema()
        );
    }

    private Map<String, Object> boundedStringArraySchema(int maxItems) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("maxItems", maxItems);
        schema.put("items", stringSchema());
        return schema;
    }

    private Map<String, Object> requestContent(AppConfig config, Map<String, Object> payload, String model) {
        ApiResponse response = postJson(config, payload, model);
        if (response.ok()) {
            return MiniJson.asObject(response.data(), "Model provider returned invalid JSON.");
        }
        throw new AppException(response.statusCode(), extractError(response));
    }

    private ApiResponse postJson(AppConfig config, Map<String, Object> payload, String model) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint(config, model)))
                .timeout(Duration.ofSeconds(30))
                .header("x-goog-api-key", config.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MiniJson.stringify(payload)))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            Object parsedBody;
            try {
                parsedBody = response.body().isBlank() ? Map.of() : MiniJson.parse(response.body());
            } catch (RuntimeException error) {
                parsedBody = Map.of("raw", response.body());
            }
            return new ApiResponse(response.statusCode(), parsedBody);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AppException(502, "Unable to generate a solution right now.");
        } catch (IOException error) {
            throw new AppException(502, "Unable to generate a solution right now.");
        }
    }

    private String endpoint(AppConfig config, String model) {
        String baseUrl = config.baseUrl().replaceAll("/+$", "");
        String selectedModel = model == null || model.isBlank() ? config.model() : model.trim();
        String modelPath = selectedModel.startsWith("models/") ? selectedModel : "models/" + selectedModel;
        return baseUrl + "/" + modelPath + ":generateContent";
    }

    private List<String> candidateModels(AppConfig config) {
        Set<String> models = new LinkedHashSet<>();
        models.add(config.model());
        models.add("gemini-2.5-flash-lite");
        models.add("gemini-2.5-flash");
        return new ArrayList<>(models);
    }

    private boolean shouldRetry(AppException error, int attempt) {
        if (attempt >= 2) {
            return false;
        }

        int status = error.statusCode();
        return status == 408 || status == 429 || status >= 500;
    }

    private void pauseBeforeRetry(int attempt) {
        try {
            Thread.sleep(220L * attempt);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AppException(502, "Unable to generate a solution right now.");
        }
    }

    private String extractContent(Map<String, Object> response) {
        List<Object> candidates = MiniJson.asList(response.get("candidates"));
        if (candidates.isEmpty()) {
            Map<String, Object> promptFeedback = response.get("promptFeedback") instanceof Map<?, ?>
                ? MiniJson.asObject(response.get("promptFeedback"), "Unable to generate a solution right now.")
                : Map.of();
            String blockReason = stringValue(promptFeedback.get("blockReason"), "");
            if (!blockReason.isBlank()) {
                throw new AppException(422, "The prompt could not be processed right now.");
            }
            throw new AppException(502, "Unable to generate a solution right now.");
        }

        Map<String, Object> candidate = MiniJson.asObject(candidates.get(0), "Unable to generate a solution right now.");
        Map<String, Object> content = candidate.get("content") instanceof Map<?, ?>
            ? MiniJson.asObject(candidate.get("content"), "Unable to generate a solution right now.")
            : Map.of();
        List<Object> parts = MiniJson.asList(content.get("parts"));
        StringBuilder builder = new StringBuilder();

        for (Object entry : parts) {
            if (!(entry instanceof Map<?, ?>)) {
                continue;
            }
            Map<String, Object> part = MiniJson.asObject(entry, "Unable to generate a solution right now.");
            String text = stringValue(part.get("text"), "");
            if (!text.isBlank()) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(text);
            }
        }

        if (builder.length() == 0) {
            throw new AppException(502, "Unable to generate a solution right now.");
        }

        return builder.toString();
    }

    private Object parseModelJson(String content) {
        String trimmed = content.trim();
        try {
            return MiniJson.parse(trimmed);
        } catch (RuntimeException ignored) {
            int fenceStart = trimmed.indexOf("```");
            int fenceEnd = trimmed.lastIndexOf("```");
            if (fenceStart >= 0 && fenceEnd > fenceStart) {
                String fenced = trimmed.substring(fenceStart + 3, fenceEnd).trim();
                if (fenced.startsWith("json")) {
                    fenced = fenced.substring(4).trim();
                }
                return MiniJson.parse(fenced);
            }
            throw new AppException(502, "Unable to generate a solution right now.");
        }
    }

    private Map<String, Object> normalize(Map<String, Object> raw, GenerateRequest request) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("title", stringValue(raw.get("title"), "Generated solution"));
        response.put("restatedProblem", stringValue(raw.get("restatedProblem"), ""));
        response.put("assumptions", stringList(raw.get("assumptions")));
        response.put("coreIdea", stringValue(raw.get("coreIdea"), ""));
        response.put("algorithmSteps", stringList(raw.get("algorithmSteps")));
        response.put("constraintFit", stringList(raw.get("constraintFit")));
        response.put("complexity", normalizeComplexity(raw.get("complexity")));
        response.put("edgeCases", stringList(raw.get("edgeCases")));
        response.put("pseudocode", stringList(raw.get("pseudocode")));
        response.put("code", stringValue(raw.get("code"), ""));
        response.put("primaryLanguage", request.primaryLanguage());
        response.put("alternateImplementations", normalizeAlternateImplementations(raw.get("alternateImplementations"), request.primaryLanguage()));
        response.put("tests", normalizeTests(raw.get("tests")));
        response.put("notes", stringList(raw.get("notes")));
        validateResult(response, request);
        return response;
    }

    private void validateResult(Map<String, Object> response, GenerateRequest request) {
        String coreIdea = stringValue(response.get("coreIdea"), "");
        String code = stringValue(response.get("code"), "");
        Map<String, Object> complexity = response.get("complexity") instanceof Map<?, ?>
            ? MiniJson.asObject(response.get("complexity"), "Invalid complexity block.")
            : Map.of();
        String time = stringValue(complexity.get("time"), "");
        String space = stringValue(complexity.get("space"), "");
        boolean logicOnly = "logic_only".equalsIgnoreCase(request.solutionMode());

        if (coreIdea.isBlank() || time.isBlank() || space.isBlank()) {
            throw new AppException(502, "The generated answer was incomplete. Try again.");
        }

        if (!logicOnly && code.isBlank()) {
            throw new AppException(502, "The generated answer did not include code. Try again.");
        }
    }

    private Map<String, Object> normalizeComplexity(Object value) {
        Map<String, Object> source = value instanceof Map<?, ?> ? MiniJson.asObject(value, "Invalid complexity block.") : Map.of();
        Map<String, Object> complexity = new LinkedHashMap<>();
        complexity.put("time", stringValue(source.get("time"), ""));
        complexity.put("space", stringValue(source.get("space"), ""));
        return complexity;
    }

    private List<Object> normalizeAlternateImplementations(Object value, String primaryLanguage) {
        List<Object> source = MiniJson.asList(value);
        List<Object> items = new ArrayList<>();
        List<String> seen = new ArrayList<>();

        for (Object entry : source) {
            if (!(entry instanceof Map<?, ?>)) {
                continue;
            }

            Map<String, Object> item = MiniJson.asObject(entry, "Invalid alternate implementation.");
            String language = stringValue(item.get("language"), "");
            String code = stringValue(item.get("code"), "");

            if (language.isBlank() || code.isBlank()) {
                continue;
            }

            String key = language.toLowerCase();
            if (key.equals(primaryLanguage.toLowerCase()) || seen.contains(key)) {
                continue;
            }

            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("language", language);
            normalized.put("code", code);
            items.add(normalized);
            seen.add(key);
        }

        return items;
    }

    private List<Object> normalizeTests(Object value) {
        List<Object> source = MiniJson.asList(value);
        List<Object> tests = new ArrayList<>();

        for (Object entry : source) {
            if (!(entry instanceof Map<?, ?>)) {
                continue;
            }

            Map<String, Object> test = MiniJson.asObject(entry, "Invalid test case.");
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("input", stringValue(test.get("input"), ""));
            normalized.put("expectedOutput", stringValue(test.get("expectedOutput"), ""));
            normalized.put("reason", stringValue(test.get("reason"), ""));
            tests.add(normalized);
        }

        return tests;
    }

    private List<Object> stringList(Object value) {
        List<Object> source = MiniJson.asList(value);
        List<Object> items = new ArrayList<>();

        for (Object entry : source) {
            String text = stringValue(entry, "");
            if (!text.isBlank()) {
                items.add(text);
            }
        }

        return items;
    }

    private String stringValue(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }

    private String nonEmptyOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String extractError(ApiResponse response) {
        Object data = response.data();
        if (data instanceof Map<?, ?> map) {
            Map<String, Object> payload = MiniJson.asObject(map, "Provider error.");
            Object error = payload.get("error");
            if (error instanceof Map<?, ?>) {
                Map<String, Object> errorMap = MiniJson.asObject(error, "Provider error.");
                String message = stringValue(errorMap.get("message"), "");
                if (!message.isBlank()) {
                    return "Unable to generate a solution right now.";
                }
            }
        }
        return "Unable to generate a solution right now.";
    }

    private record ApiResponse(int statusCode, Object data) {
        boolean ok() {
            return statusCode >= 200 && statusCode < 300;
        }
    }
}
