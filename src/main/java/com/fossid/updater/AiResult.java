package com.fossid.updater;

public class AiResult {
    public String licenseIdentifier;
    public String url;
    public String copyright;

    public AiResult(String licenseIdentifier, String url) {
        this.licenseIdentifier = licenseIdentifier;
        this.url = url;
    }

    public AiResult(String licenseIdentifier, String url, String copyright) {
        this.licenseIdentifier = licenseIdentifier;
        this.url = url;
        this.copyright = copyright;
    }
}
