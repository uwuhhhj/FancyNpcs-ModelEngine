package com.fancyinnovations.fancynpcsmodel.main;

import com.fancyinnovations.fancynpcsmodel.commands.fancynpcsmodel.FNMConfigCMD;
import com.fancyinnovations.fancynpcsmodel.commands.fancynpcsmodel.FNMVersionCMD;
import com.fancyinnovations.fancynpcsmodel.commands.npc.CustomModelCMD;
import com.fancyinnovations.fancynpcsmodel.commands.npc.PlayAnimationCMD;
import com.fancyinnovations.fancynpcsmodel.config.FancyNpcsModelConfigImpl;
import com.fancyinnovations.fancynpcsmodel.fancynpcshook.CustomModelAttribute;
import com.fancyinnovations.fancynpcsmodel.fancynpcshook.PlayAnimationLoopAction;
import com.fancyinnovations.fancynpcsmodel.fancynpcshook.PlayAnimationOnceAction;
import com.fancyinnovations.fancynpcsmodel.listeners.NpcInteractListener;
import com.fancyinnovations.fancynpcsmodel.listeners.NpcRemoveListener;
import com.fancyinnovations.fancynpcsmodel.metrics.FNMMetrics;
import com.fancyinnovations.fancynpcsmodel.providers.ModelProvider;
import com.fancyinnovations.fancynpcsmodel.providers.ModelProviderRegistry;
import de.oliver.fancyanalytics.logger.ExtendedFancyLogger;
import de.oliver.fancyanalytics.logger.LogLevel;
import de.oliver.fancyanalytics.logger.appender.Appender;
import de.oliver.fancyanalytics.logger.appender.ConsoleAppender;
import de.oliver.fancyanalytics.logger.appender.JsonAppender;
import de.oliver.fancylib.VersionConfig;
import de.oliver.fancylib.logging.PluginMiddleware;
import de.oliver.fancylib.translations.Language;
import de.oliver.fancylib.translations.TextConfig;
import de.oliver.fancylib.translations.Translator;
import de.oliver.fancylib.versionFetcher.FancySpacesVersionFetcher;
import de.oliver.fancylib.versionFetcher.VersionFetcher;
import de.oliver.fancynpcs.api.FancyNpcsPlugin;
import de.oliver.fancynpcs.api.Npc;
import de.oliver.fancynpcs.api.NpcAttribute;
import de.oliver.fancynpcs.api.actions.NpcAction;
import de.oliver.fancynpcs.api.events.NpcsLoadedEvent;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.bukkit.Bukkit;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import static java.util.concurrent.CompletableFuture.supplyAsync;

public class FancyNpcsModelPlugin extends JavaPlugin implements Listener {

    private static FancyNpcsModelPlugin INSTANCE;
    private static final boolean FOLIA = isFolia();
    private final ExtendedFancyLogger fancyLogger;

    private FancyNpcsModelConfigImpl fancyNpcsModelConfig;
    private VersionFetcher versionFetcher;
    private VersionConfig versionConfig;
    private Translator translator;
    private FNMMetrics metrics;
    private FancyNpcsPlugin fancyNpcs;
    private NpcAttribute modelAttribute;
    private PlayAnimationOnceAction onceAction;
    private PlayAnimationLoopAction loopAction;
    private BukkitTask restoreTask;

    public FancyNpcsModelPlugin() {
        INSTANCE = this;

        Appender consoleAppender = new ConsoleAppender("[{loggerName}] ({threadName}) {logLevel}: {message}");
        String date = new SimpleDateFormat("yyyy-MM-dd").format(new Date(System.currentTimeMillis()));
        File logsFile = new File("plugins/FancyNpcsModel/logs/FNM-logs-" + date + ".txt");
        if (!logsFile.exists()) {
            try {
                logsFile.getParentFile().mkdirs();
                logsFile.createNewFile();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        JsonAppender jsonAppender = new JsonAppender(false, false, true, logsFile.getPath());
        this.fancyLogger = new ExtendedFancyLogger(
                "FancyNpcsModel",
                LogLevel.INFO,
                List.of(consoleAppender, jsonAppender),
                List.of(new PluginMiddleware(this))
        );
    }

    public static FancyNpcsModelPlugin get() {
        return INSTANCE;
    }

    @Override
    public void onLoad() {
        this.fancyLogger.info("Loading FancyNpcsModel version %s...".formatted(getDescription().getVersion()));

        // Config
        this.fancyNpcsModelConfig = new FancyNpcsModelConfigImpl();
        this.fancyNpcsModelConfig.init();
        this.fancyNpcsModelConfig.reload();

        LogLevel logLevel;
        try {
            logLevel = LogLevel.valueOf(this.fancyNpcsModelConfig.getLogLevel());
        } catch (IllegalArgumentException e) {
            logLevel = LogLevel.INFO;
        }
        this.fancyLogger.setCurrentLevel(logLevel);

        // Version checking
        this.versionFetcher = new FancySpacesVersionFetcher("FancyNpcsModel");
        this.versionConfig = new VersionConfig(this, this.versionFetcher);
        this.versionConfig.load();

        // Translator
        registerTranslator();

        // Metrics
        this.metrics = new FNMMetrics();

        this.fancyLogger.info("Successfully loaded FancyNpcsModel version %s".formatted(getDescription().getVersion()));
    }

    @Override
    public void onEnable() {
        this.fancyLogger.info("Enabling FancyNpcsModel version %s...".formatted(getDescription().getVersion()));

        if (FOLIA) {
            this.fancyLogger.error("FancyNpcsModel requires the Paper main-thread scheduler; Folia is not supported.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("FancyNpcs")) {
            this.fancyLogger.error("FancyNpcs must be enabled before FancyNpcsModel.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        this.fancyNpcs = FancyNpcsPlugin.get();
        if (this.fancyNpcs == null) {
            this.fancyLogger.error("The installed FancyNpcs plugin does not expose the v2 API.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        if (!this.fancyNpcsModelConfig.areVersionNotificationsMuted()) {
            checkForNewerVersion();
        }
        if (this.versionConfig.isDevelopmentBuild()) {
            this.fancyLogger.warn("""
                    
                    --------------------------------------------------
                    You are using a development build of FancyNpcsModel.
                    Please be aware that there might be bugs in this version.
                    If you find any bugs, please report them on our discord server (https://discord.gg/ZUgYCEJUEx).
                    Read more about the risks of using a development build here: https://fancyinnovations.com/docs/general/development-guidelines/versioning#build
                    --------------------------------------------------
                    """);
        }

        ModelProviderRegistry.init(this.fancyLogger);
        if (ModelProviderRegistry.isEmpty()) {
            this.fancyLogger.error("""

                    --------------------------------------------------
                    No supported model plugin was found.
                    Please install BetterModel and/or ModelEngine and restart the server.
                    You can download BetterModel here: https://modrinth.com/plugin/bettermodel/versions?c=release
                    You can download ModelEngine here: https://mythiccraft.io/index.php?resources/model-engine%E2%80%94ultimate-entity-model-manager.389/
                    --------------------------------------------------
                    """);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // Register attributes and actions before FancyNpcs validates persistent
        // NPC data; restore already-loaded NPCs through the same event path.
        NpcAttribute existing = this.fancyNpcs.getAttributeManager().getAttributeByName(EntityType.PLAYER, CustomModelAttribute.ATTRIBUTE_NAME);
        if (existing != null && existing != this.modelAttribute) {
            this.fancyLogger.error("custom_model is already registered. Remove duplicate addons and restart the server.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        if (this.modelAttribute == null) {
            this.modelAttribute = CustomModelAttribute.getModelAttribute();
        }
        if (existing == null) {
            this.fancyNpcs.getAttributeManager().registerAttribute(this.modelAttribute);
        }
        this.onceAction = new PlayAnimationOnceAction();
        this.loopAction = new PlayAnimationLoopAction();
        this.fancyNpcs.getActionManager().registerAction(this.onceAction);
        this.fancyNpcs.getActionManager().registerAction(this.loopAction);

        registerCommands();
        registerListeners();
        if (this.fancyNpcs.getNpcManager().isLoaded()) {
            queueModelRestore();
        }

        try {
            this.metrics.register();
            this.metrics.checkIfPluginVersionUpdated();
        } catch (RuntimeException error) {
            this.fancyLogger.warn("Metrics could not start; model integration remains enabled: " + error);
        }

        this.fancyLogger.info("Successfully enabled FancyNpcsModel version %s".formatted(getDescription().getVersion()));
    }

    @Override
    public void onDisable() {
        this.fancyLogger.info("Disabling FancyNpcsModel version %s...".formatted(getDescription().getVersion()));

        if (this.restoreTask != null) {
            this.restoreTask.cancel();
            this.restoreTask = null;
        }
        CustomModelAttribute.clearPending();
        try {
            if (!ModelProviderRegistry.isEmpty() && this.fancyNpcs != null) {
                for (Npc npc : List.copyOf(this.fancyNpcs.getNpcManager().getAllNpcs())) {
                    try {
                        if (CustomModelAttribute.hasAttribute(npc) || ModelProviderRegistry.getActiveProvider(npc) != null) {
                            CustomModelAttribute.removeModels(npc);
                        }
                    } catch (RuntimeException | LinkageError error) {
                        this.fancyLogger.error("Failed to release model for NPC " + npc.getData().getName() + ": " + error);
                    }
                }
            }
        } finally {
            ModelProviderRegistry.shutdown();
            if (this.fancyNpcs != null) {
                unregisterAction(this.onceAction);
                unregisterAction(this.loopAction);
            }
        }

        this.fancyLogger.info("Successfully disabled FancyNpcsModel version %s".formatted(getDescription().getVersion()));
    }

    private void unregisterAction(NpcAction action) {
        if (action == null) {
            return;
        }
        try {
            this.fancyNpcs.getActionManager().unregisterAction(action);
        } catch (RuntimeException | LinkageError error) {
            this.fancyLogger.error("Failed to unregister model animation action: " + error);
        }
    }

    private static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer", false, FancyNpcsModelPlugin.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    private void registerCommands() {
        // fancynpcsmodel commands
        this.fancyNpcs.registerCommand(FNMConfigCMD.INSTANCE);
        this.fancyNpcs.registerCommand(FNMVersionCMD.INSTANCE);

        // npc commands
        this.fancyNpcs.registerCommand(CustomModelCMD.INSTANCE);
        this.fancyNpcs.registerCommand(PlayAnimationCMD.INSTANCE);
    }

    private void registerListeners() {
        Bukkit.getPluginManager().registerEvents(new NpcInteractListener(), this);
        Bukkit.getPluginManager().registerEvents(new NpcRemoveListener(), this);
        Bukkit.getPluginManager().registerEvents(this, this);

        for (ModelProvider provider : ModelProviderRegistry.getProviders()) {
            Listener listener = provider.createListener();
            if (listener != null) {
                Bukkit.getPluginManager().registerEvents(listener, this);
            }
        }
    }

    @EventHandler
    public void onNpcsLoaded(NpcsLoadedEvent event) {
        queueModelRestore();
    }

    private void queueModelRestore() {
        if (this.restoreTask != null || !isEnabled()) {
            return;
        }
        this.restoreTask = Bukkit.getScheduler().runTask(this, () -> {
            this.restoreTask = null;
            if (!isEnabled() || this.fancyNpcs == null) {
                return;
            }
            for (Npc npc : List.copyOf(this.fancyNpcs.getNpcManager().getAllNpcs())) {
                CustomModelAttribute.restoreStoredModel(npc);
            }
        });
    }

    public void registerTranslator() {
        this.translator = new Translator(
                new TextConfig(
                        "#ffcc24", // color to highlight important information
                        "gray", // text color for regular messages
                        "#81E366",
                        "#E3CA66",
                        "#E36666",
                        "<color:#ba8813>[</color><gradient:#ffae00:#fffb00:#ffae00>FancyNpcsModel</gradient><color:#ba8813>]</color> <gray>"
                )
        );

        this.translator.loadLanguages(getDataFolder().getAbsolutePath());
        Language selectedLanguage = this.translator.getFallbackLanguage();
        for (Language language : this.translator.getLanguages()) {
            if (language.getLanguageName().equals(this.fancyNpcsModelConfig.getLanguage())) {
                selectedLanguage = language;
                break;
            }
        }
        this.translator.setSelectedLanguage(selectedLanguage);
    }

    private void checkForNewerVersion() {
        final ComparableVersion current = new ComparableVersion(this.versionConfig.getVersion());

        supplyAsync(getVersionFetcher()::fetchNewestVersion).thenApply(Objects::requireNonNull).whenComplete((newest, error) -> {
            if (error != null || newest.compareTo(current) <= 0) {
                return; // could not get the newest version or already on latest
            }

            this.fancyLogger.warn("""
                    
                    -------------------------------------------------------
                    You are not using the latest version of the FancyNpcsModel plugin.
                    Please update to the newest version (%s).
                    %s
                    -------------------------------------------------------
                    """.formatted(newest, getVersionFetcher().getDownloadUrl()));
        });
    }

    public ExtendedFancyLogger getFancyLogger() {
        return this.fancyLogger;
    }

    public FancyNpcsModelConfigImpl getFancyNpcsModelConfig() {
        return this.fancyNpcsModelConfig;
    }

    public VersionFetcher getVersionFetcher() {
        return this.versionFetcher;
    }

    public VersionConfig getVersionConfig() {
        return this.versionConfig;
    }

    public Translator getTranslator() {
        return this.translator;
    }

}
