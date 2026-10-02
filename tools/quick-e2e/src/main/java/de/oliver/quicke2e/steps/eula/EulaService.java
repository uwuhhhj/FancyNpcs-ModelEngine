package de.oliver.quicke2e.steps.eula;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class EulaService {

    public boolean setEulaToTrue(String path) {
        Path eulaPath = Path.of(path);
        try {
            Files.writeString(eulaPath, "eula=true");
        } catch (IOException e) {
            return false;
        }

        return true;
    }

}
