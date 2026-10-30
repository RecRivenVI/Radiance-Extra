package com.radiance.extra.compatibility.flywheel;

import com.radiance.api.render.InstanceScene;
import dev.engine_room.flywheel.api.backend.Backend;
import dev.engine_room.flywheel.lib.backend.SimpleBackend;
import net.minecraft.resources.ResourceLocation;

public final class RadianceFlywheelBackend {
    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath("radiance_extra", "instancing");
    private static Backend backend;

    private RadianceFlywheelBackend() {}

    public static long[] workCounters() {
        return RadianceFlywheelEngine.workCounters();
    }

    public static synchronized void register() {
        if (backend != null || Backend.REGISTRY.get(ID) != null) return;
        backend =
                SimpleBackend.builder()
                        .engineFactory(RadianceFlywheelEngine::new)
                        .priority(2_000)
                        .supported(RadianceFlywheelBackend::nativeSupported)
                        .register(ID);
    }

    /** There is no OpenGL world backend while Radiance owns the window and world rendering. */
    public static ResourceLocation translatePreference(
            ResourceLocation configured, boolean active) {
        return active ? ID : configured;
    }

    public static Backend resolvePreference(Backend configured) {
        ResourceLocation original = Backend.REGISTRY.getId(configured);
        ResourceLocation translated = translatePreference(original, nativeSupported());
        if (java.util.Objects.equals(original, translated)) return configured;
        Backend replacement = Backend.REGISTRY.get(translated);
        if (replacement == null) {
            throw new IllegalStateException("Radiance Vulkan Flywheel backend was not registered");
        }
        return replacement;
    }

    public static boolean nativeSupported() {
        try {
            return InstanceScene.supported();
        } catch (LinkageError | RuntimeException unavailable) {
            return false;
        }
    }
}
