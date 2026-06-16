package com.fossid.updater;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.*;

/**
 * Fetches license and copyright information directly from source URLs.
 *
 * Supported sources:
 *  - GitHub repos via GitHub REST API (requires public access or a PAT for higher rate limits)
 *
 * GitHub API rate limits:
 *  - Without PAT : 60  requests / hour
 *  - With PAT    : 5,000 requests / hour  (recommended for bulk processing)
 */
public class UrlLicenseFetcher {

    /** Matches https://github.com/{owner}/{repo}[/anything] */
    private static final Pattern GITHUB_PATTERN =
            Pattern.compile("https?://github\\.com/([^/\\s#?]+)/([^/\\s#?]+).*",
                    Pattern.CASE_INSENSITIVE);

    /**
     * Extracts copyright lines from license text.
     * Matches patterns like:
     *   Copyright (c) 2023 Author
     *   Copyright (C) 2023 Author
     *   Copyright 2023 Author
     *   © 2023 Author
     */
    private static final Pattern COPYRIGHT_PATTERN =
            Pattern.compile(
                    "(?i)(Copyright\\s*(\\(c\\)|\\(C\\)|©)?\\s*\\d{4}.*|©\\s*\\d{4}.*)",
                    Pattern.MULTILINE);

    private final String githubPat; // null if not provided

    public UrlLicenseFetcher(String githubPat) {
        this.githubPat = (githubPat != null && !githubPat.trim().isEmpty()) ? githubPat.trim() : null;
        if (this.githubPat != null) {
            System.out.println("[UrlLicenseFetcher] GitHub PAT configured (rate limit: 5,000 req/hour)");
        } else {
            System.out.println("[UrlLicenseFetcher] No GitHub PAT — using unauthenticated API (rate limit: 60 req/hour)");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Fetches the SPDX license identifier from the given URL.
     * For GitHub repos, calls GET /repos/{owner}/{repo} and reads license.spdx_id.
     * Returns null if unable to determine.
     */
    public String fetchLicense(String url) {
        GithubCoords coords = parseGithub(url);
        if (coords == null) return null;
        return fetchGitHubLicense(coords.owner, coords.repo);
    }

    /**
     * Fetches copyright notice(s) from the LICENSE file of a GitHub repository.
     * Calls GET /repos/{owner}/{repo}/license, decodes the file content (base64),
     * and extracts the first matching copyright line.
     * Returns null if unable to determine.
     */
    public String fetchCopyright(String url) {
        GithubCoords coords = parseGithub(url);
        if (coords == null) return null;
        return fetchGitHubCopyright(coords.owner, coords.repo);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GitHub: License (SPDX ID)
    // ─────────────────────────────────────────────────────────────────────────

    private String fetchGitHubLicense(String owner, String repo) {
        String apiUrl = "https://api.github.com/repos/" + owner + "/" + repo;
        try {
            JsonObject root = callGitHubApi(apiUrl);
            if (root == null) return null;

            if (root.has("license") && !root.get("license").isJsonNull()) {
                JsonObject licObj = root.getAsJsonObject("license");
                if (licObj.has("spdx_id") && !licObj.get("spdx_id").isJsonNull()) {
                    String spdxId = licObj.get("spdx_id").getAsString().trim();
                    if (!spdxId.isEmpty()
                            && !"NOASSERTION".equalsIgnoreCase(spdxId)
                            && !"NONE".equalsIgnoreCase(spdxId)) {
                        return spdxId;
                    }
                }
            }
            System.err.printf("       [URL] GitHub repo %s/%s: no detectable SPDX license%n", owner, repo);
        } catch (Exception e) {
            System.err.printf("       [URL] GitHub license fetch failed (%s/%s): %s%n", owner, repo, e.getMessage());
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GitHub: Copyright from LICENSE file
    // ─────────────────────────────────────────────────────────────────────────

    private String fetchGitHubCopyright(String owner, String repo) {
        String apiUrl = "https://api.github.com/repos/" + owner + "/" + repo + "/license";
        try {
            JsonObject root = callGitHubApi(apiUrl);
            if (root == null) return null;

            if (!root.has("content") || root.get("content").isJsonNull()) {
                System.err.printf("       [URL] GitHub repo %s/%s: LICENSE file has no content%n", owner, repo);
                return null;
            }

            // Content is base64-encoded (with newlines inside the encoding)
            String encoded = root.get("content").getAsString().replaceAll("\\s", "");
            String licenseText = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);

            // Extract first copyright line
            Matcher m = COPYRIGHT_PATTERN.matcher(licenseText);
            if (m.find()) {
                String copyright = m.group(0).trim();
                // Trim to reasonable length (avoid multi-line captures)
                if (copyright.length() > 200) {
                    copyright = copyright.substring(0, 200).trim();
                }
                return copyright;
            }
            System.err.printf("       [URL] GitHub repo %s/%s: no copyright line found in LICENSE%n", owner, repo);

        } catch (Exception e) {
            System.err.printf("       [URL] GitHub copyright fetch failed (%s/%s): %s%n", owner, repo, e.getMessage());
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTTP helper
    // ─────────────────────────────────────────────────────────────────────────

    private JsonObject callGitHubApi(String apiUrl) throws Exception {
        URL url = new URL(apiUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("User-Agent", "fossid-license-fetcher/1.0");
        if (githubPat != null) {
            conn.setRequestProperty("Authorization", "Bearer " + githubPat);
        }
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(15_000);

        int status = conn.getResponseCode();
        if (status == 404) {
            System.err.printf("       [URL] GitHub API 404: %s%n", apiUrl);
            return null;
        }
        if (status == 403 || status == 429) {
            System.err.printf("       [URL] GitHub API rate-limited (HTTP %d). Consider using --githubpat%n", status);
            return null;
        }
        if (status != 200) {
            System.err.printf("       [URL] GitHub API HTTP %d: %s%n", status, apiUrl);
            return null;
        }

        String body = readStream(conn.getInputStream());
        return JsonParser.parseString(body).getAsJsonObject();
    }

    private String readStream(InputStream is) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // URL parsing
    // ─────────────────────────────────────────────────────────────────────────

    private static GithubCoords parseGithub(String url) {
        if (url == null || url.trim().isEmpty()) return null;
        Matcher m = GITHUB_PATTERN.matcher(url.trim());
        if (!m.matches()) return null;
        String owner = m.group(1);
        String repo  = m.group(2);
        if (repo.endsWith(".git")) repo = repo.substring(0, repo.length() - 4);
        return new GithubCoords(owner, repo);
    }

    private static class GithubCoords {
        final String owner, repo;
        GithubCoords(String owner, String repo) { this.owner = owner; this.repo = repo; }
    }
}
