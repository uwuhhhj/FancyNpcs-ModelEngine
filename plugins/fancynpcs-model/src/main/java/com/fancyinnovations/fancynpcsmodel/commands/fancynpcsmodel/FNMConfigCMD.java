package com.fancyinnovations.fancynpcsmodel.commands.fancynpcsmodel;

import com.fancyinnovations.config.ConfigField;
import com.fancyinnovations.fancynpcsmodel.utils.FancyContext;
import de.oliver.fancyanalytics.logger.LogLevel;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.Permission;

import java.util.Collection;
import java.util.Comparator;

public class FNMConfigCMD extends FancyContext {

    public static final FNMConfigCMD INSTANCE = new FNMConfigCMD();

    private FNMConfigCMD() {
    }

    @Command("fancynpcsmodel config reload")
    @Permission("fancynpcsmodel.commands.fancynpcsmodel.config.reload")
    public void configReload(
            CommandSender sender
    ) {
        super.config.reload();
        super.logger.setCurrentLevel(LogLevel.valueOf(super.config.getLogLevel()));
        super.plugin.registerTranslator();

        super.translator.translate("commands.fancynpcsmodel.config.reload.success")
                .withPrefix()
                .send(sender);
    }

    @Command("fancynpcsmodel config show")
    @Permission("fancynpcsmodel.commands.fancynpcsmodel.config.show")
    public void show(
            CommandSender sender
    ) {
        Collection<ConfigField<?>> fields = super.config.getConfig().getFields().values()
                .stream()
                .sorted(Comparator.comparing(ConfigField::path))
                .toList();

        super.translator.translate("commands.fancynpcsmodel.config.show.settings_header")
                .withPrefix()
                .send(sender);

        for (ConfigField<?> field : fields) {
            if (!field.path().startsWith("settings.")) {
                continue;
            }

            super.translator.translate("commands.fancynpcsmodel.config.show.entry")
                    .replace("path", field.path().substring("settings.".length()))
                    .replace("value", super.config.getConfig().get(field.path()).toString())
                    .replace("default", String.valueOf(field.defaultValue()))
                    .send(sender);
        }

        sender.sendMessage(" ");

        super.translator.translate("commands.fancynpcsmodel.config.show.experimental_header")
                .withPrefix()
                .send(sender);

        for (ConfigField<?> field : fields) {
            if (!field.path().startsWith("experimental_features.")) {
                continue;
            }

            super.translator.translate("commands.fancynpcsmodel.config.show.entry")
                    .replace("path", field.path().substring("experimental_features.".length()))
                    .replace("value", super.config.getConfig().get(field.path()).toString())
                    .replace("default", String.valueOf(field.defaultValue()))
                    .send(sender);
        }

    }

}
