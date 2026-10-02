package com.fancyinnovations.config;

/**
 * Represents a field in a configuration file.
 *
 * @param <T>          the type of the field value
 * @param path         the path to the field in the configuration file (e.g., "notifications.mute_version")
 * @param description  a description of the field
 * @param forRemoval   indicates if this field should be removed
 * @param defaultValue the default value of the field
 * @param forceDefault indicates if the default value should be enforced
 * @param type         the class type of the field value
 */
public record ConfigField<T>(
        String path,
        String description,
        boolean forRemoval,
        T defaultValue,
        boolean forceDefault,
        Class<T> type
) {
}
