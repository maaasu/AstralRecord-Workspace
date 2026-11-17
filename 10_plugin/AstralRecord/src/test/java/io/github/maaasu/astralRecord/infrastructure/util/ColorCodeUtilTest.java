package io.github.maaasu.astralRecord.infrastructure.util;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ColorCodeUtilTest {

    /**
     * 明示色を維持し、色のない本文には既定色を、空白の本文には代替文を使う。
     */
    @Test
    void translatesTextAndAppliesFallbacks() {
        assertAll(
            () -> assertComponent("&c警告 &f詳細", "fallback", "§c警告 §f詳細"),
            () -> assertComponent(" ", "&a代替", "§a代替"),
            () -> assertComponent("通常表示", "fallback", "§7通常表示")
        );
    }

    private static void assertComponent(String source, String fallback, String expected) {
        var component = ColorCodeUtil.toComponent(source, fallback, NamedTextColor.GRAY);

        assertEquals(expected, LegacyComponentSerializer.legacySection().serialize(component));
    }
}
