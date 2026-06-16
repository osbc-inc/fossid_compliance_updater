package com.fossid.updater;

public interface AiClient {

    /**
     * Queries the AI model to find the SPDX license identifier and official homepage URL
     * for the given package name, version, and package manager.
     *
     * @return AiResult with licenseIdentifier and url (may be null if AI cannot determine)
     */
    AiResult queryPackageInfo(String name, String version, String packageManager) throws Exception;

    /**
     * Queries the AI model to find the official homepage URL and copyright information.
     *
     * @return AiResult with url and copyright (may be null if AI cannot determine)
     */
    AiResult queryCopyright(String name, String version, String url,
                            String licenseIdentifier, String licenseName,
                            String packageManager) throws Exception;

    /**
     * Normalizes a given license name or string to a valid SPDX identifier.
     *
     * @param licenseName The license name to normalize (e.g., "Apache License 2.0")
     * @return The SPDX identifier (e.g., "Apache-2.0") or null if cannot determine
     */
    String normalizeToSpdx(String licenseName) throws Exception;

    /**
     * Returns the name and model of the AI client.
     */
    String getAiName();
}
