package io.github.maaasu.astralRecord.infrastructure.util;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ColorCodeUtilTest {

    @Test
    void toComponentTranslatesLegacyColorCodesAndAppliesFallbackColor() {
        var component = ColorCodeUtil.toComponent("&c警告 &f詳細", "fallback", NamedTextColor.GRAY);

        assertEquals("§c警告 §f詳細", LegacyComponentSerializer.legacySection().serialize(component));
    }

    @Test
    void toComponentUsesFallbackWhenTheSourceIsBlank() {
        var component = ColorCodeUtil.toComponent(" ", "&a代替", NamedTextColor.GRAY);

        assertEquals("§a代替", LegacyComponentSerializer.legacySection().serialize(component));
    }

    @Test
    void toComponentAppliesFallbackColorWhenTheSourceHasNoColorCode() {
        var component = ColorCodeUtil.toComponent("通常表示", "fallback", NamedTextColor.GRAY);

        assertEquals("§7通常表示", LegacyComponentSerializer.legacySection().serialize(component));
    }
}
