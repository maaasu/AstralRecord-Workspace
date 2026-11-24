package io.github.maaasu.astralRecord.feature.network;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TooltipCapabilityProtocolTest {
    @Test
    void rejectsTheLegacyUnverifiedNotification() throws IOException {
        byte[] legacy = notification("item_tooltips_ready");

        assertFalse(BackendProtocol.isVerifiedItemTooltipsReady(legacy));
        assertFalse(BackendProtocol.isSkillTooltipsReady(legacy));
    }

    @Test
    void recognizesVerifiedAndSkillSpecificNotificationsSeparately() throws IOException {
        byte[] common = notification("item_tooltips_v1_verified_ready");
        byte[] skill = notification("skill_tooltips_v1_ready");

        assertTrue(BackendProtocol.isVerifiedItemTooltipsReady(common));
        assertFalse(BackendProtocol.isSkillTooltipsReady(common));
        assertFalse(BackendProtocol.isVerifiedItemTooltipsReady(skill));
        assertTrue(BackendProtocol.isSkillTooltipsReady(skill));
    }

    private static byte[] notification(String action) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(action);
        }
        return bytes.toByteArray();
    }
}
