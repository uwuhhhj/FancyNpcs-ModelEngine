package de.oliver.quicke2e.steps.paper;

import com.google.gson.annotations.SerializedName;

public record BuildsResponse(
        @SerializedName("project_id") String projectID,
        @SerializedName("project_name") String projectName,
        String version,
        String[] builds
) {
}
