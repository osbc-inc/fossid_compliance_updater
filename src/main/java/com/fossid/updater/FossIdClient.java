package com.fossid.updater;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class FossIdClient {

    private final String apiUrl;
    private final String username;
    private final String apiKey;
    private final Gson gson;

    public FossIdClient(String address, String username, String apiKey) {
        String base = address.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/api.php")) {
            this.apiUrl = base;
        } else {
            this.apiUrl = base + "/api.php";
        }
        this.username = username;
        this.apiKey = apiKey;
        this.gson = new GsonBuilder().create();
    }

    /**
     * Calls FossID get_dependency_analysis_results and returns the list of components.
     */
    public List<DependencyComponent> getDependencyAnalysisResults(String scanCode) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("group", "scans");
        payload.addProperty("action", "get_dependency_analysis_results");

        JsonObject data = new JsonObject();
        data.addProperty("username", username);
        data.addProperty("key", apiKey);
        data.addProperty("scan_code", scanCode);
        payload.add("data", data);

        String responseBody = post(payload.toString());

        JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
        String status = root.get("status").getAsString();
        if (!"1".equals(status) && !"ok".equals(status)) {
            throw new RuntimeException("FossID API error (get_dependency_analysis_results): status=" + status);
        }

        JsonElement dataEl = root.get("data");
        if (dataEl == null || dataEl.isJsonNull()) {
            return Collections.emptyList();
        }

        List<DependencyComponent> components = new ArrayList<>();
        // data can be a JsonArray or a JsonObject (keyed by component IDs)
        if (dataEl.isJsonArray()) {
            JsonArray arr = dataEl.getAsJsonArray();
            for (JsonElement el : arr) {
                DependencyComponent comp = gson.fromJson(el, DependencyComponent.class);
                components.add(comp);
            }
        } else if (dataEl.isJsonObject()) {
            JsonObject obj = dataEl.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                DependencyComponent comp = gson.fromJson(entry.getValue(), DependencyComponent.class);
                components.add(comp);
            }
        }
        return components;
    }

    /**
     * Calls FossID components/update to patch a component's license_identifier and/or url.
     */
    public void updateComponent(String name, String version, String licenseIdentifier, String url) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("group", "components");
        payload.addProperty("action", "update");

        JsonObject data = new JsonObject();
        data.addProperty("username", username);
        data.addProperty("key", apiKey);
        data.addProperty("name", name);
        data.addProperty("version", version);
        if (licenseIdentifier != null && !licenseIdentifier.trim().isEmpty()) {
            data.addProperty("license_identifier", licenseIdentifier);
        }
        if (url != null && !url.trim().isEmpty()) {
            data.addProperty("url", url);
        }
        payload.add("data", data);

        String responseBody = post(payload.toString());

        JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
        String status = root.get("status").getAsString();
        if (!"1".equals(status) && !"ok".equals(status)) {
            System.err.println("[WARN] Update component failed for " + name + ":" + version + " status=" + status);
        }
    }

    /**
     * Calls FossID get_scan_identified_components and returns a list of identified components.
     */
    public List<IdentifiedComponent> getScanIdentifiedComponents(String scanCode) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("group", "scans");
        payload.addProperty("action", "get_scan_identified_components");

        JsonObject data = new JsonObject();
        data.addProperty("username", username);
        data.addProperty("key", apiKey);
        data.addProperty("scan_code", scanCode);
        payload.add("data", data);

        String responseBody = post(payload.toString());
        JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
        String status = root.get("status").getAsString();
        if (!"1".equals(status) && !"ok".equals(status)) {
            throw new RuntimeException("FossID API error (get_scan_identified_components): status=" + status);
        }

        List<IdentifiedComponent> result = new ArrayList<>();
        JsonElement dataEl = root.get("data");
        if (dataEl == null || dataEl.isJsonNull()) {
            return result;
        }
        // data is a JsonObject keyed by component id
        JsonObject dataObj = dataEl.getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : dataObj.entrySet()) {
            JsonObject item = entry.getValue().getAsJsonObject();
            IdentifiedComponent comp = new IdentifiedComponent();
            comp.id = item.has("id") && !item.get("id").isJsonNull() ? item.get("id").getAsInt() : 0;
            comp.name = item.has("name") && !item.get("name").isJsonNull() ? item.get("name").getAsString() : null;
            comp.version = item.has("version") && !item.get("version").isJsonNull() ? item.get("version").getAsString() : null;
            comp.url = item.has("url") && !item.get("url").isJsonNull() ? item.get("url").getAsString() : null;
            comp.licenseIdentifier = item.has("license_identifier") && !item.get("license_identifier").isJsonNull() ? item.get("license_identifier").getAsString() : null;
            comp.licenseName = item.has("license_name") && !item.get("license_name").isJsonNull() ? item.get("license_name").getAsString() : null;
            result.add(comp);
        }
        return result;
    }

    /**
     * Calls FossID components/get_information and returns detailed component info including copyright.
     */
    public ComponentInfo getComponentInformation(String name, String version) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("group", "components");
        payload.addProperty("action", "get_information");

        JsonObject data = new JsonObject();
        data.addProperty("username", username);
        data.addProperty("key", apiKey);
        data.addProperty("component_name", name);
        data.addProperty("component_version", version);
        payload.add("data", data);

        String responseBody = post(payload.toString());
        JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
        String status = root.get("status").getAsString();
        if (!"1".equals(status) && !"ok".equals(status)) {
            System.err.println("[WARN] get_information failed for " + name + ":" + version + " status=" + status);
            return null;
        }

        JsonElement dataEl = root.get("data");
        if (dataEl == null || dataEl.isJsonNull()) {
            return null;
        }
        JsonObject item = dataEl.getAsJsonObject();
        ComponentInfo info = new ComponentInfo();
        info.id = item.has("id") && !item.get("id").isJsonNull() ? item.get("id").getAsInt() : 0;
        info.name = item.has("name") && !item.get("name").isJsonNull() ? item.get("name").getAsString() : null;
        info.version = item.has("version") && !item.get("version").isJsonNull() ? item.get("version").getAsString() : null;
        info.url = item.has("url") && !item.get("url").isJsonNull() ? item.get("url").getAsString() : null;
        info.copyright = item.has("copyright") && !item.get("copyright").isJsonNull() ? item.get("copyright").getAsString() : null;
        info.licenseIdentifier = item.has("license_identifier") && !item.get("license_identifier").isJsonNull() ? item.get("license_identifier").getAsString() : null;
        info.licenseName = item.has("license_name") && !item.get("license_name").isJsonNull() ? item.get("license_name").getAsString() : null;
        return info;
    }

    /**
     * Calls FossID components/update to patch a component's copyright.
     */
    public void updateComponentCopyright(String name, String version, String copyright) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("group", "components");
        payload.addProperty("action", "update");

        JsonObject data = new JsonObject();
        data.addProperty("username", username);
        data.addProperty("key", apiKey);
        data.addProperty("name", name);
        data.addProperty("version", version);
        data.addProperty("copyright", copyright);
        payload.add("data", data);

        String responseBody = post(payload.toString());
        JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
        String status = root.get("status").getAsString();
        if (!"1".equals(status) && !"ok".equals(status)) {
            System.err.println("[WARN] updateComponentCopyright failed for " + name + ":" + version + " status=" + status);
        }
    }

    private String post(String jsonBody) throws Exception {
        URL urlObj = new URL(apiUrl);
        HttpURLConnection conn = (HttpURLConnection) urlObj.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonBody.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int statusCode = conn.getResponseCode();
            InputStream is = (statusCode >= 200 && statusCode < 300) ? conn.getInputStream() : conn.getErrorStream();
            String body = readStream(is);

            if (statusCode < 200 || statusCode >= 300) {
                throw new RuntimeException("HTTP error from FossID (" + conn.getURL() + "): " + statusCode + " " + body);
            }
            return body;
        } finally {
            conn.disconnect();
        }
    }

    private String readStream(InputStream is) throws IOException {
        if (is == null) return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }
}
