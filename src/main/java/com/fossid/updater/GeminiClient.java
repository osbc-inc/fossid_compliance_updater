package com.fossid.updater;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class GeminiClient implements AiClient {

    private static final String BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s";

    private final String model;
    private final String apiKey;

    public GeminiClient(String model, String apiKey) {
        this.model = model;
        this.apiKey = apiKey;
    }

    @Override
    public AiResult queryPackageInfo(String name, String version, String packageManager) throws Exception {
        String responseBody = post(String.format(BASE_URL, model, apiKey),
                buildJsonRequest(PromptBuilder.buildPackageInfoPrompt(name, version, packageManager)));
        return parseGeminiResponse(responseBody);
    }

    @Override
    public AiResult queryCopyright(String name, String version, String url,
                                   String licenseIdentifier, String licenseName,
                                   String packageManager) throws Exception {
        String responseBody = post(String.format(BASE_URL, model, apiKey),
                buildJsonRequest(PromptBuilder.buildCopyrightPrompt(name, version, url, licenseIdentifier, licenseName, packageManager)));
        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            JsonArray candidates = root.getAsJsonArray("candidates");
            if (candidates == null || candidates.size() == 0) return new AiResult(null, null, null);
            String text = candidates.get(0).getAsJsonObject()
                    .getAsJsonObject("content").getAsJsonArray("parts")
                    .get(0).getAsJsonObject().get("text").getAsString().trim();
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
            return new AiResult(null, newUrl, cr);
        } catch (Exception e) {
            System.err.println("[WARN] Failed to parse Gemini copyright response: " + e.getMessage());
            System.err.println("       Raw body: " + responseBody);
        }
        return new AiResult(null, null, null);
    }

    @Override
    public String normalizeToSpdx(String licenseName) throws Exception {
        if (licenseName == null || licenseName.trim().isEmpty()) return null;
        String responseBody = post(String.format(BASE_URL, model, apiKey),
                buildJsonRequest(PromptBuilder.buildNormalizeSpdxPrompt(licenseName)));
        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            JsonArray candidates = root.getAsJsonArray("candidates");
            if (candidates == null || candidates.size() == 0) return null;
            String text = candidates.get(0).getAsJsonObject()
                    .getAsJsonObject("content").getAsJsonArray("parts")
                    .get(0).getAsJsonObject().get("text").getAsString().trim();
            JsonObject parsed = JsonParser.parseString(text).getAsJsonObject();
            if (parsed.has("spdx_id") && !parsed.get("spdx_id").isJsonNull()) {
                String spdx = parsed.get("spdx_id").getAsString();
                if (spdx.trim().isEmpty() || "null".equalsIgnoreCase(spdx)) return null;
                return spdx;
            }
        } catch (Exception e) {
            System.err.println("[WARN] Failed to parse Gemini normalize response: " + e.getMessage());
        }
        return null;
    }

    @Override
    public String getAiName() {
        return "Gemini (" + model + ")";
    }

    private String buildJsonRequest(String prompt) {
        JsonObject textPart = new JsonObject();
        textPart.addProperty("text", prompt);
        JsonArray parts = new JsonArray();
        parts.add(textPart);
        JsonObject content = new JsonObject();
        content.add("parts", parts);
        JsonArray contents = new JsonArray();
        contents.add(content);
        JsonObject requestBody = new JsonObject();
        requestBody.add("contents", contents);
        JsonObject generationConfig = new JsonObject();
        generationConfig.addProperty("responseMimeType", "application/json");
        requestBody.add("generationConfig", generationConfig);
        return requestBody.toString();
    }

    private AiResult parseGeminiResponse(String responseBody) {
        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            JsonArray candidates = root.getAsJsonArray("candidates");
            if (candidates == null || candidates.size() == 0) {
                System.err.println("[WARN] Gemini returned no candidates. Body: " + responseBody);
                return new AiResult(null, null);
            }
            String text = candidates.get(0).getAsJsonObject()
                    .getAsJsonObject("content").getAsJsonArray("parts")
                    .get(0).getAsJsonObject().get("text").getAsString().trim();
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
            System.err.println("[WARN] Failed to parse Gemini response: " + e.getMessage());
            System.err.println("       Raw body: " + responseBody);
            return new AiResult(null, null);
        }
    }

    private String post(String urlStr, String jsonBody) throws Exception {
        int maxRetries = 3;
        int retryDelayMs = 3000;

        URL urlObj;
        try {
            urlObj = new URI(urlStr).toURL();
        } catch (Exception e) {
            throw new RuntimeException("Invalid Gemini API URL (check model name): " + model);
        }

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            HttpURLConnection conn = (HttpURLConnection) urlObj.openConnection();
            try {
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setDoOutput(true);
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(120000);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                }

                int statusCode = conn.getResponseCode();
                InputStream is = (statusCode >= 200 && statusCode < 300) ? conn.getInputStream() : conn.getErrorStream();
                String body = readStream(is);

                if (statusCode >= 200 && statusCode < 300) return body;

                if ((statusCode == 503 || statusCode == 429) && attempt < maxRetries) {
                    System.err.printf("       [WARN] Gemini API busy (HTTP %d). Retrying (%d/%d) in %d ms...%n",
                            statusCode, attempt, maxRetries, retryDelayMs);
                    Thread.sleep(retryDelayMs);
                    retryDelayMs *= 2;
                    continue;
                }
                throw new RuntimeException("HTTP error from Gemini (model=" + model + "): " + statusCode + " " + body);
            } finally {
                conn.disconnect();
            }
        }
        throw new RuntimeException("Max retries exceeded");
    }

    private String readStream(InputStream is) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }
}
