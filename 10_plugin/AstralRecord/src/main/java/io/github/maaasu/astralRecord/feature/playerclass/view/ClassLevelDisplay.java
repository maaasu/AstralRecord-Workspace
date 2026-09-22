package io.github.maaasu.astralRecord.feature.playerclass.view;

import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.jetbrains.annotations.NotNull;

/** クラスレベルを通常値または最大到達表記へ変換します。 */
public final class ClassLevelDisplay {
    private ClassLevelDisplay() {
    }

    /**
     * Adventure表示用のクラスレベルを生成します。
     *
     * @param level 現在クラスレベル
     * @param maximum 最大レベルへ到達済みか
     * @param normalColor 通常表示と {@code Lv.} に使用する色
     * @return 通常時は {@code Lv.<数値>}、最大時は {@code Lv.max} のComponent
     */
    public static @NotNull Component component(
        int level,
        boolean maximum,
        @NotNull NamedTextColor normalColor
    ) {
        return component(level, maximum, normalColor, normalColor);
    }

    /**
     * 接頭辞と通常数値の色を分けたAdventure表示を生成します。
     *
     * @param level 現在クラスレベル
     * @param maximum 最大レベルへ到達済みか
     * @param prefixColor {@code Lv.} に使用する色
     * @param valueColor 最大未到達時の数値に使用する色
     * @return 通常時は {@code Lv.<数値>}、最大時は {@code Lv.max} のComponent
     */
    public static @NotNull Component component(
        int level,
        boolean maximum,
        @NotNull NamedTextColor prefixColor,
        @NotNull NamedTextColor valueColor
    ) {
        Component prefix = Component.text("Lv.", prefixColor);
        return maximum
            ? prefix.append(Component.text("max", NamedTextColor.RED, TextDecoration.BOLD))
            : prefix.append(Component.text(Math.max(1, level), valueColor));
    }

    /**
     * legacy色コードを含むクラスレベル表示を生成します。
     *
     * @param level 現在クラスレベル
     * @param maximum 最大レベルへ到達済みか
     * @return 通常時は黄色い数値、最大時は赤太字の {@code max} を持つ {@code Lv.} 表示
     */
    public static @NotNull String legacy(int level, boolean maximum) {
        return ColorCodeUtil.GRAY + "Lv." + (maximum
            ? ColorCodeUtil.RED + ColorCodeUtil.BOLD + "max"
            : ColorCodeUtil.YELLOW + Integer.toString(Math.max(1, level)));
    }
}
