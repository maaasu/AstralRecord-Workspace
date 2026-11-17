package io.github.maaasu.astralRecord.feature.playerclass.view;

import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClassLevelDisplayTest {
    /**
     * 最大到達時はLv.を維持し、数値部分だけを赤太字の小文字maxへ置換する。
     */
    @Test
    void rendersMaximumLevelAsLvMax() {
        assertEquals(
            Component.text("Lv.", NamedTextColor.GRAY)
                .append(Component.text("max", NamedTextColor.RED, TextDecoration.BOLD)),
            ClassLevelDisplay.component(100, true, NamedTextColor.GRAY, NamedTextColor.YELLOW));
        assertEquals(
            ColorCodeUtil.GRAY + "Lv." + ColorCodeUtil.RED + ColorCodeUtil.BOLD + "max",
            ClassLevelDisplay.legacy(100, true));
    }

    /**
     * 最大未到達時は既存のLv.数値表示と色を維持する。
     */
    @Test
    void preservesNumericLevelBelowMaximum() {
        assertEquals(
            Component.text("Lv.", NamedTextColor.GRAY)
                .append(Component.text(42, NamedTextColor.YELLOW)),
            ClassLevelDisplay.component(42, false, NamedTextColor.GRAY, NamedTextColor.YELLOW));
        assertEquals(
            ColorCodeUtil.GRAY + "Lv." + ColorCodeUtil.YELLOW + "42",
            ClassLevelDisplay.legacy(42, false));
    }
}
