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
    private RuntimeHeadBinding() { }

    public static Optional<ModelBone> install(ActiveModel model, Settings settings) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(settings, "settings");
        var type = BoneBehaviorTypes.HEAD;
        // ME installs a hidden, forced HEAD placeholder on ordinary bones too.
        // Only a visible HEAD behavior represents an authored h_/hi_ binding.
        if (!settings.enabled() || model.getBones().values().stream()
                .anyMatch(bone -> bone.getBoneBehavior(type).filter(behavior -> !behavior.isHidden()).isPresent())) {
            return Optional.empty(); // Preserve authored h_/hi_ behavior and pivots.
        }
        for (String name : settings.boneNames()) {
            Optional<ModelBone> match = model.getBones().values().stream()
                    .filter(bone -> name.equalsIgnoreCase(bone.getBoneId())).findFirst();
            if (match.isEmpty() || !type.test(match.get().getImmutableBoneBehaviors().keySet())) continue;
            ModelBone bone = match.get();
            // Rotate the parent before evaluating children. Hair and ears
            // inherit this rotation while keeping their local animation offsets.
            var data = new BoneBehaviorData(Map.of("local", true, "inherited", true));
            attach(bone, type, data);
            refreshChildren(bone);
            return match;
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
            child.getBoneBehavior(BoneBehaviorTypes.HEAD).ifPresent(behavior -> behavior.onParentSwap(parent));
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
