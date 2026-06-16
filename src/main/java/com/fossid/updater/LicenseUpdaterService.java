package com.fossid.updater;

import com.google.gson.*;
import java.util.*;

public class LicenseUpdaterService {

    private final FossIdClient fossIdClient;
    private final AiClient aiClient;
    private final UrlLicenseFetcher urlLicenseFetcher;
    private final RegistryInfoFetcher registryInfoFetcher;

    public LicenseUpdaterService(FossIdClient fossIdClient, AiClient aiClient, String githubPat) {
        this.fossIdClient = fossIdClient;
        this.aiClient = aiClient;
        this.urlLicenseFetcher = new UrlLicenseFetcher(githubPat);
        this.registryInfoFetcher = new RegistryInfoFetcher();
    }


    public void run(String scanCode) {
        System.out.println("==========================================================");
        System.out.println(" FossID Dependency License/URL Updater");
        System.out.println("==========================================================");
        System.out.println("Scan Code: " + scanCode);
        System.out.println();

        List<DependencyComponent> components;
        try {
            System.out.println("[1/3] Fetching dependency analysis results ...");
            components = fossIdClient.getDependencyAnalysisResults(scanCode);
            System.out.println("      Found " + components.size() + " component(s).");
        } catch (Exception e) {
            System.err.println("[ERROR] Failed to fetch dependency analysis results: " + e.getMessage());
            e.printStackTrace();
            return;
        }

        if (components.isEmpty()) {
            System.out.println("No components found. Nothing to do.");
            return;
        }

        int updatedCount = 0;
        int skippedCount = 0;
        int errorCount = 0;

        System.out.println();
        System.out.println("[2/3] Processing components ...");
        System.out.println("----------------------------------------------------------");

        for (int i = 0; i < components.size(); i++) {
            DependencyComponent comp = components.get(i);
            String idx = String.format("[%d/%d]", i + 1, components.size());

            boolean needsLicense = isBlankOrNA(comp.licenseIdentifier);
            boolean needsUrl     = isBlankOrNA(comp.url);

            if (!needsLicense && !needsUrl) {
                System.out.printf("%s SKIP  %-40s %-10s license=%-20s url=%s%n",
                        idx, comp.name, comp.version, comp.licenseIdentifier, comp.url);
                skippedCount++;
                continue;
            }

            String packageManager = extractPackageManager(comp.detailedDependencyInfo);
            System.out.printf("%s QUERY %-40s %-10s (pkgMgr=%s, needLicense=%b, needUrl=%b)%n",
                    idx, comp.name, comp.version, packageManager, needsLicense, needsUrl);

            String foundLicense = null;
            String foundUrl = null;
            List<String> sources = new ArrayList<>();

            // ── Step 1: GitHub API (if URL already exists) ────────────────────
            if (needsLicense && !isBlankOrNA(comp.url)) {
                String rawLicense = urlLicenseFetcher.fetchLicense(comp.url);
                if (rawLicense != null) {
                    foundLicense = normalizeLicense(rawLicense);
                    sources.add("License: GitHub API");
                    System.out.printf("       [URL] 실제 라이선스 조회 성공 (GitHub API): %s%n", foundLicense);
                }
            }

            // ── Step 2: Registry API ───────────────────────────────────────────
            if ((foundLicense == null && needsLicense) || (foundUrl == null && needsUrl)) {
                if (!isBlankOrNA(packageManager)) {
                    RegistryInfoFetcher.RegistryResult regResult = registryInfoFetcher.fetchInfo(packageManager, comp.name, comp.version);
                    if (regResult != null) {
                        if (foundLicense == null && needsLicense && !isBlankOrNA(regResult.license)) {
                            foundLicense = normalizeLicense(regResult.license);
                            sources.add("License: " + regResult.source);
                            System.out.printf("       [REGISTRY] 라이선스 조회 성공 (%s): %s%n", regResult.source, foundLicense);
                        }
                        if (foundUrl == null && needsUrl && !isBlankOrNA(regResult.url)) {
                            foundUrl = regResult.url;
                            sources.add("URL: " + regResult.source);
                            System.out.printf("       [REGISTRY] URL 조회 성공 (%s): %s%n", regResult.source, foundUrl);
                        }
                        
                        // If registry gave us a URL but no license, try GitHub API on that URL
                        if (foundLicense == null && needsLicense && !isBlankOrNA(regResult.url)) {
                            String regUrlLicense = urlLicenseFetcher.fetchLicense(regResult.url);
                            if (regUrlLicense != null) {
                                foundLicense = normalizeLicense(regUrlLicense);
                                sources.add("License: " + regResult.source + " -> GitHub API");
                                System.out.printf("       [REGISTRY] Registry URL에서 라이선스 조회 성공 (GitHub API): %s%n", foundLicense);
                            }
                        }
                    }
                }
            }

            // ── Step 3: AI Query ──────────────────────────────────────────────
            if ((foundLicense == null && needsLicense) || (foundUrl == null && needsUrl)) {
                System.out.println("       [AI] URL/Registry 조회 실패 → AI에게 정보 요청");
                AiResult result;
                try {
                    result = aiClient.queryPackageInfo(comp.name, comp.version, packageManager);
                    
                    if (foundUrl == null && needsUrl && !isBlankOrNA(result.url)) {
                        foundUrl = result.url;
                        sources.add("URL: " + aiClient.getAiName());
                        System.out.printf("       [AI] URL 추정: %s%n", foundUrl);
                    }
                    
                    if (foundLicense == null && needsLicense) {
                        // Try GitHub on AI suggested URL
                        String aiUrlToCheck = !isBlankOrNA(result.url) ? result.url : comp.url;
                        if (!isBlankOrNA(aiUrlToCheck)) {
                            String aiUrlLicense = urlLicenseFetcher.fetchLicense(aiUrlToCheck);
                            if (aiUrlLicense != null) {
                                foundLicense = normalizeLicense(aiUrlLicense);
                                sources.add("License: AI Suggested URL -> GitHub API");
                                System.out.printf("       [AI] AI 제안 URL에서 실제 라이선스 조회 성공: %s%n", foundLicense);
                            }
                        }
                        
                        // Fallback to AI guessed license
                        if (foundLicense == null && !isBlankOrNA(result.licenseIdentifier)) {
                            foundLicense = normalizeLicense(result.licenseIdentifier);
                            sources.add("License: " + aiClient.getAiName());
                            System.out.printf("       [AI] 라이선스 추정: %s%n", foundLicense);
                        }
                    }
                } catch (Exception e) {
                    System.err.printf("       [ERROR] AI query failed: %s%n", e.getMessage());
                }
            }

            // ── Step 4: Final update ───────────────────────────────────────────
            if (foundLicense == null && foundUrl == null) {
                System.out.println("       No new information found. Skipping update.");
                skippedCount++;
                continue;
            }

            String finalLicense = foundLicense != null ? foundLicense : comp.licenseIdentifier;
            String finalUrl     = foundUrl     != null ? foundUrl     : comp.url;
            String sourceStr = String.join(" / ", sources);

            System.out.printf("       변경전: %s, %s, %s, %s%n", comp.name, comp.version,
                    comp.licenseIdentifier != null ? comp.licenseIdentifier : "N/A",
                    comp.url != null ? comp.url : "N/A");

            try {
                fossIdClient.updateComponent(comp.name, comp.version, foundLicense, foundUrl);
                System.out.printf("       변경후: %s, %s, %s, %s%n", comp.name, comp.version,
                        finalLicense != null ? finalLicense : "N/A",
                        finalUrl     != null ? finalUrl     : "N/A");
                System.out.println("       UPDATE SUCCESS [Source: " + sourceStr + "]");
                updatedCount++;
            } catch (Exception e) {
                System.err.printf("       [ERROR] FossID update failed: %s%n", e.getMessage());
                errorCount++;
            }
        }


        System.out.println();
        System.out.println("[3/3] Summary");
        System.out.println("==========================================================");
        System.out.printf("  Total components : %d%n", components.size());
        System.out.printf("  Updated          : %d%n", updatedCount);
        System.out.printf("  Skipped          : %d%n", skippedCount);
        System.out.printf("  Errors           : %d%n", errorCount);
        System.out.println("==========================================================");
    }

    /**
     * Normalizes a license string to an SPDX identifier using AI.
     */
    private String normalizeLicense(String license) {
        if (isBlankOrNA(license)) return license;
        try {
            String normalized = aiClient.normalizeToSpdx(license);
            if (normalized != null && !normalized.equalsIgnoreCase(license)) {
                System.out.printf("       [AI] License Normalized: %s -> %s%n", license, normalized);
                return normalized;
            }
        } catch (Exception e) {
            System.err.println("       [WARN] License normalization failed: " + e.getMessage());
        }
        return license;
    }

    /**
     * Returns true if the value is null, empty, blank, "N/A", or the literal string "null".
     */
    private boolean isBlankOrNA(String value) {
        if (value == null) return true;
        String trimmed = value.trim();
        return trimmed.isEmpty()
                || "null".equalsIgnoreCase(trimmed)
                || "N/A".equalsIgnoreCase(trimmed);
    }

    /**
     * Extracts the package manager name from the detailed_dependency_info JSON string.
     * Looks for a "project_id" value like "Yarn:json-diff:1.0.6:" and takes the prefix
     * before the first colon.
     */
    private String extractPackageManager(String detailedDependencyInfo) {
        if (detailedDependencyInfo == null || detailedDependencyInfo.trim().isEmpty()) {
            return "unknown";
        }
        try {
            JsonObject obj = JsonParser.parseString(detailedDependencyInfo).getAsJsonObject();
            // Iterate over all key entries (hash-based keys)
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                JsonElement val = entry.getValue();
                if (val.isJsonObject()) {
                    JsonObject inner = val.getAsJsonObject();
                    if (inner.has("project_id")) {
                        String projectId = inner.get("project_id").getAsString();
                        // e.g. "Yarn:json-diff:1.0.6:" -> extract "Yarn"
                        int colonIdx = projectId.indexOf(':');
                        if (colonIdx > 0) {
                            return projectId.substring(0, colonIdx);
                        }
                        return projectId;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[WARN] Could not parse detailed_dependency_info: " + e.getMessage());
        }
        return "unknown";
    }
}
