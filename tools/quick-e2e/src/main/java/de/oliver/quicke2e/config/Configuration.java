package de.oliver.quicke2e.config;

public record Configuration(
        String type,
        String version,
        String build,
        String[] plugins,
        boolean eula,
        String[] opPlayers,
        String port
) {
}
