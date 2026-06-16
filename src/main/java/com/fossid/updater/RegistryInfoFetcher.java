package com.fossid.updater;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/**
 * Fetches component metadata (URL, License, Description) from various package registries.
 */
public class RegistryInfoFetcher {

    public static class RegistryResult {
        public String url;
        public String license;
        public String description;
        public String copyright;
        public String source;
        public String referenceUrl;

        @Override
        public String toString() {
            return String.format("RegistryResult{source='%s', url='%s', license='%s', copyright='%s'}", source, url, license, copyright);
        }
    }

    private final Gson gson = new Gson();

    /**
     * Entry point to fetch info based on package manager type.
     */
    public RegistryResult fetchInfo(String packageManager, String name, String version) {
        if (packageManager == null || name == null) return null;

        String pm = packageManager.toLowerCase();
        
        // Mapping package managers to their respective registries
        if (isAnyOf(pm, "npm", "bower", "pnpm", "yarn", "yarn2")) {
            return fetchNpmInfo(name, version);
        } else if (isAnyOf(pm, "maven", "gradle", "ivy", "kotlin")) {
            return fetchMavenInfo(name, version);
        } else if (isAnyOf(pm, "pip", "pipenv", "poetry", "hatch")) {
            return fetchPypiInfo(name, version);
        } else if (isAnyOf(pm, "rust", "cargo")) {
            return fetchCratesInfo(name, version);
        } else if (isAnyOf(pm, "gem", "bundler")) {
            return fetchRubyGemsInfo(name, version);
        } else if (isAnyOf(pm, "dotnet", "nuget")) {
            return fetchNuGetInfo(name, version);
        } else if (isAnyOf(pm, "godep", "gomod", "go")) {
            return fetchGoInfo(name, version);
        } else if (isAnyOf(pm, "composer")) {
            return fetchPackagistInfo(name, version);
        } else if (isAnyOf(pm, "cocoapod", "carthage")) {
            return fetchCocoaPodsInfo(name, version);
        } else if (isAnyOf(pm, "elixir")) {
            return fetchHexInfo(name, version);
        } else if (pm.equals("haskell")) {
            return fetchHackageInfo(name, version);
        } else if (isAnyOf(pm, "dart", "flutter")) {
            return fetchPubInfo(name, version);
        }

        return null;
    }

    private boolean isAnyOf(String value, String... options) {
        for (String opt : options) {
            if (opt.equalsIgnoreCase(value)) return true;
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NPM Registry
    // ─────────────────────────────────────────────────────────────────────────
    private RegistryResult fetchNpmInfo(String name, String version) {
        String cleanVersion = version.replaceAll("[\\^~<>=]", "").trim();
        String url = "https://registry.npmjs.org/" + name + "/" + cleanVersion;
        try {
            JsonObject data = callApi(url);
            if (data == null) return null;

            RegistryResult res = new RegistryResult();
            res.source = "NPM Registry";
            res.referenceUrl = url;
            res.url = getJsonString(data, "homepage");
            if (res.url == null && data.has("repository")) {
                JsonElement repo = data.get("repository");
                if (repo.isJsonObject()) {
                    res.url = getJsonString(repo.getAsJsonObject(), "url");
                } else if (repo.isJsonPrimitive()) {
                    res.url = repo.getAsString();
                }
            }
            
            // License
            JsonElement licElem = data.get("license");
            if (licElem != null) {
                if (licElem.isJsonPrimitive()) res.license = licElem.getAsString();
                else if (licElem.isJsonObject()) res.license = getJsonString(licElem.getAsJsonObject(), "type");
            }
            
            res.description = getJsonString(data, "description");
            
            // Copyright (Author)
            JsonElement authorElem = data.get("author");
            if (authorElem != null) {
                if (authorElem.isJsonPrimitive()) res.copyright = formatCopyright(authorElem.getAsString());
                else if (authorElem.isJsonObject()) res.copyright = formatCopyright(getJsonString(authorElem.getAsJsonObject(), "name"));
            }
            return res;
        } catch (Exception e) {
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Maven Registry (Search API + POM)
    // ─────────────────────────────────────────────────────────────────────────
    private RegistryResult fetchMavenInfo(String name, String version) {
        String[] parts = name.split("[:/]");
        String groupId, artifactId;
        if (parts.length >= 2) {
            groupId = parts[0];
            artifactId = parts[1];
        } else {
            return null;
        }

        RegistryResult res = new RegistryResult();
        res.source = "Maven Central";
        res.referenceUrl = String.format("https://search.maven.org/artifact/%s/%s/%s/jar", groupId, artifactId, version);

        try {
            // Recursively find metadata in POM and its parents
            findMavenMetadata(res, groupId, artifactId, version, 0);
        } catch (Exception e) {
            // ignore
        }

        if (res.url == null) res.url = "https://mvnrepository.com/artifact/" + groupId + "/" + artifactId;
        
        return res;
    }

    private void findMavenMetadata(RegistryResult res, String groupId, String artifactId, String version, int depth) throws Exception {
        if (depth > 5) return; // Limit recursion

        String groupPath = groupId.replace('.', '/');
        String pomUrl = String.format("https://repo1.maven.org/maven2/%s/%s/%s/%s-%s.pom", 
                                      groupPath, artifactId, version, artifactId, version);
        
        String pomContent = callApiRaw(pomUrl);
        if (pomContent == null) return;

        if (res.description == null) res.description = extractTag(pomContent, "description");
        if (res.url == null) res.url = extractTag(pomContent, "url");
        
        // License extraction: strictly from <license> block
        if (res.license == null) {
            res.license = extractLicenseFromPom(pomContent);
        }

        // Copyright/Author (Developer or Organization)
        if (res.copyright == null) {
            String developer = extractInnerTag(pomContent, "developer", "name");
            if (developer == null) developer = extractInnerTag(pomContent, "organization", "name");
            if (developer != null) res.copyright = formatCopyright(developer);
        }

        // If some info still missing, try parent POM
        if (res.license == null || res.url == null || res.description == null || res.copyright == null) {
            // Extract parent info
            String parentBlock = extractRawTag(pomContent, "parent");
            if (parentBlock != null) {
                String pGroupId = extractTag(parentBlock, "groupId");
                String pArtifactId = extractTag(parentBlock, "artifactId");
                String pVersion = extractTag(parentBlock, "version");
                if (pGroupId != null && pArtifactId != null && pVersion != null) {
                    findMavenMetadata(res, pGroupId, pArtifactId, pVersion, depth + 1);
                }
            }
        }
    }

    private String extractLicenseFromPom(String pomContent) {
        String licenseBlock = extractRawTag(pomContent, "license");
        if (licenseBlock != null) {
            return extractTag(licenseBlock, "name");
        }
        return null;
    }

    private String extractInnerTag(String xml, String parentTag, String childTag) {
        String parentBlock = extractRawTag(xml, parentTag);
        if (parentBlock != null) {
            return extractTag(parentBlock, childTag);
        }
        return null;
    }

    private String extractRawTag(String xml, String tagName) {
        Pattern p = Pattern.compile("<" + tagName + "[^>]*>(.*?)</" + tagName + ">", Pattern.DOTALL);
        Matcher m = p.matcher(xml);
        if (m.find()) return m.group(1).trim();
        return null;
    }

    private String extractTag(String xml, String tagName) {
        Pattern p = Pattern.compile("<" + tagName + "[^>]*>(.*?)</" + tagName + ">", Pattern.DOTALL);
        Matcher m = p.matcher(xml);
        if (m.find()) return m.group(1).trim().replaceAll("<[^>]*>", "");
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PyPI Registry
    // ─────────────────────────────────────────────────────────────────────────
    private RegistryResult fetchPypiInfo(String name, String version) {
        String cleanVersion = version.replaceAll("[\\^~<>=]", "").trim();
        String url = "https://pypi.org/pypi/" + name + "/" + (cleanVersion.isEmpty() ? "" : cleanVersion + "/") + "json";
        try {
            JsonObject data = callApi(url);
            if (data == null || !data.has("info")) return null;
            JsonObject info = data.getAsJsonObject("info");

            RegistryResult res = new RegistryResult();
            res.source = "PyPI";
            res.referenceUrl = url;
            res.url = getJsonString(info, "home_page");
            if (res.url == null || res.url.equalsIgnoreCase("N/A")) {
                res.url = getJsonString(info, "project_url");
            }
            res.license = getJsonString(info, "license");
            res.description = getJsonString(info, "summary");
            res.copyright = formatCopyright(getJsonString(info, "author"));
            return res;
        } catch (Exception e) {
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Crates.io Registry
    // ─────────────────────────────────────────────────────────────────────────
    private RegistryResult fetchCratesInfo(String name, String version) {
        String url = "https://crates.io/api/v1/crates/" + name;
        try {
            JsonObject data = callApi(url, true); // User-Agent required
            if (data == null || !data.has("crate")) return null;
            JsonObject crate = data.getAsJsonObject("crate");

            RegistryResult res = new RegistryResult();
            res.source = "Crates.io";
            res.referenceUrl = "https://crates.io/crates/" + name;
            res.url = getJsonString(crate, "homepage");
            if (res.url == null) res.url = getJsonString(crate, "repository");
            res.license = getJsonString(crate, "license");
            res.description = getJsonString(crate, "description");
            
            // Authors
            String authors = getJsonString(crate, "authors"); // Sometimes it's a string or we might need to fetch separately?
            // In API v1, authors might not be directly in the crate object. 
            // Let's check for "authors" field if it exists.
            if (authors != null) res.copyright = formatCopyright(authors);

            return res;
        } catch (Exception e) {
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RubyGems Registry
    // ─────────────────────────────────────────────────────────────────────────
    private RegistryResult fetchRubyGemsInfo(String name, String version) {
        String url = "https://rubygems.org/api/v2/rubygems/" + name + "/versions/" + version + ".json";
        try {
            JsonObject data = callApi(url);
            if (data == null) return null;

            RegistryResult res = new RegistryResult();
            res.source = "RubyGems";
            res.referenceUrl = url;
            res.url = getJsonString(data, "homepage_uri");
            if (res.url == null) res.url = getJsonString(data, "source_code_uri");
            
            JsonElement lics = data.get("licenses");
            if (lics != null && lics.isJsonArray()) {
                List<String> list = new ArrayList<>();
                for (JsonElement e : lics.getAsJsonArray()) list.add(e.getAsString());
                res.license = String.join(", ", list);
            }
            
            res.description = getJsonString(data, "description");
            if (res.description == null) res.description = getJsonString(data, "summary");
            
            res.copyright = formatCopyright(getJsonString(data, "authors"));
            
            return res;
        } catch (Exception e) {
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NuGet Registry
    // ─────────────────────────────────────────────────────────────────────────
    private RegistryResult fetchNuGetInfo(String name, String version) {
        String lowerId = name.toLowerCase();
        String lowerVer = version.toLowerCase();
        String url = String.format("https://api.nuget.org/v3/registration5-semver1/%s/%s.json", lowerId, lowerVer);
        try {
            JsonObject data = callApi(url);
            if (data == null) return null;

            RegistryResult res = new RegistryResult();
            res.source = "NuGet";
            res.referenceUrl = url;

            JsonObject entry = null;
            if (data.has("catalogEntry")) {
                JsonElement e = data.get("catalogEntry");
                if (e.isJsonObject()) entry = e.getAsJsonObject();
                else if (e.isJsonPrimitive()) {
                    entry = callApi(e.getAsString());
                }
            }

            if (entry != null) {
                res.description = getJsonString(entry, "description");
                res.url = getJsonString(entry, "projectUrl");
                res.license = getJsonString(entry, "licenseExpression");
                if (res.license == null) res.license = getJsonString(entry, "licenseUrl");
                res.copyright = formatCopyright(getJsonString(entry, "authors"));
            }
            
            return res;
        } catch (Exception e) {
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Go Registry (pkg.go.dev HTML)
    // ─────────────────────────────────────────────────────────────────────────
    private RegistryResult fetchGoInfo(String name, String version) {
        // Correct version prefix if missing
        if (version != null && !version.startsWith("v")) version = "v" + version;
        
        String url = String.format("https://pkg.go.dev/%s@%s", name, version);
        try {
            String html = callApiRaw(url);
            if (html == null) return null;

            RegistryResult res = new RegistryResult();
            res.source = "pkg.go.dev";
            res.referenceUrl = url;

            // Description
            Matcher mDesc = Pattern.compile("<meta name=\"Description\" content=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE).matcher(html);
            if (mDesc.find()) res.description = mDesc.group(1).trim();

            // Repo URL
            Matcher mRepo = Pattern.compile("class=\"UnitMeta-repo\".*?href=\"([^\"]+)\"", Pattern.DOTALL).matcher(html);
            if (mRepo.find()) res.url = mRepo.group(1).trim();

            // License
            Matcher mLic = Pattern.compile("data-test-id=\"UnitHeader-license\"[^>]*>([^<]+)</a>").matcher(html);
            Set<String> licenses = new LinkedHashSet<>();
            while (mLic.find()) {
                licenses.add(mLic.group(1).trim());
            }
            if (!licenses.isEmpty()) res.license = String.join(", ", licenses);

            return res;
        } catch (Exception e) {
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Packagist (Composer)
    // ─────────────────────────────────────────────────────────────────────────
    private RegistryResult fetchPackagistInfo(String name, String version) {
        String url = "https://repo.packagist.org/p2/" + name + ".json";
        try {
            JsonObject data = callApi(url);
            if (data == null || !data.has("packages")) return null;
            
            JsonElement pkgElement = data.getAsJsonObject("packages").get(name);
            if (pkgElement == null || !pkgElement.isJsonArray()) return null;

            JsonArray versions = pkgElement.getAsJsonArray();
            JsonObject targetVersion = null;
            for (JsonElement v : versions) {
                JsonObject vo = v.getAsJsonObject();
                if (version.equalsIgnoreCase(getJsonString(vo, "version"))) {
                    targetVersion = vo;
                    break;
                }
            }
            if (targetVersion == null && versions.size() > 0) {
                targetVersion = versions.get(0).getAsJsonObject(); // Fallback to first
            }

            if (targetVersion != null) {
                RegistryResult res = new RegistryResult();
                res.source = "Packagist";
                res.referenceUrl = "https://packagist.org/packages/" + name;
                res.url = getJsonString(targetVersion, "homepage");
                if (res.url == null) {
                    JsonObject source = targetVersion.getAsJsonObject("source");
                    if (source != null) res.url = getJsonString(source, "url");
                }
                
                JsonArray lics = targetVersion.getAsJsonArray("license");
                if (lics != null) {
                    List<String> list = new ArrayList<>();
                    for (JsonElement e : lics) list.add(e.getAsString());
                    res.license = String.join(", ", list);
                }
                res.description = getJsonString(targetVersion, "description");
                return res;
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CocoaPods
    // ─────────────────────────────────────────────────────────────────────────
    private RegistryResult fetchCocoaPodsInfo(String name, String version) {
        String url = "https://cocoapods.org/api/v1/pods/" + name;
        try {
            JsonObject data = callApi(url);
            if (data == null) return null;

            RegistryResult res = new RegistryResult();
            res.source = "CocoaPods";
            res.referenceUrl = url;
            res.url = getJsonString(data, "homepage");
            if (res.url == null) {
                JsonObject source = data.getAsJsonObject("source");
                if (source != null) {
                    res.url = getJsonString(source, "git");
                }
            }
            
            JsonElement lic = data.get("license");
            if (lic != null) {
                if (lic.isJsonPrimitive()) res.license = lic.getAsString();
                else if (lic.isJsonObject()) res.license = getJsonString(lic.getAsJsonObject(), "type");
            }
            res.description = getJsonString(data, "summary");
            return res;
        } catch (Exception e) {
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Hex.pm (Elixir)
    // ─────────────────────────────────────────────────────────────────────────
    private RegistryResult fetchHexInfo(String name, String version) {
        String url = "https://hex.pm/api/packages/" + name;
        try {
            JsonObject data = callApi(url);
            if (data == null) return null;

            RegistryResult res = new RegistryResult();
            res.source = "Hex.pm";
            res.referenceUrl = url;
            
            JsonObject meta = data.getAsJsonObject("meta");
            if (meta != null) {
                JsonArray lics = meta.getAsJsonArray("licenses");
                if (lics != null) {
                    List<String> list = new ArrayList<>();
                    for (JsonElement e : lics) list.add(e.getAsString());
                    res.license = String.join(", ", list);
                }
                JsonObject links = meta.getAsJsonObject("links");
                if (links != null) {
                    res.url = getJsonString(links, "GitHub");
                    if (res.url == null) res.url = getJsonString(links, "Homepage");
                }
            }
            res.description = getJsonString(data, "description");
            return res;
        } catch (Exception e) {
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Hackage (Haskell)
    // ─────────────────────────────────────────────────────────────────────────
    private RegistryResult fetchHackageInfo(String name, String version) {
        String url = String.format("https://hackage.haskell.org/package/%s.json", name);
        try {
            JsonObject data = callApi(url);
            if (data == null) return null;

            RegistryResult res = new RegistryResult();
            res.source = "Hackage";
            res.referenceUrl = url;
            res.license = getJsonString(data, "license");
            res.url = "https://hackage.haskell.org/package/" + name;
            return res;
        } catch (Exception e) {
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pub.dev (Dart/Flutter)
    // ─────────────────────────────────────────────────────────────────────────
    private RegistryResult fetchPubInfo(String name, String version) {
        String url = "https://pub.dev/api/packages/" + name;
        try {
            JsonObject data = callApi(url);
            if (data == null || !data.has("latest")) return null;
            
            JsonObject latest = data.getAsJsonObject("latest");
            JsonObject pubspec = latest.getAsJsonObject("pubspec");
            
            RegistryResult res = new RegistryResult();
            res.source = "Pub.dev";
            res.referenceUrl = url;
            if (pubspec != null) {
                res.url = getJsonString(pubspec, "homepage");
                if (res.url == null) res.url = getJsonString(pubspec, "repository");
                res.description = getJsonString(pubspec, "description");
            }
            return res;
        } catch (Exception e) {
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private JsonObject callApi(String urlStr) throws Exception {
        return callApi(urlStr, false);
    }

    private JsonObject callApi(String urlStr, boolean useUserAgent) throws Exception {
        String raw = callApiRaw(urlStr, useUserAgent);
        if (raw == null) return null;
        return JsonParser.parseString(raw).getAsJsonObject();
    }

    private String callApiRaw(String urlStr) throws Exception {
        return callApiRaw(urlStr, false);
    }

    private String callApiRaw(String urlStr, boolean useUserAgent) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        if (useUserAgent) {
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");
        }

        int status = conn.getResponseCode();
        if (status != 200) return null;

        try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder content = new StringBuilder();
            String inputLine;
            while ((inputLine = in.readLine()) != null) content.append(inputLine).append("\n");
            return content.toString();
        }
    }

    private String getJsonString(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return null;
    }

    private String formatCopyright(String author) {
        if (author == null || author.trim().isEmpty()) return null;
        String trimmed = author.trim();
        // Remove common separators if it's a list
        trimmed = trimmed.split(",")[0].trim();
        if (trimmed.toLowerCase().contains("copyright") || trimmed.contains("©")) {
            return trimmed;
        }
        return "Copyright (c) " + trimmed;
    }
}
