package com.fossid.updater;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * AI client that invokes a local 'gemini' CLI command via PowerShell.
 */
public class GeminiCliClient implements AiClient {

    private final String model;
    private final String apiKey;

    public GeminiCliClient() {
        this.model = "default";
        this.apiKey = null;
    }

    public GeminiCliClient(String model, String apiKey) {
        this.model = model;
        this.apiKey = apiKey;
    }

    @Override
    public AiResult queryPackageInfo(String name, String version, String packageManager) throws Exception {
        String prompt = buildPrompt(name, version, packageManager);
        String responseText = executeCli(prompt);
        checkQuotaError(responseText);
        return parseGeminiResponse(responseText);
    }

    @Override
    public AiResult queryCopyright(String name, String version, String url,
                                   String licenseIdentifier, String licenseName,
                                   String packageManager) throws Exception {
        String prompt = buildCopyrightPrompt(name, version, url, licenseIdentifier, licenseName, packageManager);
        
        // Print the prompt for transparency/debugging
        // System.out.println("\n------------------ [AI Copyright Prompt Start] ------------------");
        // System.out.println(prompt);
        // System.out.println("------------------ [AI Copyright Prompt End] ------------------\n");

        String responseText = executeCli(prompt);
        
        // System.out.println("\n------------------ [AI Response Start] ------------------");
        // System.out.println(responseText);
        // System.out.println("------------------ [AI Response End] ------------------\n");

        checkQuotaError(responseText);

        // Use a more flexible parser for copyright results
        try {
            String jsonText = extractJson(responseText);
            JsonObject parsed = JsonParser.parseString(jsonText).getAsJsonObject();
            
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
            return new AiResult(null, newUrl, cr);
        } catch (Exception e) {
            System.err.println("[WARN] Failed to parse Gemini CLI copyright response: " + e.getMessage());
            System.err.println("       Raw output: " + responseText);
            return new AiResult(null, null, null);
        }
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
                "You MUST respond ONLY with a valid JSON object. No markdown, no preamble, no conversational text.\n" +
                "{\n" +
                "  \"license_identifier\": \"<SPDX ID>\",\n" +
                "  \"url\": \"<Official Source URL>\"\n" +
                "}\n" +
                "If a field is absolutely unknowable, use null.";
    }

    private String buildCopyrightPrompt(String name, String version, String url,
                                         String licenseIdentifier, String licenseName,
                                         String packageManager) {
        String normalizedName = name;
        if ("Maven".equalsIgnoreCase(packageManager) && name.contains("/")) {
            normalizedName = name.replace("/", ":");
        }

        return "You are a senior open-source compliance officer specializing in copyright attribution and legal notices. " +
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
                "You MUST respond ONLY with a valid JSON object. No markdown, no explanation.\n" +
                "{\n" +
                "  \"url\": \"<Official Source URL>\",\n" +
                "  \"copyright\": \"<Standard Copyright Notice>\"\n" +
                "}\n" +
                "If a value is truly unavailable, use null.";
    }

    private String executeCli(String prompt) throws Exception {
        String psCommand = "gemini @'\n" + sanitizeForPowerShellHereString(prompt) + "\n'@";

        List<String> command = Arrays.asList(
                "powershell.exe",
                "-NoLogo",
                "-Command",
                psCommand
        );

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

    private String readStream(InputStream is) throws IOException {
        if (is == null) return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        }
    }

    private String extractJson(String responseText) {
        String jsonText = responseText.trim();
        if (jsonText.contains("```json")) {
            int start = jsonText.indexOf("```json") + 7;
            int end = jsonText.lastIndexOf("```");
            if (end > start) {
                jsonText = jsonText.substring(start, end).trim();
            }
        } else if (jsonText.contains("```")) {
            int start = jsonText.indexOf("```") + 3;
            int end = jsonText.lastIndexOf("```");
            if (end > start) {
                jsonText = jsonText.substring(start, end).trim();
            }
        }

        int firstBrace = jsonText.indexOf('{');
        int lastBrace = jsonText.lastIndexOf('}');
        if (firstBrace != -1 && lastBrace != -1 && lastBrace >= firstBrace) {
            jsonText = jsonText.substring(firstBrace, lastBrace + 1);
        }

        return jsonText;
    }

    private AiResult parseGeminiResponse(String responseText) {
        try {
            String jsonText = extractJson(responseText);
            JsonObject parsed = JsonParser.parseString(jsonText).getAsJsonObject();

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
            System.err.println("[WARN] Failed to parse Gemini CLI response: " + e.getMessage());
            System.err.println("       Raw output: " + responseText);
            return new AiResult(null, null);
        }
    }

    /**
     * Prevents PowerShell single-quoted here-string injection.
     * The closing delimiter '@ must appear at the start of a line (column 0).
     * Any '@ found at the start of a line in the prompt is prefixed with a space
     * so PowerShell does not interpret it as the terminator.
     */
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

        String responseText = executeCli(prompt);
        checkQuotaError(responseText);

        try {
            String jsonText = extractJson(responseText);
            JsonObject parsed = JsonParser.parseString(jsonText).getAsJsonObject();
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
}
