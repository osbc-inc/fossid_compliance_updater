package com.fossid.updater;

import com.google.gson.annotations.SerializedName;

public class DependencyComponent {

    @SerializedName("scan_id")
    public int scanId;

    @SerializedName("component_id")
    public int componentId;

    @SerializedName("package_id")
    public String packageId;

    @SerializedName("projects_and_scopes")
    public String projectsAndScopes;

    @SerializedName("detailed_dependency_info")
    public String detailedDependencyInfo;

    public String updated;

    @SerializedName("include_in_report")
    public int includeInReport;

    @SerializedName("is_direct_dependency")
    public int isDirectDependency;

    @SerializedName("is_transitive_dependency")
    public int isTransitiveDependency;

    public String name;
    public String version;

    @SerializedName("license_identifier")
    public String licenseIdentifier;

    @SerializedName("license_category")
    public String licenseCategory;

    public String url;
    public String cpe;

    @SerializedName("license_id")
    public Integer licenseId;
}
