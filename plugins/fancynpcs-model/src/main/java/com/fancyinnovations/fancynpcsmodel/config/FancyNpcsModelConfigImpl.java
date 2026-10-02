package com.fancyinnovations.fancynpcsmodel.config;

import com.fancyinnovations.config.Config;
import com.fancyinnovations.config.ConfigField;
import com.fancyinnovations.fancynpcsmodel.main.FancyNpcsModelPlugin;
import com.fancyinnovations.fancynpcsmodel.providers.modelengine.ModelEngineAnimationSettings;
import com.fancyinnovations.fancynpcsmodel.providers.modelengine.SmoothHeadTracking;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class FancyNpcsModelConfigImpl {

    public static final String LOG_LEVEL_PATH = "settings.logging.level";
    public static final String MUTE_VERSION_NOTIFICATION_PATH = "settings.logging.version_notification";

    public static final String LANGUAGE_PATH = "settings.language";

    private static final String CONFIG_FILE_PATH = "plugins/FancyNpcsModel/config.yml";

    private Config config;
    private volatile MotionSettings motionSettings;
    private static final String ME = "settings.modelengine.";

    public void init() {
        config = new Config(FancyNpcsModelPlugin.get().getFancyLogger(), CONFIG_FILE_PATH);

        config.addField(new ConfigField<>(
                LOG_LEVEL_PATH,
                "The log level for the plugin (DEBUG, INFO, WARN, ERROR).",
                false,
                "INFO",
                false,
                String.class
        ));

        config.addField(new ConfigField<>(
                MUTE_VERSION_NOTIFICATION_PATH,
                "Whether version notifications are muted.",
                false,
                false,
                false,
                Boolean.class
        ));

        config.addField(new ConfigField<>(
                LANGUAGE_PATH,
                "The language for the plugin.",
                false,
                "default",
                false,
                String.class
        ));

        field("head-tracking.enabled", true, Boolean.class, "Smoothly look at players when turn_to_player is enabled.");
        number("head-tracking.max-yaw-degrees", 65, "Maximum head yaw relative to the body's current direction.");
        number("head-tracking.max-pitch-degrees", 35, "Maximum head pitch in either direction.");
        number("head-tracking.yaw-speed-degrees-per-second", 180, "Maximum horizontal head turning speed.");
        number("head-tracking.pitch-speed-degrees-per-second", 120, "Maximum vertical head turning speed.");
        number("head-tracking.response-per-second", 8, "Exponential response rate; larger values respond faster.");
        number("head-tracking.dead-zone-degrees", .25, "Ignore tiny head angle changes.");
        number("head-tracking.switch-distance-ratio", .8, "Switch to another player only when meaningfully closer.");
        number("head-tracking.minimum-target-hold-seconds", .75, "Minimum duration to keep a valid look target.");
        number("head-tracking.lost-target-grace-seconds", .2, "Grace period before returning to the original direction.");
        number("head-tracking.exit-range-multiplier", 1.15, "Exit distance multiplier to avoid flickering at the range boundary.");
        field("body-follow.enabled", true, Boolean.class, "Let the body smoothly follow the head after a delay.");
        number("body-follow.start-angle-degrees", 35, "Start the body after the head turns this far from the shoulders.");
        number("body-follow.stop-angle-degrees", 2, "Stop following when the head and body are nearly aligned.");
        number("body-follow.delay-seconds", .3, "Time the head must stay beyond the start angle before the body follows.");
        number("body-follow.yaw-speed-degrees-per-second", 90, "Maximum body turning speed.");
        number("body-follow.response-per-second", 5, "Body smoothing response rate.");
        field("body-follow.pause-during-pose", true, Boolean.class, "Keep the body still while a manual pose is active; the head may still look around.");
        field("idle.enabled", true, Boolean.class, "Enable the concurrent ambient animation layers.");
        for (String state : List.of("idle", "walk", "jump", "death"))
            field("idle.default-states." + state, state, String.class, "Model animation used for the default " + state + " state.");
        field("idle.loop-animations", List.of("ribbon_sway", "tail_hair_sway", "blink"), List.class,
                "Parallel ambient loops, in increasing priority; missing animations are skipped.");
        field("idle.random-gestures.enabled", false, Boolean.class, "Optionally play occasional idle gestures.");
        field("idle.random-gestures.animations", List.of("smile", "nod"), List.class, "Animations to choose for occasional gestures.");
        field("idle.random-gestures.min-interval-ticks", 160, Integer.class, "Minimum ticks between random idle gestures.");
        field("idle.random-gestures.max-interval-ticks", 400, Integer.class, "Maximum ticks between random idle gestures.");
        number("animations.blend-in-seconds", .05, "Animation fade-in in seconds (0.05 is one server tick).");
        number("animations.blend-out-seconds", .05, "Animation fade-out in seconds.");
        number("animations.speed", 1, "Base, ambient and pose animation speed multiplier.");
        number("animations.gesture-speed", 1.35, "One-shot gesture speed multiplier.");
        field("animations.pose-animations", List.of("crouch_idle", "sit", "sleep", "climb_idle"), List.class,
                "Animations treated as poses at priority 5 (other manual actions use priority 4).");
        field("animations.head-tracking-pause-animations", List.of("sleep", "nod", "talk", "wave"), List.class,
                "Temporarily stop looking at players while these animations move the head.");
        field("animations.pause-random-gestures-during-pose", true, Boolean.class, "Do not add random gestures while a pose is held.");

    }

    public void reload() {
        config.reload();
        double in = value("animations.blend-in-seconds", 0, 5, .05);
        double out = value("animations.blend-out-seconds", 0, 5, .05);
        double speed = value("animations.speed", .05, 10, 1);
        var blend = new ModelEngineAnimationSettings.Blend(in, out, speed);
        List<ModelEngineAnimationSettings.LoopAnimation> loops = new ArrayList<>();
        List<String> names = names("idle.loop-animations");
        for (int i = 0; i < names.size(); i++)
            loops.add(new ModelEngineAnimationSettings.LoopAnimation(true, names.get(i), i + 1, blend));
        int min = Math.max(20, Math.min(72000, integer("idle.random-gestures.min-interval-ticks")));
        int max = Math.max(min, Math.min(72000, integer("idle.random-gestures.max-interval-ticks")));
        int gesturePriority = Math.max(4, names.size() + 1);
        var animation = new ModelEngineAnimationSettings(
                bool("idle.enabled"),
                new ModelEngineAnimationSettings.DefaultStates(text("idle.default-states.idle"), text("idle.default-states.walk"),
                        text("idle.default-states.jump"), text("idle.default-states.death"), blend),
                loops,
                new ModelEngineAnimationSettings.Motion(gesturePriority, new ModelEngineAnimationSettings.Blend(in, out,
                        value("animations.gesture-speed", .05, 10, 1.35))),
                new ModelEngineAnimationSettings.Motion(gesturePriority + 1, blend),
                Set.copyOf(names("animations.pose-animations")),
                Set.copyOf(names("animations.head-tracking-pause-animations")),
                bool("animations.pause-random-gestures-during-pose"),
                new ModelEngineAnimationSettings.RandomGestures(bool("idle.random-gestures.enabled"),
                        names("idle.random-gestures.animations"), min, max));
        double yawLimit = value("head-tracking.max-yaw-degrees", .1, 150, 65);
        double pitchLimit = value("head-tracking.max-pitch-degrees", .1, 89, 35);
        double bodyStart = value("body-follow.start-angle-degrees", .1, yawLimit, Math.min(35, yawLimit));
        var body = new SmoothHeadTracking.BodyFollow(bool("body-follow.enabled"), bodyStart,
                value("body-follow.stop-angle-degrees", 0, Math.max(0, bodyStart - .1), Math.min(2, bodyStart * .5)),
                value("body-follow.delay-seconds", 0, 30, .3),
                value("body-follow.yaw-speed-degrees-per-second", 1, 1440, 90),
                value("body-follow.response-per-second", .1, 100, 5), bool("body-follow.pause-during-pose"));
        var head = new SmoothHeadTracking.Config(
                yawLimit, pitchLimit,
                value("head-tracking.yaw-speed-degrees-per-second", 1, 1440, 180),
                value("head-tracking.pitch-speed-degrees-per-second", 1, 1440, 120),
                value("head-tracking.response-per-second", .1, 100, 8),
                Math.min(value("head-tracking.dead-zone-degrees", 0, 10, .25), Math.min(yawLimit, pitchLimit) * .5),
                value("head-tracking.switch-distance-ratio", .1, 1, .8),
                value("head-tracking.minimum-target-hold-seconds", 0, 30, .75),
                value("head-tracking.lost-target-grace-seconds", 0, 10, .2),
                value("head-tracking.exit-range-multiplier", 1, 3, 1.15), body);
        motionSettings = new MotionSettings(bool("head-tracking.enabled"), head, animation);
    }

    private <T> void field(String path, T defaultValue, Class<?> type, String description) {
        config.addField(new ConfigField<>(ME + path, description, false, defaultValue, false, (Class<T>) type));
    }

    private void number(String path, double defaultValue, String description) {
        field(path, defaultValue, Number.class, description);
    }

    private double value(String path, double min, double max, double fallback) {
        Number number = config.get(ME + path);
        double value = number == null ? fallback : number.doubleValue();
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }

    private boolean bool(String path) { return config.get(ME + path); }
    private int integer(String path) { return config.get(ME + path); }
    private String text(String path) {
        String value = ((String) config.get(ME + path)).trim();
        return value.isEmpty() ? path.substring(path.lastIndexOf('.') + 1) : value;
    }
    private List<String> names(String path) {
        List<?> raw = config.get(ME + path);
        return raw.stream().filter(String.class::isInstance).map(String.class::cast).map(String::trim)
                .filter(name -> !name.isEmpty()).distinct().toList();
    }

    public MotionSettings getMotionSettings() { return motionSettings; }
    public record MotionSettings(boolean headTrackingEnabled, SmoothHeadTracking.Config headTracking,
                                 ModelEngineAnimationSettings animations) { }

    public Config getConfig() {
        return config;
    }

    public String getLogLevel() {
        return config.get(LOG_LEVEL_PATH);
    }

    public boolean areVersionNotificationsMuted() {
        return config.get(MUTE_VERSION_NOTIFICATION_PATH);
    }

    public String getLanguage() {
        return config.get(LANGUAGE_PATH);
    }

}
