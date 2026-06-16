package com.fossid.updater;

import java.util.*;

public class CopyrightUpdaterService {

    private final FossIdClient fossIdClient;
    private final AiClient aiClient;
    private final UrlLicenseFetcher urlLicenseFetcher;
    private final RegistryInfoFetcher registryInfoFetcher;

    private final Map<String, RegistryInfoFetcher.RegistryResult> registryCache = new HashMap<>();
    private final Map<String, String> urlCopyrightCache = new HashMap<>();
    private final Map<String, AiResult> aiCopyrightCache = new HashMap<>();

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
                if (ComponentUtils.isBlankOrNA(comp.name) || ComponentUtils.isBlankOrNA(comp.version)) {
                    System.out.printf("%s SKIP  (name or version is empty)%n", idx);
                    totalSkipped++;
                    continue;
                }
                int[] counts = processComponentCopyright(
                        idx, comp.name, comp.version,
                        comp.url, comp.licenseIdentifier, comp.licenseName,
                        null
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
                if (ComponentUtils.isBlankOrNA(comp.name) || ComponentUtils.isBlankOrNA(comp.version)) {
                    System.out.printf("%s SKIP  (name or version is empty)%n", idx);
                    totalSkipped++;
                    continue;
                }
                String packageManager = ComponentUtils.extractPackageManager(comp.detailedDependencyInfo);
                int[] counts = processComponentCopyright(
                        idx, comp.name, comp.version,
                        comp.url, comp.licenseIdentifier, null,
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

    private int[] processComponentCopyright(String idx, String name, String version,
                                            String url, String licenseIdentifier, String licenseName,
                                            String packageManager) {
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

        if (!ComponentUtils.isBlankOrNA(info.copyright)) {
            System.out.printf("%s SKIP  %-40s %-12s copyright already set%n", idx, name, version);
            return new int[]{0, 1, 0};
        }

        System.out.printf("%s QUERY %-40s %-12s (copyright missing)%n", idx, name, version);

        String copyright = null;
        String sourceStr = "N/A";
        String refUrl    = "N/A";

        // Step 1: GitHub API from existing URL
        if (!ComponentUtils.isBlankOrNA(url)) {
            copyright = fetchCopyrightWithCache(url);
            if (copyright != null) {
                sourceStr = "GitHub API";
                refUrl = url;
                System.out.printf("       [URL] 실제 Copyright 조회 성공 (GitHub API): %s (Ref: %s)%n", copyright, refUrl);
            }
        }

        // Step 2: Registry API
        if (copyright == null && !ComponentUtils.isBlankOrNA(packageManager)) {
            RegistryInfoFetcher.RegistryResult regResult = fetchRegistryWithCache(packageManager, name, version);
            if (regResult != null) {
                if (!ComponentUtils.isBlankOrNA(regResult.url)) {
                    copyright = fetchCopyrightWithCache(regResult.url);
                    if (copyright != null) {
                        sourceStr = regResult.source + " -> GitHub API";
                        refUrl = regResult.url;
                        System.out.printf("       [REGISTRY] Registry URL(%s)에서 Copyright 조회 성공 (GitHub API): %s%n", regResult.source, copyright);
                    }
                }
                if (copyright == null && !ComponentUtils.isBlankOrNA(regResult.copyright)) {
                    copyright = regResult.copyright;
                    sourceStr = regResult.source;
                    refUrl = regResult.referenceUrl;
                    System.out.printf("       [REGISTRY] Registry(%s)에서 Copyright 조회 성공: %s (Ref: %s)%n", regResult.source, copyright, refUrl);
                }
            }
        }

        // Step 3: AI query
        if (copyright == null) {
            System.out.println("       [AI] URL/Registry 조회 실패 → AI에게 대체 URL 및 Copyright 추정 요청");
            AiResult result;
            try {
                result = fetchAiCopyrightWithCache(name, version, url, licenseIdentifier, licenseName, packageManager);
            } catch (Exception e) {
                System.err.printf("       [ERROR] AI copyright query failed: %s%n", e.getMessage());
                return new int[]{0, 0, 1};
            }

            if (!ComponentUtils.isBlankOrNA(result.url)) {
                String aiUrlCopyright = fetchCopyrightWithCache(result.url);
                if (aiUrlCopyright != null) {
                    copyright = aiUrlCopyright;
                    sourceStr = "AI Suggested URL -> GitHub API";
                    refUrl = result.url;
                    System.out.printf("       [URL] AI 제안 URL에서 실제 Copyright 조회 성공 (GitHub API): %s (Ref: %s)%n", copyright, refUrl);
                }
            }

            if (copyright == null && !ComponentUtils.isBlankOrNA(result.copyright)) {
                copyright = result.copyright;
                sourceStr = aiClient.getAiName();
                refUrl = !ComponentUtils.isBlankOrNA(result.url) ? result.url : "Estimated";
                System.out.printf("       [AI] AI 추정 Copyright 사용: %s (Ref: %s)%n", copyright, refUrl);
            }
        }

        if (ComponentUtils.isBlankOrNA(copyright)) {
            System.out.println("       AI could not determine copyright. Skipping update.");
            return new int[]{0, 1, 0};
        }

        System.out.printf("       변경전: %s, %s, copyright=%s%n", name, version,
                ComponentUtils.isBlankOrNA(info.copyright) ? "N/A" : info.copyright);

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

    private String fetchCopyrightWithCache(String url) {
        if (urlCopyrightCache.containsKey(url)) return urlCopyrightCache.get(url);
        String copyright = urlLicenseFetcher.fetchCopyright(url);
        urlCopyrightCache.put(url, copyright);
        return copyright;
    }

    private RegistryInfoFetcher.RegistryResult fetchRegistryWithCache(String packageManager, String name, String version) {
        String key = packageManager + ":" + name + ":" + version;
        if (registryCache.containsKey(key)) return registryCache.get(key);
        RegistryInfoFetcher.RegistryResult result = registryInfoFetcher.fetchInfo(packageManager, name, version);
        registryCache.put(key, result);
        return result;
    }

    private AiResult fetchAiCopyrightWithCache(String name, String version, String url,
                                                String licenseIdentifier, String licenseName,
                                                String packageManager) throws Exception {
        String key = name + ":" + version;
        if (aiCopyrightCache.containsKey(key)) return aiCopyrightCache.get(key);
        AiResult result = aiClient.queryCopyright(name, version, url, licenseIdentifier, licenseName, packageManager);
        aiCopyrightCache.put(key, result);
        return result;
    }
}
