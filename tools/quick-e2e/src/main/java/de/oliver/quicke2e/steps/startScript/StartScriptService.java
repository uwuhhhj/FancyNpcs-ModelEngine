package de.oliver.quicke2e.steps.startScript;

import de.oliver.quicke2e.config.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

public class StartScriptService {

    private String startScriptContent;

    public StartScriptService() {
        try {
            startScriptContent = getResourceFileAsString("start.sh");
        } catch (IOException e) {
            startScriptContent = "echo No start script found!";
        }
    }

    /**
     * Reads given resource file as a string.
     *
     * @param fileName path to the resource file
     * @return the file's contents
     * @throws IOException if read fails for any reason
     */
    private static String getResourceFileAsString(String fileName) throws IOException {
        ClassLoader classLoader = ClassLoader.getSystemClassLoader();
        try (InputStream is = classLoader.getResourceAsStream(fileName)) {
            if (is == null) return null;
            try (InputStreamReader isr = new InputStreamReader(is);
                 BufferedReader reader = new BufferedReader(isr)) {
                return reader.lines().collect(Collectors.joining(System.lineSeparator()));
            }
        }
    }

    public boolean writeStartScript(Context context) {
        Path startScriptPath = Path.of(String.format("%s/start.sh", context.serverEnvPath().toString()));

        String content = startScriptContent
                .replace("%SERVER_FILE%", context.serverFileName());

        try {
            Files.writeString(startScriptPath, content);
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }

        context.setStartScriptPath(startScriptPath);
        return true;
    }
}
