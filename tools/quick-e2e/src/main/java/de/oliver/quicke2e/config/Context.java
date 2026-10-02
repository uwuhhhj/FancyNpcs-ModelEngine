package de.oliver.quicke2e.config;

import java.nio.file.Path;

public class Context {

    private final Configuration configuration;
    private String actualBuildNumber;

    private Path serverEnvPath;
    private Path serverJarPath;
    private Path startScriptPath;

    private Process serverProcess;

    public Context(Configuration configuration) {
        this.configuration = configuration;
        this.actualBuildNumber = configuration.build();
    }

    public Configuration configuration() {
        return configuration;
    }

    public String actualBuildNumber() {
        return actualBuildNumber;
    }

    public void setActualBuildNumber(String actualBuildNumber) {
        this.actualBuildNumber = actualBuildNumber;
    }

    public String serverFileName() {
        return String.format("%s-%s-%s.jar", configuration.type(), configuration.version(), actualBuildNumber);
    }

    public Path serverEnvPath() {
        return serverEnvPath;
    }

    public void setServerEnvPath(Path serverEnvPath) {
        this.serverEnvPath = serverEnvPath;
    }

    public Path serverJarPath() {
        return serverJarPath;
    }

    public void setServerJarPath(Path serverJarPath) {
        this.serverJarPath = serverJarPath;
    }

    public Path startScriptPath() {
        return startScriptPath;
    }

    public void setStartScriptPath(Path startScriptPath) {
        this.startScriptPath = startScriptPath;
    }

    public Process serverProcess() {
        return serverProcess;
    }

    public void setServerProcess(Process serverProcess) {
        this.serverProcess = serverProcess;
    }
}
