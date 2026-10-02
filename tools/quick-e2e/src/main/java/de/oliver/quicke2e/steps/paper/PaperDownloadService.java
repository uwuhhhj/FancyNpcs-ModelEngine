package de.oliver.quicke2e.steps.paper;

import com.google.gson.Gson;
import de.oliver.quicke2e.config.Context;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Paths;

public class PaperDownloadService {

    private static final Gson GSON = new Gson();
    private static final String BASE_URL = "https://api.papermc.io/v2";
    private static final String destination = "servers";

    private final HttpClient client;

    public PaperDownloadService() {
        this.client = HttpClient.newHttpClient();
    }

    public void downloadServerFile(Context context) {
        final String type = context.configuration().type();
        final String version = context.configuration().version();
        final String build = context.configuration().build();

        Path folderPath = Paths.get(String.format("%s/%s_%s_%s/", destination, type, version, build));
        if (!folderPath.toFile().exists()) {
            folderPath.toFile().mkdirs();
        }
        context.setServerEnvPath(folderPath);

        String buildNumber = build.equals("latest") ? getLatestBuildNumber(type, version) : build;
        context.setActualBuildNumber(buildNumber);

        Path filePath = Paths.get(String.format("%s/%s_%s_%s/%s-%s-%s.jar", destination, type, version, build, type, version, buildNumber));
        context.setServerJarPath(filePath);

        if (filePath.toFile().exists()) {
            System.out.println("Server file already exists, skipping download");
            return;
        }

        HttpRequest req = HttpRequest.newBuilder()
                .GET()
                .uri(URI.create(String.format("%s/projects/%s/versions/%s/builds/%s/downloads/%s-%s-%s.jar", BASE_URL, type, version, buildNumber, type, version, buildNumber)))
                .build();

        client.sendAsync(req, HttpResponse.BodyHandlers.ofFile(filePath))
                .thenAccept(r -> System.out.println("Downloaded server file to " + filePath))
                .join();
    }

    private String getLatestBuildNumber(
            String project,
            String version
    ) {
        HttpRequest req = HttpRequest.newBuilder()
                .GET()
                .uri(URI.create(String.format("%s/projects/%s/versions/%s", BASE_URL, project, version)))
                .build();

        HttpResponse<String> resp = client.sendAsync(req, HttpResponse.BodyHandlers.ofString()).join();
        BuildsResponse builds = GSON.fromJson(resp.body(), BuildsResponse.class);

        return builds.builds()[builds.builds().length - 1];
    }

}
