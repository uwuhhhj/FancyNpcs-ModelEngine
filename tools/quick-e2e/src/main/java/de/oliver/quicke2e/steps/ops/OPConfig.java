package de.oliver.quicke2e.steps.ops;

public record OPConfig(
        String uuid,
        String name,
        int level,
        boolean bypassesPlayerLimit
) {
}
