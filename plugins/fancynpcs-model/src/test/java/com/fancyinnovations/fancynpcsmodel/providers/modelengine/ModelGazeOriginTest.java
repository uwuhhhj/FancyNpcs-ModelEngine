package com.fancyinnovations.fancynpcsmodel.providers.modelengine;

import com.ticxo.modelengine.api.entity.Hitbox;
import com.ticxo.modelengine.api.generator.blueprint.BlueprintBone;
import com.ticxo.modelengine.api.generator.blueprint.ModelBlueprint;
import org.bukkit.Location;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ModelGazeOriginTest {
    private static final ModelGazeOrigin.Settings AUTO = new ModelGazeOrigin.Settings(List.of("Eyes", "Eye", "Head"), -1, 0, 0);

    @Test
    void originalFoxUsesVisualEyesAndLooksDownTowardStandingPlayer() {
        ModelBlueprint model = model(2.09875f);
        double height = ModelGazeOrigin.eyeHeight(model, .9, AUTO);
        assertEquals(1.888875, height, .000001);
        Location target = new Location(null, 0, 1.62, 3);
        ModelGazeOrigin.Angles oldFacing = ModelGazeOrigin.lookAtAngles(new Location(null, 0, 1.44 * .9, 0), target, AUTO);
        ModelGazeOrigin.Angles newFacing = ModelGazeOrigin.lookAtAngles(new Location(null, 0, height, 0), target, AUTO);
        assertNotNull(oldFacing);
        assertNotNull(newFacing);
        assertTrue(oldFacing.pitch() < -6, "Hitbox origin incorrectly looked up");
        assertEquals(5.121, newFacing.pitch(), .01, "Visual eyes must look slightly down");
    }

    @Test
    void secondFoxHasItsOwnEyeHeightRatherThanOneSharedConstant() {
        assertEquals(1.895625, ModelGazeOrigin.eyeHeight(model(2.10625f), .9, AUTO), .000001);
    }

    @Test
    void sameVisualEyeLevelIsHorizontalRegardlessOfNpcWorldAltitude() {
        double height = ModelGazeOrigin.eyeHeight(model(2.09875f), .9, AUTO);
        ModelGazeOrigin.Angles facing = ModelGazeOrigin.lookAtAngles(new Location(null, 10, 80 + height, 20),
                new Location(null, 13, 80 + height, 20), AUTO);
        assertNotNull(facing);
        assertEquals(0, facing.pitch(), .000001);
        assertEquals(270, facing.yaw(), .000001); // Bukkit's setDirection returns [0, 360).
    }

    @Test
    void scaledModelCanChangeFromLookingDownToLookingUp() {
        ModelBlueprint model = model(2.09875f);
        Location eye = new Location(null, 0, 1.62, 3);
        double small = ModelGazeOrigin.eyeHeight(model, .5, AUTO);
        double tall = ModelGazeOrigin.eyeHeight(model, 1, AUTO);
        ModelGazeOrigin.Angles smallFacing = ModelGazeOrigin.lookAtAngles(new Location(null, 0, small, 0), eye, AUTO);
        ModelGazeOrigin.Angles tallFacing = ModelGazeOrigin.lookAtAngles(new Location(null, 0, tall, 0), eye, AUTO);
        assertNotNull(smallFacing);
        assertNotNull(tallFacing);
        assertTrue(smallFacing.pitch() < 0);
        assertTrue(tallFacing.pitch() > 0);
    }

    @Test
    void explicitHeightAndHeightOffsetAreScaledExactlyOnce() {
        ModelGazeOrigin.Settings settings = new ModelGazeOrigin.Settings(List.of("Eyes"), 1.7, .1, 0);
        assertEquals(1.62, ModelGazeOrigin.eyeHeight(model(2.09875f), .9, settings), .000001);
        assertEquals(.9, ModelGazeOrigin.eyeHeight(model(2.09875f), .5, settings), .000001);
    }

    @Test
    void boneMatchingIsCaseInsensitiveAndMissingOrInvalidEyesFallBackToHead() {
        ModelBlueprint model = model(Float.NaN);
        assertEquals(1.98125, ModelGazeOrigin.eyeHeight(model, 1, AUTO), .000001);
        model.getFlatMap().get("Head").setName("hEaD");
        ModelGazeOrigin.Settings settings = new ModelGazeOrigin.Settings(List.of("head"), -1, 0, 0);
        assertEquals(1.98125, ModelGazeOrigin.eyeHeight(model, 1, settings), .000001);
    }

    @Test
    void modelsWithoutMatchingBonesRetainHitboxFallback() {
        ModelBlueprint model = new ModelBlueprint();
        model.setMainHitbox(new Hitbox(.6, 1.8, .6, 1.44));
        model.constructFlatBoneMap(null);
        assertEquals(1.296, ModelGazeOrigin.eyeHeight(model, .9, AUTO), .000001);
        ModelGazeOrigin.Angles facing = ModelGazeOrigin.lookAtAngles(new Location(null, 0, 0, 0), new Location(null, 0, 3, 0), AUTO);
        assertNotNull(facing);
        assertTrue(facing.pitch() < 0);
    }

    @Test
    void positivePitchCorrectionLowersGazeAndTrackingLimitsStillApply() {
        ModelGazeOrigin.Settings settings = new ModelGazeOrigin.Settings(List.of("Eyes"), -1, 0, 10);
        ModelGazeOrigin.Angles facing = ModelGazeOrigin.lookAtAngles(new Location(null, 0, 1.62, 0), new Location(null, 0, 1.62, 3), settings);
        assertNotNull(facing);
        assertEquals(10, facing.pitch(), .000001);
        SmoothHeadTracking tracker = new SmoothHeadTracking(SmoothHeadTracking.Config.defaults());
        UUID id = UUID.randomUUID();
        ModelGazeOrigin.Angles targetFacing = ModelGazeOrigin.lookAtAngles(new Location(null, 0, 3, 0), new Location(null, 0, 0, .1), settings);
        assertNotNull(targetFacing);
        SmoothHeadTracking.Pose pose = null;
        for (int i = 0; i < 100; i++) {
            pose = tracker.tick(0, 0, 6,
                    List.of(new SmoothHeadTracking.Candidate(id, 9, 0, targetFacing.pitch())), .05);
        }
        assertNotNull(pose);
        assertEquals(35, pose.headPitch(), .25);
    }

    @Test
    void coincidentEyesHaveNoUndefinedFacingAndInvalidSettingsAreRejected() {
        Location point = new Location(null, 0, 1.62, 0);
        assertNull(ModelGazeOrigin.lookAtAngles(point, point, AUTO));
        assertThrows(IllegalArgumentException.class, () -> ModelGazeOrigin.eyeHeight(model(2), Double.NaN, AUTO));
        assertThrows(IllegalArgumentException.class, () -> ModelGazeOrigin.eyeHeight(model(2), 0, AUTO));
        assertThrows(IllegalArgumentException.class, () -> new ModelGazeOrigin.Settings(List.of("Eyes"), -2, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ModelGazeOrigin.Settings(List.of("Eyes"), -1, Double.NaN, 0));
    }

    private static ModelBlueprint model(float eyesY) {
        ModelBlueprint model = new ModelBlueprint();
        bone(model, "Eyes", eyesY);
        bone(model, "Head", 1.98125f);
        model.constructFlatBoneMap(null);
        return model;
    }

    private static void bone(ModelBlueprint model, String name, float y) {
        BlueprintBone bone = new BlueprintBone();
        bone.setName(name);
        bone.setGlobalPosition(new Vector3f(0, y, 0));
        model.getBones().put(name, bone);
    }
}
