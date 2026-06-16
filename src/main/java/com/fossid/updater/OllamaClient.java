package com.fossid.updater;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class OllamaClient implements AiClient {

    private final String apiUrl;
    private final String model;
    private final String token; // Optional

    public OllamaClient(String address, String model, String token) {
        String base = address.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/api/generate")) {
            this.apiUrl = base;
        } else {
            this.apiUrl = base + "/api/generate";
        }
        this.model = model;
        this.token = token;
    }

    @Override
    public AiResult queryPackageInfo(String name, String version, String packageManager) throws Exception {
        String prompt = buildPrompt(name, version, packageManager);

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", model);
        requestBody.addProperty("prompt", prompt);
        requestBody.addProperty("stream", false);
        requestBody.addProperty("format", "json");

        String responseBody = post(apiUrl, requestBody.toString());
        
        // System.out.println("\n------------------ [AI Response Start] ------------------");
        // System.out.println(responseBody);
        // System.out.println("------------------ [AI Response End] ------------------\n");
        
        return parseOllamaResponse(responseBody);
    }

    private String buildPrompt(String name, String version, String packageManager) {
        String normalizedName = name;
        if ("Maven".equalsIgnoreCase(packageManager) && name.contains("/")) {
            normalizedName = name.replace("/", ":");
        }

        return "You are a world-class software compliance and open-source licensing expert. " +
                "Your task is to identify the official metadata for the following software component by searching your extensive knowledge base.\n\n" +
                "### Component Details:\n" +
                "- Package Manager: " + (packageManager != null ? packageManager : "unknown") + "\n" +
                "- Package Name: " + normalizedName + "\n" +
                "- Package Version: " + version + "\n\n" +
                "### Your Objective:\n" +
                "Provide the most accurate SPDX license identifier and the official homepage/source URL.\n\n" +
                "### URL Selection Strategy (Priority Order):\n" +
                "1. **Primary**: Official GitHub repository (e.g., https://github.com/owner/repo). This is critical for automated license verification.\n" +
                "2. **Secondary**: Official project website or documentation (e.g., https://project.org).\n" +
                "3. **Strictly Forbidden**: Do NOT use package registry landing pages (npmjs.com, nuget.org, pypi.org, mvnrepository.com, etc.).\n\n" +
                "### Requirements:\n" +
                "- License: Provide the exact SPDX identifier (e.g., Apache-2.0, MIT, BSD-3-Clause). If multi-licensed, provide the most common one.\n" +
                "- Accuracy: Ensure the URL is valid and publicly accessible. Avoid URLs likely to return 404.\n" +
                "- Reasoning: Even if you don't have an exact record for this specific version, provide the most likely information based on the project's historical data.\n\n" +
                "### Output Format:\n" +
                "You MUST respond ONLY with a JSON object. No markdown, no preamble, no conversational text.\n" +
                "{\n" +
                "  \"license_identifier\": \"<SPDX ID>\",\n" +
                "  \"url\": \"<Official Source URL>\"\n" +
                "}\n" +
                "If a field is absolutely unknowable, use null.";
    }

    private AiResult parseOllamaResponse(String responseBody) {
        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();

            if (!root.has("response") && !root.has("thinking")) {
                System.err.println("[WARN] Ollama returned no response or thinking field. Body: " + responseBody);
                return new AiResult(null, null);
            }

            String text = "";
            if (root.has("response")) {
                text = cleanOllamaText(root.get("response").getAsString().trim());
            }
            if (text.isEmpty() && root.has("thinking")) {
                text = cleanOllamaText(root.get("thinking").getAsString().trim());
            }
            JsonObject parsed = JsonParser.parseString(text).getAsJsonObject();

            String license = null;
            String url = null;

            if (parsed.has("license_identifier") && !parsed.get("license_identifier").isJsonNull()) {
                license = parsed.get("license_identifier").getAsString();
                if (license.trim().isEmpty() || "null".equalsIgnoreCase(license) || "N/A".equalsIgnoreCase(license)) {
                    license = null;
                }
            }
            if (parsed.has("url") && !parsed.get("url").isJsonNull()) {
                url = parsed.get("url").getAsString();
                if (url.trim().isEmpty() || "null".equalsIgnoreCase(url) || "N/A".equalsIgnoreCase(url)) {
                    url = null;
                }
            }

            return new AiResult(license, url);

        } catch (Exception e) {
            System.err.println("[WARN] Failed to parse Ollama response: " + e.getMessage());
            System.err.println("       Raw body: " + responseBody);
            return new AiResult(null, null);
        }
    }

    @Override
    public AiResult queryCopyright(String name, String version, String url,
                                   String licenseIdentifier, String licenseName,
                                   String packageManager) throws Exception {
        String normalizedName = name;
        if ("Maven".equalsIgnoreCase(packageManager) && name.contains("/")) {
            normalizedName = name.replace("/", ":");
        }

        String prompt = "You are a senior open-source compliance officer specializing in copyright attribution and legal notices. " +
                "Your task is to generate the standard copyright notice and identify the official source URL for this package.\n\n" +
                "### Component Context:\n" +
                "- Package Name: " + normalizedName + "\n" +
                "- Package Version: " + version + "\n" +
                "- Current URL: " + (url != null ? url : "unknown") + "\n" +
                "- License: " + (licenseIdentifier != null ? licenseIdentifier : "unknown") + " (" + (licenseName != null ? licenseName : "unknown") + ")\n" +
                (packageManager != null ? "- Ecosystem: " + packageManager + "\n" : "") +
                "\n### Your Objective:\n" +
                "1. Find the **Official Source URL** (Prefer GitHub if available).\n" +
                "2. Construct the **Standard Copyright Notice** (e.g., 'Copyright (c) 2024 The Project Authors' or 'Copyright (c) Microsoft Corporation').\n\n" +
                "### Guidelines:\n" +
                "- Copyright: Search for the primary maintainer, organization, or author. Base the year on the project's inception or latest release. Ensure it follows professional legal attribution standards.\n" +
                "- URL: Priority is GitHub repository. Do NOT use registry pages like npmjs.com or pypi.org.\n" +
                "- Reliability: Search your knowledge base thoroughly for the specific authors of this project.\n\n" +
                "### Output Format:\n" +
                "You MUST respond ONLY with a JSON object. No markdown, no explanation.\n" +
                "{\n" +
                "  \"url\": \"<Official Source URL>\",\n" +
                "  \"copyright\": \"<Standard Copyright Notice>\"\n" +
                "}\n" +
                "If a value is truly unavailable, use null.";

        // Print the prompt for transparency/debugging
        // System.out.println("\n------------------ [AI Copyright Prompt Start] ------------------");
        // System.out.println(prompt);
        // System.out.println("------------------ [AI Copyright Prompt End] ------------------\n");

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", model);
        requestBody.addProperty("prompt", prompt);
        requestBody.addProperty("stream", false);
        requestBody.addProperty("format", "json");

        String responseBody = post(apiUrl, requestBody.toString());
        
        // System.out.println("\n------------------ [AI Response Start] ------------------");
        // System.out.println(responseBody);
        // System.out.println("------------------ [AI Response End] ------------------\n");

        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            if (!root.has("response") && !root.has("thinking")) return new AiResult(null, null, null);
            
            String text = "";
            if (root.has("response")) {
                text = cleanOllamaText(root.get("response").getAsString().trim());
            }
            if (text.isEmpty() && root.has("thinking")) {
                text = cleanOllamaText(root.get("thinking").getAsString().trim());
            }
            
            JsonObject parsed = JsonParser.parseString(text).getAsJsonObject();
            String cr = null;
            if (parsed.has("copyright") && !parsed.get("copyright").isJsonNull()) {
                cr = parsed.get("copyright").getAsString();
            } else if (parsed.has("copyright_notice") && !parsed.get("copyright_notice").isJsonNull()) {
                cr = parsed.get("copyright_notice").getAsString();
            }
            if (cr != null && (cr.trim().isEmpty() || "null".equalsIgnoreCase(cr))) cr = null;

            String newUrl = null;
            if (parsed.has("url") && !parsed.get("url").isJsonNull()) {
                newUrl = parsed.get("url").getAsString();
                if (newUrl.trim().isEmpty() || "null".equalsIgnoreCase(newUrl) || "N/A".equalsIgnoreCase(newUrl)) {
                    newUrl = null;
                }
            }

            if (cr == null) {
                System.err.println("\n[DEBUG] AI returned a JSON but copyright was missing or null.");
                System.err.println("--- PROMPT ---\n" + prompt);
                System.err.println("\n--- RAW OUTPUT ---\n" + responseBody);
                System.err.println("------------------\n");
            }
            return new AiResult(null, newUrl, cr);
        } catch (Exception e) {
            System.err.println("[WARN] Failed to parse Ollama copyright response: " + e.getMessage());
            System.err.println("       Raw body: " + responseBody);
        }
        return new AiResult(null, null, null);
    }

    private String cleanOllamaText(String text) {
        if (text.startsWith("```json")) text = text.substring(7);
        else if (text.startsWith("```")) text = text.substring(3);
        if (text.endsWith("```")) text = text.substring(0, text.length() - 3);
        return text.trim();
    }

    private String post(String urlStr, String jsonBody) throws Exception {
        int maxRetries = 3;
        int retryDelayMs = 3000;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            URL urlObj = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) urlObj.openConnection();
            try {
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                if (token != null && !token.trim().isEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer " + token);
                }
                conn.setDoOutput(true);
                conn.setConnectTimeout(30000);
                // Ollama models can be quite slow to generate on CPU, increasing timeout
                conn.setReadTimeout(300000);

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = jsonBody.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                int statusCode = conn.getResponseCode();
                InputStream is = (statusCode >= 200 && statusCode < 300) ? conn.getInputStream() : conn.getErrorStream();
                String body = readStream(is);

                if (statusCode >= 200 && statusCode < 300) {
                    return body;
                }

                if (statusCode == 503 || statusCode == 429) {
                    if (attempt < maxRetries) {
                        System.err.printf("       [WARN] Ollama API busy (HTTP %d). Retrying (%d/%d) in %d ms...%n", statusCode, attempt, maxRetries, retryDelayMs);
                        Thread.sleep(retryDelayMs);
                        retryDelayMs *= 2;
                        continue;
                    }
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
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    @Override
    public String normalizeToSpdx(String licenseName) throws Exception {
        if (licenseName == null || licenseName.trim().isEmpty()) return null;

        String prompt = "You are an open-source licensing expert. Convert the following license name to its exact SPDX identifier.\n\n" +
                "License Name: " + licenseName + "\n\n" +
                "Requirements:\n" +
                "- If it is already a valid SPDX identifier (e.g., 'MIT', 'Apache-2.0'), return it as is.\n" +
                "- If it's a long name (e.g., 'GNU General Public License v3.0'), return the SPDX ID (e.g., 'GPL-3.0-only').\n" +
                "- If it's a list or multiple licenses, return the most prominent one as a single SPDX ID.\n\n" +
                "Output Format:\n" +
                "Respond ONLY with a JSON object. No markdown.\n" +
                "{\n" +
                "  \"spdx_id\": \"<SPDX ID>\"\n" +
                "}\n" +
                "If no match is found, use null.";

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", model);
        requestBody.addProperty("prompt", prompt);
        requestBody.addProperty("stream", false);
        requestBody.addProperty("format", "json");

        String responseBody = post(apiUrl, requestBody.toString());

        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            if (!root.has("response") && !root.has("thinking")) return null;
            String text = "";
            if (root.has("response")) text = cleanOllamaText(root.get("response").getAsString().trim());
            if (text.isEmpty() && root.has("thinking")) text = cleanOllamaText(root.get("thinking").getAsString().trim());
            
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
}
