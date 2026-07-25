package io.github.maaasu.astralRecord.feature.player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerMsgResourceTest {

    @Test
    void formatTranslatesColorCodesInMessageArguments() {
        String formatted = PlayerMsgResource.format(PlayerMsgId.P_6608.getId(), "&eColored Quest");

        assertTrue(formatted.contains("\u00a7eColored Quest"));
        assertFalse(formatted.contains("&eColored Quest"));
    }

    @Test
    void formatComponentDoesNotLeaveAmpersandColorCodesInPlainText() {
        Component component = PlayerMsgResource.formatComponent(PlayerMsgId.P_6608.getId(), "&eColored Quest");
        String legacyText = LegacyComponentSerializer.legacySection().serialize(component);
        String plainText = PlainTextComponentSerializer.plainText().serialize(component);

        assertTrue(legacyText.contains("\u00a7eColored Quest"));
        assertTrue(plainText.contains("Colored Quest"));
        assertFalse(plainText.contains("&e"));
    }

    @Test
    void damageDetailMessageFormatsHitChanceAndCriticalMarker() {
        String formatted = PlayerMsgResource.format(
                PlayerMsgId.P_5350.getId(),
                "Test Mob",
                "25.0",
                "0.0",
                "近接",
                "無属性",
                "92.0",
                "95.0",
                "3.0",
                " &eCRITICAL"
        );

        assertTrue(formatted.contains("Test Mob"));
        assertTrue(formatted.contains("92.0%"));
        assertTrue(formatted.contains("命中 95.0 - 回避 3.0"));
        assertTrue(formatted.contains("\u00a7eCRITICAL"));
    }

    @Test
    void displayAndCommandMessagesAreLoadedFromPlayerProperties() {
        String accountMode = PlayerMsgResource.format(PlayerMsgId.P_5332.getId(), "Alice", "PLAYER");
        String displayAudit = PlayerMsgResource.format(PlayerMsgId.P_5055.getId(), 4, 3, 2, 1);

        assertTrue(accountMode.contains("Alice"));
        assertTrue(accountMode.contains("PLAYER"));
        assertTrue(displayAudit.contains("スキルツリー表示="));
        assertTrue(PlayerMsgResource.getMessage(PlayerMsgId.P_5325.getId()).contains("プレイヤー設定機能"));
        assertTrue(PlayerMsgResource.getMessage(PlayerMsgId.P_5280.getId()).contains("オートセーブ"));
        assertTrue(PlayerMsgResource.getMessage(PlayerMsgId.P_5600.getId()).contains("ガイド"));
        assertTrue(PlayerMsgResource.format(PlayerMsgId.P_5624.getId(), 3).contains("3"));
    }
}
