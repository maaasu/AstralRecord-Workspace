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
    void damageDetailMessageFormatsCompactCalculationBreakdown() {
        String formatted = PlayerMsgResource.format(
                PlayerMsgId.P_5350.getId(),
                "&cHP125",
                "MEL",
                "FIR",
                "180",
                "80",
                "64",
                " RES25>15",
                "92",
                "95",
                "3",
                " &eCRIT"
        );

        assertTrue(formatted.contains("HP125"));
        assertTrue(formatted.contains("MEL/FIR"));
        assertTrue(formatted.contains("AP180 DEF80>64 RES25>15"));
        assertTrue(formatted.contains("H92"));
        assertTrue(formatted.contains("A95-E3"));
        assertTrue(formatted.contains("\u00a7eCRIT"));
        assertFalse(formatted.contains("Test Mob"));
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

    @Test
    void externalDataOperationCompletionMessagesIncludeElapsedMilliseconds() {
        String joinLoad = PlayerMsgResource.format(PlayerMsgId.P_5072.getId(), 123L);
        String autoSave = PlayerMsgResource.format(PlayerMsgId.P_5281.getId(), 456L);

        assertTrue(joinLoad.contains("(123ms)"));
        assertTrue(autoSave.contains("(456ms)"));
    }
}
