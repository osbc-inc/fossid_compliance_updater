package com.fossid.updater;

import java.util.List;

/**
 * Service that handles the --getinfo copyright workflow:
 *  1. From get_scan_identified_components (identified scan components)
 *  2. From get_dependency_analysis_results (dependency components)
 *  For each component, get_information is called to check if copyright is missing.
 *  If missing, queryCopyright is called on the AI client.
 *  If AI returns a copyright, updateComponentCopyright is called.
 */
public class CopyrightUpdaterService {

    private final FossIdClient fossIdClient;
    private final AiClient aiClient;
    private final UrlLicenseFetcher urlLicenseFetcher;
    private final RegistryInfoFetcher registryInfoFetcher;

    public CopyrightUpdaterService(FossIdClient fossIdClient, AiClient aiClient, String githubPat) {
        this.fossIdClient = fossIdClient;
        this.aiClient = aiClient;
        this.urlLicenseFetcher = new UrlLicenseFetcher(githubPat);
        this.registryInfoFetcher = new RegistryInfoFetcher();
    }

    public void run(String scanCode) {
        System.out.println("==========================================================");
        System.out.println(" FossID Copyright Updater");
        System.out.println("==========================================================");
        System.out.println("Scan Code: " + scanCode);
        System.out.println();

        int totalUpdated = 0;
        int totalSkipped = 0;
        int totalError = 0;

        // ── Phase 1: Identified scan components ──────────────────────────────
        System.out.println("[Phase 1] Processing identified scan components ...");
        System.out.println("----------------------------------------------------------");
        try {
            List<IdentifiedComponent> identified = fossIdClient.getScanIdentifiedComponents(scanCode);
            System.out.println("  Found " + identified.size() + " identified component(s).");
            System.out.println();

            for (int i = 0; i < identified.size(); i++) {
                IdentifiedComponent comp = identified.get(i);
                String idx = String.format("[%d/%d]", i + 1, identified.size());
                if (isBlankOrNull(comp.name) || isBlankOrNull(comp.version)) {
                    System.out.printf("%s SKIP  (name or version is empty)%n", idx);
                    totalSkipped++;
                    continue;
                }
                int[] counts = processComponentCopyright(
                        idx, comp.name, comp.version,
                        comp.url, comp.licenseIdentifier, comp.licenseName,
                        null /* no package manager in identified scan */
                );
                totalUpdated += counts[0];
                totalSkipped += counts[1];
                totalError   += counts[2];
            }
        } catch (Exception e) {
            System.err.println("[ERROR] Failed to fetch identified components: " + e.getMessage());
        }

        System.out.println();

        // ── Phase 2: Dependency analysis components ───────────────────────────
        System.out.println("[Phase 2] Processing dependency analysis components ...");
        System.out.println("----------------------------------------------------------");
        try {
            List<DependencyComponent> deps = fossIdClient.getDependencyAnalysisResults(scanCode);
            System.out.println("  Found " + deps.size() + " dependency component(s).");
            System.out.println();

            for (int i = 0; i < deps.size(); i++) {
                DependencyComponent comp = deps.get(i);
                String idx = String.format("[%d/%d]", i + 1, deps.size());
                if (isBlankOrNull(comp.name) || isBlankOrNull(comp.version)) {
                    System.out.printf("%s SKIP  (name or version is empty)%n", idx);
                    totalSkipped++;
                    continue;
                }
                String packageManager = extractPackageManager(comp.detailedDependencyInfo);
                int[] counts = processComponentCopyright(
                        idx, comp.name, comp.version,
                        comp.url, comp.licenseIdentifier, null /* no license_name in dep */,
                        packageManager
                );
                totalUpdated += counts[0];
                totalSkipped += counts[1];
                totalError   += counts[2];
            }
        } catch (Exception e) {
            System.err.println("[ERROR] Failed to fetch dependency analysis results: " + e.getMessage());
        }

        System.out.println();
        System.out.println("==========================================================");
        System.out.println(" Summary");
        System.out.println("==========================================================");
        System.out.printf("  Updated : %d%n", totalUpdated);
        System.out.printf("  Skipped : %d%n", totalSkipped);
        System.out.printf("  Errors  : %d%n", totalError);
        System.out.println("==========================================================");
    }

    /**
     * For a single component: calls get_information, checks copyright, queries AI if needed,
     * then updates via FossID API.
     * @return int[3] = {updated, skipped, error}
     */
    private int[] processComponentCopyright(String idx, String name, String version,
                                            String url, String licenseIdentifier, String licenseName,
                                            String packageManager) {
        // Step 1: get detailed component info including current copyright
        ComponentInfo info;
        try {
            info = fossIdClient.getComponentInformation(name, version);
        } catch (Exception e) {
            System.err.printf("%s [ERROR] get_information failed for %s:%s => %s%n", idx, name, version, e.getMessage());
            return new int[]{0, 0, 1};
        }

        if (info == null) {
            System.out.printf("%s SKIP  %-40s %-12s (component not found)%n", idx, name, version);
            return new int[]{0, 1, 0};
        }

        // Step 2: if copyright is already populated, skip
        if (!isBlankOrNull(info.copyright)) {
            System.out.printf("%s SKIP  %-40s %-12s copyright already set%n", idx, name, version);
            return new int[]{0, 1, 0};
        }

        System.out.printf("%s QUERY %-40s %-12s (copyright missing)%n", idx, name, version);

        String copyright = null;
        String sourceStr = "N/A";
        String refUrl = "N/A";

        // Step 3: Try URL-based direct fetch first (GitHub API)
        if (!isBlankOrNull(url)) {
            copyright = urlLicenseFetcher.fetchCopyright(url);
            if (copyright != null) {
                sourceStr = "GitHub API";
                refUrl = url;
                System.out.printf("       [URL] 실제 Copyright 조회 성공 (GitHub API): %s (Ref: %s)%n", copyright, refUrl);
            }
        }

        // Step 4: Try Registry API (if package manager is known)
        if (copyright == null && !isBlankOrNull(packageManager)) {
            RegistryInfoFetcher.RegistryResult regResult = registryInfoFetcher.fetchInfo(packageManager, name, version);
            if (regResult != null) {
                // If registry returned a URL, try fetching copyright from it if it's GitHub
                if (!isBlankOrNull(regResult.url)) {
                    copyright = urlLicenseFetcher.fetchCopyright(regResult.url);
                    if (copyright != null) {
                        sourceStr = regResult.source + " -> GitHub API";
                        refUrl = regResult.url;
                        System.out.printf("       [REGISTRY] Registry URL(%s)에서 Copyright 조회 성공 (GitHub API): %s%n", regResult.source, copyright);
                    }
                }
                
                // If still no copyright, try the copyright field from registry metadata
                if (copyright == null && !isBlankOrNull(regResult.copyright)) {
                    copyright = regResult.copyright;
                    sourceStr = regResult.source;
                    refUrl = regResult.referenceUrl;
                    System.out.printf("       [REGISTRY] Registry(%s)에서 Copyright 조회 성공: %s (Ref: %s)%n", regResult.source, copyright, refUrl);
                }
            }
        }

        // Step 5: If URL/Registry fetch fails, query AI
        if (copyright == null) {
            System.out.println("       [AI] URL/Registry 조회 실패 → AI에게 대체 URL 및 Copyright 추정 요청");
            AiResult result;
            try {
                result = aiClient.queryCopyright(name, version, url, licenseIdentifier, licenseName, packageManager);
            } catch (Exception e) {
                System.err.printf("       [ERROR] AI copyright query failed: %s%n", e.getMessage());
                return new int[]{0, 0, 1};
            }

            // Step 5a: If AI suggested a URL, try to fetch copyright from that URL via GitHub API
            if (!isBlankOrNull(result.url)) {
                String aiUrlCopyright = urlLicenseFetcher.fetchCopyright(result.url);
                if (aiUrlCopyright != null) {
                    copyright = aiUrlCopyright;
                    sourceStr = "AI Suggested URL -> GitHub API";
                    refUrl = result.url;
                    System.out.printf("       [URL] AI 제안 URL에서 실제 Copyright 조회 성공 (GitHub API): %s (Ref: %s)%n", copyright, refUrl);
                }
            }

            // Step 5b: If still no copyright, fall back to AI's guessed copyright
            if (copyright == null && !isBlankOrNull(result.copyright)) {
                copyright = result.copyright;
                sourceStr = aiClient.getAiName();
                refUrl = !isBlankOrNull(result.url) ? result.url : "Estimated";
                System.out.printf("       [AI] AI 추정 Copyright 사용: %s (Ref: %s)%n", copyright, refUrl);
            }
        }

        if (isBlankOrNull(copyright)) {
            System.out.println("       AI could not determine copyright. Skipping update.");
            return new int[]{0, 1, 0};
        }

        System.out.printf("       변경전: %s, %s, copyright=%s%n", name, version,
                isBlankOrNull(info.copyright) ? "N/A" : info.copyright);

        // Step 6: update copyright
        try {
            fossIdClient.updateComponentCopyright(name, version, copyright);
            System.out.printf("       변경후: %s, %s, copyright=%s%n", name, version, copyright);
            System.out.println("       UPDATE SUCCESS [Source: " + sourceStr + "]");
            return new int[]{1, 0, 0};
        } catch (Exception e) {
            System.err.printf("       [ERROR] FossID updateCopyright failed: %s%n", e.getMessage());
            return new int[]{0, 0, 1};
        }
    }


    private boolean isBlankOrNull(String value) {
        if (value == null) return true;
        String trimmed = value.trim();
        return trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed) || "N/A".equalsIgnoreCase(trimmed);
    }

    private String extractPackageManager(String detailedDependencyInfo) {
        if (isBlankOrNull(detailedDependencyInfo)) return null;
        try {
            com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(detailedDependencyInfo).getAsJsonObject();
            for (java.util.Map.Entry<String, com.google.gson.JsonElement> entry : obj.entrySet()) {
                com.google.gson.JsonElement val = entry.getValue();
                if (val.isJsonObject()) {
                    com.google.gson.JsonObject inner = val.getAsJsonObject();
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
