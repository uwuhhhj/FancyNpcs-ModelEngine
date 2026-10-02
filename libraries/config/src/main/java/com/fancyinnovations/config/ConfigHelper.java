package com.fancyinnovations.config;

import org.bukkit.configuration.file.FileConfiguration;

@Deprecated
public class ConfigHelper {

    @Deprecated
    public static Object getOrDefault(FileConfiguration config, String path, Object defaultVal) {
        if (!config.contains(path)) {
            config.set(path, defaultVal);
            return defaultVal;
        }

        return config.get(path);
    }

}
