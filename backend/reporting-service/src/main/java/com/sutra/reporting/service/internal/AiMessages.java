package com.sutra.reporting.service.internal;

import java.util.LinkedHashMap;
import java.util.Map;

/** Builds the chat messages and the strict JSON-schema response format sent to the AI through the extraction service. */
public final class AiMessages {

    private AiMessages() {
    }

    public static Map<String, Object> message(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    public static Map<String, Object> jsonSchema(String name, Map<String, Object> schema) {
        Map<String, Object> jsonSchema = new LinkedHashMap<>();
        jsonSchema.put("name", name);
        jsonSchema.put("strict", true);
        jsonSchema.put("schema", schema);
        Map<String, Object> format = new LinkedHashMap<>();
        format.put("type", "json_schema");
        format.put("json_schema", jsonSchema);
        return format;
    }
}
