package io.github.maaasu.astralRecord.feature.player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerMsgResourceTest {

    /**
     * 引数のカラーコードは文字列とComponentの両方で色に変換し、プレーンテキストには残さない。
     */
    @Test
    void translatesArgumentColorCodesInStringAndComponent() {
        String key = PlayerMsgId.P_6608.getId();
        String argument = "&eColored Quest";
        String formatted = PlayerMsgResource.format(key, argument);
        Component component = PlayerMsgResource.formatComponent(key, argument);
        String legacyText = LegacyComponentSerializer.legacySection().serialize(component);
        String plainText = PlainTextComponentSerializer.plainText().serialize(component);

        assertAll(
            () -> assertTrue(formatted.contains("§eColored Quest")),
            () -> assertFalse(formatted.contains(argument)),
            () -> assertTrue(legacyText.contains("§eColored Quest")),
            () -> assertTrue(plainText.contains("Colored Quest")),
            () -> assertFalse(plainText.contains("&e"))
        );
    }

    /**
     * 先頭・後段どちらの色付き引数でも、続く本文の色と装飾を元に戻す。
     */
    @Test
    void restoresBodyStyleAfterColoredArguments() {
        assertAll(
            "色付き引数の挿入後",
            () -> assertTrue(PlayerMsgResource.format(PlayerMsgId.P_5292.getId(), "&c装備", 100, 100)
                .contains("§c装備§r§a ")),
            () -> assertTrue(PlayerMsgResource.format(PlayerMsgId.P_7094.getId(), "参加者", "&d&lダンジョン")
                .contains("§d§lダンジョン§r§a "))
        );
    }

    /**
     * 改行を含むテンプレートで引数を展開し、改行と置換値を保持する。
     */
    @Test
    void expandsArgumentInsideMultilineTemplate() {
        String dungeonName = "test-dungeon";
        String formatted = PlayerMsgResource.format(PlayerMsgId.P_7033.getId(), dungeonName);

        assertAll(
            () -> assertTrue(formatted.lines().findFirst().orElseThrow().contains(dungeonName)),
            () -> assertTrue(formatted.lines().count() > 1),
            () -> assertFalse(formatted.contains("{0}"))
        );
    }

}
