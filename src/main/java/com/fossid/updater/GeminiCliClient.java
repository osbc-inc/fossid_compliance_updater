package com.fossid.updater;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

public class GeminiCliClient implements AiClient {

    private final String model;

    public GeminiCliClient() {
        this.model = "default";
    }

    public GeminiCliClient(String model, String apiKey) {
        this.model = model;
    }

    @Override
    public AiResult queryPackageInfo(String name, String version, String packageManager) throws Exception {
        String responseText = executeCli(PromptBuilder.buildPackageInfoPrompt(name, version, packageManager));
        checkQuotaError(responseText);
        return parseJsonResponse(responseText);
    }

    @Override
    public AiResult queryCopyright(String name, String version, String url,
                                   String licenseIdentifier, String licenseName,
                                   String packageManager) throws Exception {
        String responseText = executeCli(
                PromptBuilder.buildCopyrightPrompt(name, version, url, licenseIdentifier, licenseName, packageManager));
        checkQuotaError(responseText);

        try {
            JsonObject parsed = JsonParser.parseString(extractJson(responseText)).getAsJsonObject();
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
            System.err.println("[WARN] Failed to parse Gemini CLI copyright response: " + e.getMessage());
            System.err.println("       Raw output: " + responseText);
            return new AiResult(null, null, null);
        }
    }

    @Override
    public String normalizeToSpdx(String licenseName) throws Exception {
        if (licenseName == null || licenseName.trim().isEmpty()) return null;
        String responseText = executeCli(PromptBuilder.buildNormalizeSpdxPrompt(licenseName));
        checkQuotaError(responseText);
        try {
            JsonObject parsed = JsonParser.parseString(extractJson(responseText)).getAsJsonObject();
            if (parsed.has("spdx_id") && !parsed.get("spdx_id").isJsonNull()) {
                String spdx = parsed.get("spdx_id").getAsString();
                if (spdx.trim().isEmpty() || "null".equalsIgnoreCase(spdx)) return null;
                return spdx;
            }
        } catch (Exception e) {
            System.err.println("[WARN] Failed to parse Gemini CLI normalize response: " + e.getMessage());
        }
        return null;
    }

    @Override
    public String getAiName() {
        return "Gemini CLI (" + model + ")";
    }

    private AiResult parseJsonResponse(String responseText) {
        try {
            JsonObject parsed = JsonParser.parseString(extractJson(responseText)).getAsJsonObject();
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
            System.err.println("[WARN] Failed to parse Gemini CLI response: " + e.getMessage());
            System.err.println("       Raw output: " + responseText);
            return new AiResult(null, null);
        }
    }

    private String executeCli(String prompt) throws Exception {
        String psCommand = "gemini @'\n" + sanitizeForPowerShellHereString(prompt) + "\n'@";
        List<String> command = Arrays.asList("powershell.exe", "-NoLogo", "-Command", psCommand);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        String output = readStream(process.getInputStream());
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            System.err.println("[WARN] Gemini CLI call returned exit code " + exitCode);
            System.err.println("[WARN] Output: " + output);
        }
        return output;
    }

    private String extractJson(String responseText) {
        String text = responseText.trim();
        if (text.contains("```json")) {
            int start = text.indexOf("```json") + 7;
            int end = text.lastIndexOf("```");
            if (end > start) text = text.substring(start, end).trim();
        } else if (text.contains("```")) {
            int start = text.indexOf("```") + 3;
            int end = text.lastIndexOf("```");
            if (end > start) text = text.substring(start, end).trim();
        }
        int firstBrace = text.indexOf('{');
        int lastBrace = text.lastIndexOf('}');
        if (firstBrace != -1 && lastBrace != -1 && lastBrace >= firstBrace)
            text = text.substring(firstBrace, lastBrace + 1);
        return text;
    }

    private static String sanitizeForPowerShellHereString(String prompt) {
        return prompt.replaceAll("(?m)^'@", " '@");
    }

    private void checkQuotaError(String responseText) {
        if (responseText == null) return;
        if (responseText.contains("\"code\": 429") || responseText.contains("RESOURCE_EXHAUSTED")) {
            System.err.println("\n[CRITICAL ERROR] Gemini API Quota Exceeded (HTTP 429)");
            System.err.println("Message: You exceeded your current quota, please check your plan and billing details.");
            System.err.println("Raw Error Response:\n" + responseText);
            System.err.println("\nExecution stopped to prevent further failures.");
            System.exit(1);
        }
    }

    private String readStream(InputStream is) throws IOException {
        if (is == null) return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
            return sb.toString();
        }
    }
}
