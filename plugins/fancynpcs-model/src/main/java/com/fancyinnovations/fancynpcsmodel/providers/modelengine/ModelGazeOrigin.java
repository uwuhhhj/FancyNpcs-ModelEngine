package com.fancyinnovations.fancynpcsmodel.providers.modelengine;

import com.ticxo.modelengine.api.generator.blueprint.BlueprintBone;
import com.ticxo.modelengine.api.generator.blueprint.ModelBlueprint;
import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Visual gaze height is independent of the collision hitbox's eye height. */
public final class ModelGazeOrigin {
    private ModelGazeOrigin() { }

    public static double eyeHeight(ModelBlueprint blueprint, double verticalScale, Settings settings) {
        Objects.requireNonNull(blueprint, "blueprint");
        Objects.requireNonNull(settings, "settings");
        if (!Double.isFinite(verticalScale) || verticalScale <= 0)
            throw new IllegalArgumentException("verticalScale must be finite and positive");
        double height = settings.fixedHeightBlocks();
        if (height < 0) {
            height = blueprint.getMainHitbox().getEyeHeight();
            for (String name : settings.boneNames()) {
                BlueprintBone bone = blueprint.getFlatMap().get(name);
                if (bone == null) bone = blueprint.getFlatMap().values().stream()
                        .filter(candidate -> name.equalsIgnoreCase(candidate.getName())).findFirst().orElse(null);
                // Bind-pose positions use blocks. Reading the live rotated eye
                // here would feed last frame's tracking pose back into the target.
                if (bone != null && bone.getGlobalPosition() != null) {
                    double y = bone.getGlobalPosition().y;
                    if (Double.isFinite(y) && y > 0) { height = y; break; }
                }
            }
        }
        return Math.max(0, height + settings.heightOffsetBlocks()) * verticalScale;
    }

    public static Optional<Angles> lookAt(Location origin, Location playerEye, Settings settings) {
        Vector direction = playerEye.toVector().subtract(origin.toVector());
        if (direction.lengthSquared() <= .000001) return Optional.empty();
        Location facing = origin.clone().setDirection(direction);
        double pitch = Math.max(-90, Math.min(90, facing.getPitch() + settings.pitchOffsetDegrees()));
        return Optional.of(new Angles(facing.getYaw(), pitch));
    }

    public record Angles(double yaw, double pitch) { }

    public record Settings(List<String> boneNames, double fixedHeightBlocks, double heightOffsetBlocks,
                           double pitchOffsetDegrees) {
        public Settings {
            Objects.requireNonNull(boneNames, "boneNames");
            boneNames = boneNames.stream().map(String::trim).filter(name -> !name.isEmpty()).distinct().toList();
            if (!Double.isFinite(fixedHeightBlocks) || fixedHeightBlocks < -1
                    || !Double.isFinite(heightOffsetBlocks) || !Double.isFinite(pitchOffsetDegrees))
                throw new IllegalArgumentException("Gaze settings must be finite; fixed height is -1 for automatic or nonnegative");
        }
    }
}
