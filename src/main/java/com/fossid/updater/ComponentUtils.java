package com.fossid.updater;

import com.google.gson.*;
import java.util.Map;

public class ComponentUtils {

    private ComponentUtils() {}

    public static boolean isBlankOrNA(String value) {
        if (value == null) return true;
        String trimmed = value.trim();
        return trimmed.isEmpty()
                || "null".equalsIgnoreCase(trimmed)
                || "N/A".equalsIgnoreCase(trimmed);
    }

    public static String extractPackageManager(String detailedDependencyInfo) {
        if (isBlankOrNA(detailedDependencyInfo)) return null;
        try {
            JsonObject obj = JsonParser.parseString(detailedDependencyInfo).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                JsonElement val = entry.getValue();
                if (val.isJsonObject()) {
                    JsonObject inner = val.getAsJsonObject();
                    if (inner.has("project_id")) {
                        String projectId = inner.get("project_id").getAsString();
                        int colonIdx = projectId.indexOf(':');
                        return colonIdx > 0 ? projectId.substring(0, colonIdx) : projectId;
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }
}
