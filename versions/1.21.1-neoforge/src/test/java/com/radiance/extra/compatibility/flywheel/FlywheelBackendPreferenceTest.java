package com.radiance.extra.compatibility.flywheel;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class FlywheelBackendPreferenceTest {
    @Test
    void activeRadianceSelectsItsOnlyUsableBackendForEveryStoredPreference() {
        assertEquals("radiance_extra:instancing", RadianceFlywheelBackend.ID.toString());
        for (String name :
                new String[] {
                    "flywheel:indirect",
                    "flywheel:instancing",
                    "flywheel:off",
                    "other:custom",
                    "radiance:vulkan_instancing",
                    "radiance_extra:instancing"
                })
            assertEquals(
                    RadianceFlywheelBackend.ID,
                    RadianceFlywheelBackend.translatePreference(
                            ResourceLocation.parse(name), true));
        assertEquals(
                RadianceFlywheelBackend.ID,
                RadianceFlywheelBackend.translatePreference(null, true));
    }

    @Test
    void inactiveRadianceLeavesFlywheelSelectionUntouched() {
        for (String name :
                new String[] {"flywheel:off", "other:indirect", "radiance:vulkan_instancing"}) {
            ResourceLocation id = ResourceLocation.parse(name);
            assertSame(id, RadianceFlywheelBackend.translatePreference(id, false));
        }
        assertNull(RadianceFlywheelBackend.translatePreference(null, false));
    }
}
