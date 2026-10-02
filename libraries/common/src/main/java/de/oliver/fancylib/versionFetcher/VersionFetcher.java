package de.oliver.fancylib.versionFetcher;

import de.oliver.fancyanalytics.logger.ExtendedFancyLogger;
import org.apache.maven.artifact.versioning.ComparableVersion;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public interface VersionFetcher {

    ExtendedFancyLogger LOGGER = new ExtendedFancyLogger("VersionFetcher");

    static String getDataFromUrl(String urlString) {
        try {
            URL url = new URL(urlString);
            URLConnection connection = url.openConnection();

            connection.setConnectTimeout(300);
            Scanner scanner = new Scanner(connection.getInputStream(), StandardCharsets.UTF_8).useDelimiter("\\A");

            return scanner.hasNext() ? scanner.next() : "";
        } catch (IOException e) {
            LOGGER.warn("Could not fetch data from URL: " + urlString);
        }

        return "";
    }

    ComparableVersion fetchNewestVersion();

    String getDownloadUrl();
}
