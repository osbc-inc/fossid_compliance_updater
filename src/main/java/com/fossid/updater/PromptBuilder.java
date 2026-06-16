package com.fossid.updater;

public class PromptBuilder {

    private PromptBuilder() {}

    public static String buildPackageInfoPrompt(String name, String version, String packageManager) {
        String normalizedName = normalizeMavenName(name, packageManager);
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

    public static String buildCopyrightPrompt(String name, String version, String url,
                                               String licenseIdentifier, String licenseName,
                                               String packageManager) {
        String normalizedName = normalizeMavenName(name, packageManager);
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

    public static String buildNormalizeSpdxPrompt(String licenseName) {
        return "You are an open-source licensing expert. Convert the following license name to its exact SPDX identifier.\n\n" +
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
    }

    private static String normalizeMavenName(String name, String packageManager) {
        if ("Maven".equalsIgnoreCase(packageManager) && name != null && name.contains("/")) {
            return name.replace("/", ":");
        }
        return name;
    }
}
