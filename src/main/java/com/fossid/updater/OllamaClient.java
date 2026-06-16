package com.fossid.updater;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class OllamaClient implements AiClient {

    private final String apiUrl;
    private final String model;
    private final String token;

    public OllamaClient(String address, String model, String token) {
        String base = address.trim();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        this.apiUrl = base.endsWith("/api/generate") ? base : base + "/api/generate";
        this.model = model;
        this.token = token;
    }

    @Override
    public AiResult queryPackageInfo(String name, String version, String packageManager) throws Exception {
        String responseBody = post(buildJsonRequest(PromptBuilder.buildPackageInfoPrompt(name, version, packageManager)));
        return parsePackageInfoResponse(responseBody);
    }

    @Override
    public AiResult queryCopyright(String name, String version, String url,
                                   String licenseIdentifier, String licenseName,
                                   String packageManager) throws Exception {
        String responseBody = post(buildJsonRequest(
                PromptBuilder.buildCopyrightPrompt(name, version, url, licenseIdentifier, licenseName, packageManager)));
        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            if (!root.has("response") && !root.has("thinking")) return new AiResult(null, null, null);

            String text = extractText(root);
            JsonObject parsed = JsonParser.parseString(text).getAsJsonObject();

            String cr = null;
            if (parsed.has("copyright") && !parsed.get("copyright").isJsonNull())
                cr = parsed.get("copyright").getAsString();
            else if (parsed.has("copyright_notice") && !parsed.get("copyright_notice").isJsonNull())
                cr = parsed.get("copyright_notice").getAsString();
            if (cr != null && (cr.trim().isEmpty() || "null".equalsIgnoreCase(cr))) cr = null;

            String newUrl = null;
            if (parsed.has("url") && !parsed.get("url").isJsonNull()) {
                newUrl = parsed.get("url").getAsString();
                if (newUrl.trim().isEmpty() || "null".equalsIgnoreCase(newUrl) || "N/A".equalsIgnoreCase(newUrl))
                    newUrl = null;
            }

            if (cr == null) {
                System.err.println("\n[DEBUG] AI returned a JSON but copyright was missing or null.");
                System.err.println("--- RAW OUTPUT ---\n" + responseBody);
                System.err.println("------------------\n");
            }
            return new AiResult(null, newUrl, cr);
        } catch (Exception e) {
            System.err.println("[WARN] Failed to parse Ollama copyright response: " + e.getMessage());
            System.err.println("       Raw body: " + responseBody);
        }
        return new AiResult(null, null, null);
    }

    @Override
    public String normalizeToSpdx(String licenseName) throws Exception {
        if (licenseName == null || licenseName.trim().isEmpty()) return null;
        String responseBody = post(buildJsonRequest(PromptBuilder.buildNormalizeSpdxPrompt(licenseName)));
        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            if (!root.has("response") && !root.has("thinking")) return null;
            String text = extractText(root);
            JsonObject parsed = JsonParser.parseString(text).getAsJsonObject();
            if (parsed.has("spdx_id") && !parsed.get("spdx_id").isJsonNull()) {
                String spdx = parsed.get("spdx_id").getAsString();
                if (spdx.trim().isEmpty() || "null".equalsIgnoreCase(spdx)) return null;
                return spdx;
            }
        } catch (Exception e) {
            System.err.println("[WARN] Failed to parse Ollama normalize response: " + e.getMessage());
        }
        return null;
    }

    @Override
    public String getAiName() {
        return "Ollama (" + model + ")";
    }

    private String buildJsonRequest(String prompt) {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", model);
        requestBody.addProperty("prompt", prompt);
        requestBody.addProperty("stream", false);
        requestBody.addProperty("format", "json");
        return requestBody.toString();
    }

    private AiResult parsePackageInfoResponse(String responseBody) {
        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            if (!root.has("response") && !root.has("thinking")) {
                System.err.println("[WARN] Ollama returned no response or thinking field. Body: " + responseBody);
                return new AiResult(null, null);
            }
            String text = extractText(root);
            JsonObject parsed = JsonParser.parseString(text).getAsJsonObject();

            String license = null;
            String url = null;
            if (parsed.has("license_identifier") && !parsed.get("license_identifier").isJsonNull()) {
                license = parsed.get("license_identifier").getAsString();
                if (license.trim().isEmpty() || "null".equalsIgnoreCase(license) || "N/A".equalsIgnoreCase(license))
                    license = null;
            }
            if (parsed.has("url") && !parsed.get("url").isJsonNull()) {
                url = parsed.get("url").getAsString();
                if (url.trim().isEmpty() || "null".equalsIgnoreCase(url) || "N/A".equalsIgnoreCase(url))
                    url = null;
            }
            return new AiResult(license, url);
        } catch (Exception e) {
            System.err.println("[WARN] Failed to parse Ollama response: " + e.getMessage());
            System.err.println("       Raw body: " + responseBody);
            return new AiResult(null, null);
        }
    }

    private String extractText(JsonObject root) {
        String text = "";
        if (root.has("response")) text = cleanJson(root.get("response").getAsString().trim());
        if (text.isEmpty() && root.has("thinking")) text = cleanJson(root.get("thinking").getAsString().trim());
        return text;
    }

    private String cleanJson(String text) {
        if (text.startsWith("```json")) text = text.substring(7);
        else if (text.startsWith("```")) text = text.substring(3);
        if (text.endsWith("```")) text = text.substring(0, text.length() - 3);
        return text.trim();
    }

    private String post(String jsonBody) throws Exception {
        int maxRetries = 3;
        int retryDelayMs = 3000;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            URL urlObj = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) urlObj.openConnection();
            try {
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                if (token != null && !token.trim().isEmpty())
                    conn.setRequestProperty("Authorization", "Bearer " + token);
                conn.setDoOutput(true);
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(300000);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                }

                int statusCode = conn.getResponseCode();
                InputStream is = (statusCode >= 200 && statusCode < 300) ? conn.getInputStream() : conn.getErrorStream();
                String body = readStream(is);

                if (statusCode >= 200 && statusCode < 300) return body;

                if ((statusCode == 503 || statusCode == 429) && attempt < maxRetries) {
                    System.err.printf("       [WARN] Ollama API busy (HTTP %d). Retrying (%d/%d) in %d ms...%n",
                            statusCode, attempt, maxRetries, retryDelayMs);
                    Thread.sleep(retryDelayMs);
                    retryDelayMs *= 2;
                    continue;
                }
                throw new RuntimeException("HTTP error from Ollama: " + statusCode + " " + body);
            } finally {
                conn.disconnect();
            }
        }
        throw new RuntimeException("Max retries exceeded");
    }

    private String readStream(InputStream is) throws IOException {
        if (is == null) return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }
}
