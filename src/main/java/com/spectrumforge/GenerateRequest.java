package com.spectrumforge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

record GenerateRequest(
    String problemStatement,
    String constraints,
    String examples,
    String primaryLanguage,
    String alternateLanguages,
    String solutionMode,
    String interfaceStyle,
    String explanationDepth,
    String additionalRequirements
) {
    static GenerateRequest from(Map<String, Object> body) {
        return new GenerateRequest(
            readString(body, "problemStatement"),
            readString(body, "constraints"),
            readString(body, "examples"),
            readString(body, "primaryLanguage"),
            readString(body, "alternateLanguages"),
            readString(body, "solutionMode"),
            readString(body, "interfaceStyle"),
            readString(body, "explanationDepth"),
            readString(body, "additionalRequirements")
        );
    }

    void validate() {
        if (problemStatement.isBlank()) {
            throw new AppException(400, "Problem statement is required.");
        }
        if (primaryLanguage.isBlank()) {
            throw new AppException(400, "Primary language is required.");
        }
    }

    List<String> alternateLanguageList() {
        List<String> items = new ArrayList<>();
        for (String entry : alternateLanguages.split(",")) {
            String value = entry.trim();
            if (!value.isEmpty()) {
                items.add(value);
            }
            if (items.size() == 3) {
                break;
            }
        }
        return items;
    }

    Map<String, Object> toMap() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("problemStatement", problemStatement);
        payload.put("constraints", constraints);
        payload.put("examples", examples);
        payload.put("primaryLanguage", primaryLanguage);
        payload.put("alternateLanguages", alternateLanguages);
        payload.put("solutionMode", solutionMode);
        payload.put("interfaceStyle", interfaceStyle);
        payload.put("explanationDepth", explanationDepth);
        payload.put("additionalRequirements", additionalRequirements);
        return payload;
    }

    private static String readString(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }
}
