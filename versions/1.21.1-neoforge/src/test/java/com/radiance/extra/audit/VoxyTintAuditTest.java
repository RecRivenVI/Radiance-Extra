package com.radiance.extra.audit;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class VoxyTintAuditTest {
    @Test
    void verifiesAllRealSourceTintClassesAndRejectsMissingPalette() {
        int[] source = new int[48];
        for (int i = 0; i < 3; i++) source[i * 16 + 13] = 0x56585054;
        source[6] = 2;
        source[7] = -1;
        source[22] = 1;
        source[23] = 0xff619961;
        source[38] = 3;
        source[39] = 0;
        var result = VoxyTintAudit.inspect(source, 3, 1);
        assertEquals(1, result.get("untintedFluidHosts"));
        assertEquals(1, result.get("constantTintModels"));
        assertEquals(1, result.get("ownBiomeTintModels"));
        assertThrows(IllegalStateException.class, () -> VoxyTintAudit.inspect(source, 3, 0));
        assertThrows(IllegalStateException.class, () -> VoxyTintAudit.inspect(source, 2, 1));
    }
}
