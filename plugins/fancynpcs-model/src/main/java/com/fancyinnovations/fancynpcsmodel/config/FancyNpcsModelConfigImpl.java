package com.fancyinnovations.fancynpcsmodel.config;

import com.fancyinnovations.config.Config;
import com.fancyinnovations.config.ConfigField;
import com.fancyinnovations.fancynpcsmodel.main.FancyNpcsModelPlugin;
import com.fancyinnovations.fancynpcsmodel.providers.modelengine.ModelEngineAnimationSettings;
import com.fancyinnovations.fancynpcsmodel.providers.modelengine.SmoothHeadTracking;
import com.fancyinnovations.fancynpcsmodel.providers.modelengine.RuntimeHeadBinding;

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
                "日志级别：DEBUG、INFO、WARN、ERROR。",
                false,
                "INFO",
                false,
                String.class
        ));

        config.addField(new ConfigField<>(
                MUTE_VERSION_NOTIFICATION_PATH,
                "是否屏蔽版本通知；true 屏蔽，false 显示。",
                false,
                false,
                false,
                Boolean.class
        ));

        config.addField(new ConfigField<>(
                LANGUAGE_PATH,
                "插件消息使用的语言文件。",
                false,
                "default",
                false,
                String.class
        ));

        field("head-tracking.enabled", true, Boolean.class, "在 NPC 开启 turn_to_player 时平滑看向玩家。");
        field("head-tracking.runtime-head-bone.enabled", true, Boolean.class,
                "模型没有头部行为时，给当前 NPC 的骨骼补上头部追踪；不会修改蓝图或资源包。");
        field("head-tracking.runtime-head-bone.bone-names", List.of("Head", "AllHead"), List.class,
                "按顺序匹配第一个头部骨骼，忽略大小写；头发、耳朵等子骨骼继承旋转。");
        number("head-tracking.max-yaw-degrees", 65, "头部相对身体当前朝向的最大水平偏转，单位为度。");
        number("head-tracking.max-pitch-degrees", 35, "最大抬头或低头角度，单位为度。");
        number("head-tracking.yaw-speed-degrees-per-second", 180, "头部水平转动的最高速度，单位为度/秒。");
        number("head-tracking.pitch-speed-degrees-per-second", 120, "头部俯仰转动的最高速度，单位为度/秒。");
        number("head-tracking.response-per-second", 8, "头部平滑响应系数，越大反应越快。");
        number("head-tracking.dead-zone-degrees", .25, "忽略小于此值的头部角度变化，单位为度。");
        number("head-tracking.switch-distance-ratio", .8, "新玩家足够近才切换；0.8 表示新距离小于当前距离的 80%。");
        number("head-tracking.minimum-target-hold-seconds", .75, "切换目标前保持当前有效目标的最短时间，单位为秒。");
        number("head-tracking.lost-target-grace-seconds", .2, "目标消失后等待多久再让头部回正，单位为秒。");
        number("head-tracking.exit-range-multiplier", 1.15, "目标退出距离倍率，减少范围边缘的来回切换。");
        field("body-follow.enabled", true, Boolean.class, "头部先转动，身体延迟后平滑跟上。");
        number("body-follow.start-angle-degrees", 35, "头部相对肩膀偏转达到此角度后开始计时，单位为度。");
        number("body-follow.stop-angle-degrees", 2, "头身角度差缩小到此值时停止转身，单位为度。");
        number("body-follow.delay-seconds", .3, "头部持续超过起转角度多久才开始转身，单位为秒。");
        number("body-follow.yaw-speed-degrees-per-second", 90, "身体水平转动的最高速度，单位为度/秒。");
        number("body-follow.response-per-second", 5, "身体平滑响应系数，越大反应越快。");
        field("body-follow.pause-during-pose", true, Boolean.class, "手动姿态期间暂停转身，头部仍可按设置追踪。");
        field("idle.enabled", true, Boolean.class, "开启并行循环微动作；关闭时也停止随机手势，基础待机保留。");
        for (String state : List.of("idle", "walk", "jump", "death"))
            field("idle.default-states." + state, state, String.class, "默认 " + state + " 状态使用的模型动画名。");
        field("idle.loop-animations", List.of("ribbon_sway", "tail_hair_sway", "blink"), List.class,
                "按列表顺序分配递增优先级的循环微动作；缺失动画会跳过。");
        field("idle.random-gestures.enabled", false, Boolean.class, "是否偶尔自动播放随机待机手势。");
        field("idle.random-gestures.animations", List.of("smile", "nod"), List.class, "随机手势候选动画，只选择模型中存在的动画。");
        field("idle.random-gestures.min-interval-ticks", 160, Integer.class, "随机手势最短间隔，20 tick 在 20 TPS 时为 1 秒。");
        field("idle.random-gestures.max-interval-ticks", 400, Integer.class, "随机手势最长间隔，单位为 tick。");
        number("animations.blend-in-seconds", .05, "动画进入混合时间，单位为秒；0.05 秒约为一个服务端 tick。");
        number("animations.blend-out-seconds", .05, "动画退出混合时间，单位为秒。");
        number("animations.speed", 1, "基础动画、循环微动作和姿态的速度倍率。");
        number("animations.gesture-speed", 1.35, "普通手势动画的速度倍率。");
        field("animations.pose-animations", List.of("crouch_idle", "sit", "sleep", "climb_idle"), List.class,
                "视为姿态的动画；配合 --loop 保持最后一帧，优先级高于微动作和手势。");
        field("animations.head-tracking-pause-animations", List.of("sleep", "nod", "talk", "wave"), List.class,
                "这些手动动画播放期间暂停叠加玩家视线追踪。");
        field("animations.pause-random-gestures-during-pose", true, Boolean.class, "手动姿态期间暂停随机手势。");

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
        motionSettings = new MotionSettings(bool("head-tracking.enabled"), head, animation,
                new RuntimeHeadBinding.Settings(bool("head-tracking.runtime-head-bone.enabled"),
                        names("head-tracking.runtime-head-bone.bone-names")));
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
                                 ModelEngineAnimationSettings animations, RuntimeHeadBinding.Settings headBinding) { }

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
