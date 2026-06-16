package com.fossid.updater;

import java.util.*;

public class LicenseUpdaterService {

    private static final Set<String> KNOWN_SPDX = new HashSet<>(Arrays.asList(
        "MIT", "MIT-0", "Apache-2.0", "Apache-1.1",
        "GPL-2.0-only", "GPL-2.0-or-later", "GPL-3.0-only", "GPL-3.0-or-later",
        "GPL-2.0", "GPL-3.0",
        "LGPL-2.0-only", "LGPL-2.0-or-later", "LGPL-2.1-only", "LGPL-2.1-or-later",
        "LGPL-3.0-only", "LGPL-3.0-or-later", "LGPL-2.1", "LGPL-3.0",
        "AGPL-3.0-only", "AGPL-3.0-or-later", "AGPL-3.0",
        "BSD-2-Clause", "BSD-3-Clause", "BSD-4-Clause", "0BSD",
        "ISC", "MPL-2.0", "MPL-1.1",
        "CDDL-1.0", "CDDL-1.1",
        "EPL-1.0", "EPL-2.0",
        "CC0-1.0", "CC-BY-4.0", "CC-BY-SA-4.0", "CC-BY-NC-4.0",
        "Unlicense", "WTFPL", "Artistic-2.0", "Zlib",
        "PSF-2.0", "Python-2.0", "EUPL-1.2",
        "MS-PL", "MS-RL", "OFL-1.1", "AFL-3.0",
        "NOASSERTION", "NONE"
    ));

    private final FossIdClient fossIdClient;
    private final AiClient aiClient;
    private final UrlLicenseFetcher urlLicenseFetcher;
    private final RegistryInfoFetcher registryInfoFetcher;

    private final Map<String, RegistryInfoFetcher.RegistryResult> registryCache = new HashMap<>();
    private final Map<String, String> urlLicenseCache = new HashMap<>();
    private final Map<String, AiResult> aiResultCache = new HashMap<>();

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

            boolean needsLicense = ComponentUtils.isBlankOrNA(comp.licenseIdentifier);
            boolean needsUrl     = ComponentUtils.isBlankOrNA(comp.url);

            if (!needsLicense && !needsUrl) {
                System.out.printf("%s SKIP  %-40s %-10s license=%-20s url=%s%n",
                        idx, comp.name, comp.version, comp.licenseIdentifier, comp.url);
                skippedCount++;
                continue;
            }

            String packageManager = ComponentUtils.extractPackageManager(comp.detailedDependencyInfo);
            System.out.printf("%s QUERY %-40s %-10s (pkgMgr=%s, needLicense=%b, needUrl=%b)%n",
                    idx, comp.name, comp.version,
                    packageManager != null ? packageManager : "unknown",
                    needsLicense, needsUrl);

            String foundLicense = null;
            String foundUrl = null;
            List<String> sources = new ArrayList<>();

            // ── Step 1: GitHub API (if URL already exists) ────────────────────
            if (needsLicense && !ComponentUtils.isBlankOrNA(comp.url)) {
                String rawLicense = fetchLicenseWithCache(comp.url);
                if (rawLicense != null) {
                    foundLicense = normalizeLicense(rawLicense);
                    sources.add("License: GitHub API");
                    System.out.printf("       [URL] 실제 라이선스 조회 성공 (GitHub API): %s%n", foundLicense);
                }
            }

            // ── Step 2: Registry API ───────────────────────────────────────────
            if ((foundLicense == null && needsLicense) || (foundUrl == null && needsUrl)) {
                if (!ComponentUtils.isBlankOrNA(packageManager)) {
                    RegistryInfoFetcher.RegistryResult regResult = fetchRegistryWithCache(packageManager, comp.name, comp.version);
                    if (regResult != null) {
                        if (foundLicense == null && needsLicense && !ComponentUtils.isBlankOrNA(regResult.license)) {
                            foundLicense = normalizeLicense(regResult.license);
                            sources.add("License: " + regResult.source);
                            System.out.printf("       [REGISTRY] 라이선스 조회 성공 (%s): %s%n", regResult.source, foundLicense);
                        }
                        if (foundUrl == null && needsUrl && !ComponentUtils.isBlankOrNA(regResult.url)) {
                            foundUrl = regResult.url;
                            sources.add("URL: " + regResult.source);
                            System.out.printf("       [REGISTRY] URL 조회 성공 (%s): %s%n", regResult.source, foundUrl);
                        }

                        // If registry gave us a URL but no license, try GitHub API on that URL
                        if (foundLicense == null && needsLicense && !ComponentUtils.isBlankOrNA(regResult.url)) {
                            String regUrlLicense = fetchLicenseWithCache(regResult.url);
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
                    result = fetchAiResultWithCache(comp.name, comp.version, packageManager);

                    if (foundUrl == null && needsUrl && !ComponentUtils.isBlankOrNA(result.url)) {
                        foundUrl = result.url;
                        sources.add("URL: " + aiClient.getAiName());
                        System.out.printf("       [AI] URL 추정: %s%n", foundUrl);
                    }

                    if (foundLicense == null && needsLicense) {
                        String aiUrlToCheck = !ComponentUtils.isBlankOrNA(result.url) ? result.url : comp.url;
                        if (!ComponentUtils.isBlankOrNA(aiUrlToCheck)) {
                            String aiUrlLicense = fetchLicenseWithCache(aiUrlToCheck);
                            if (aiUrlLicense != null) {
                                foundLicense = normalizeLicense(aiUrlLicense);
                                sources.add("License: AI Suggested URL -> GitHub API");
                                System.out.printf("       [AI] AI 제안 URL에서 실제 라이선스 조회 성공: %s%n", foundLicense);
                            }
                        }

                        if (foundLicense == null && !ComponentUtils.isBlankOrNA(result.licenseIdentifier)) {
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
            String sourceStr    = String.join(" / ", sources);

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

    private String normalizeLicense(String license) {
        if (ComponentUtils.isBlankOrNA(license)) return license;
        String trimmed = license.trim();
        // Return canonical SPDX form if already a known identifier — skip AI call
        for (String spdx : KNOWN_SPDX) {
            if (spdx.equalsIgnoreCase(trimmed)) return spdx;
        }
        try {
            String normalized = aiClient.normalizeToSpdx(trimmed);
            if (normalized != null && !normalized.equalsIgnoreCase(trimmed)) {
                System.out.printf("       [AI] License Normalized: %s -> %s%n", trimmed, normalized);
                return normalized;
            }
        } catch (Exception e) {
            System.err.println("       [WARN] License normalization failed: " + e.getMessage());
        }
        return trimmed;
    }

    private String fetchLicenseWithCache(String url) {
        if (urlLicenseCache.containsKey(url)) return urlLicenseCache.get(url);
        String license = urlLicenseFetcher.fetchLicense(url);
        urlLicenseCache.put(url, license);
        return license;
    }

    private RegistryInfoFetcher.RegistryResult fetchRegistryWithCache(String packageManager, String name, String version) {
        String key = packageManager + ":" + name + ":" + version;
        if (registryCache.containsKey(key)) return registryCache.get(key);
        RegistryInfoFetcher.RegistryResult result = registryInfoFetcher.fetchInfo(packageManager, name, version);
        registryCache.put(key, result);
        return result;
    }

    private AiResult fetchAiResultWithCache(String name, String version, String packageManager) throws Exception {
        String key = (packageManager != null ? packageManager : "") + ":" + name + ":" + version;
        if (aiResultCache.containsKey(key)) return aiResultCache.get(key);
        AiResult result = aiClient.queryPackageInfo(name, version, packageManager);
        aiResultCache.put(key, result);
        return result;
    }
}
