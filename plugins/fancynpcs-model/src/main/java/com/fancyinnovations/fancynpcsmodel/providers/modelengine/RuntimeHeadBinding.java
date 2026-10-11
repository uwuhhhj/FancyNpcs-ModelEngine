package com.fancyinnovations.fancynpcsmodel.providers.modelengine;

import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.model.bone.ModelBone;
import com.ticxo.modelengine.api.model.bone.behavior.BoneBehaviorData;
import com.ticxo.modelengine.api.model.bone.behavior.BoneBehavior;
import com.ticxo.modelengine.api.model.bone.behavior.BoneBehaviorType;
import com.ticxo.modelengine.api.model.bone.BoneBehaviorTypes;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Adds head rotation to an NPC's live bones; never changes the shared blueprint. */
public final class RuntimeHeadBinding {
    private RuntimeHeadBinding() {
    }

    public static Optional<ModelBone> install(ActiveModel model, Settings settings) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(settings, "settings");
        BoneBehaviorType<?> type = BoneBehaviorTypes.HEAD;
        // ME installs a hidden, forced HEAD placeholder on ordinary bones too.
        // Only a visible HEAD behavior represents an authored h_/hi_ binding.
        if (!settings.enabled() || model.getBones().values().stream()
                .anyMatch(bone -> {
                    BoneBehavior behavior = bone.getBoneBehavior(type).orElse(null);
                    return behavior != null && !behavior.isHidden();
                })) {
            return Optional.empty();
        }
        for (String name : settings.boneNames()) {
            ModelBone bone = model.getBones().values().stream()
                    .filter(candidate -> name.equalsIgnoreCase(candidate.getBoneId())).findFirst().orElse(null);
            if (bone == null || !type.test(bone.getImmutableBoneBehaviors().keySet())) {
                continue;
            }
            // Rotate the parent before evaluating children. Hair and ears
            // inherit this rotation while keeping their local animation offsets.
            BoneBehaviorData data = new BoneBehaviorData(Map.of("local", true, "inherited", true));
            attach(bone, type, data);
            refreshChildren(bone);
            return Optional.of(bone);
        }
        return Optional.empty();
    }

    private static <T extends BoneBehavior> void attach(ModelBone bone, BoneBehaviorType<T> type, BoneBehaviorData data) {
        T behavior = type.getBehaviorProvider().create(bone, type, data);
        bone.removeBoneBehavior(type);
        bone.addBoneBehavior(behavior);
    }

    private static void refreshChildren(ModelBone parent) {
        for (ModelBone child : parent.getChildren().values()) {
            BoneBehavior behavior = child.getBoneBehavior(BoneBehaviorTypes.HEAD).orElse(null);
            if (behavior != null) {
                behavior.onParentSwap(parent);
            }
            refreshChildren(child);
        }
    }

    public record Settings(boolean enabled, List<String> boneNames) {
        public Settings {
            Objects.requireNonNull(boneNames, "boneNames");
            boneNames = boneNames.stream().map(String::trim).filter(name -> !name.isEmpty()).distinct().toList();
        }
    }
}
