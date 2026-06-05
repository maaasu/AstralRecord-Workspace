package io.github.maaasu.astralRecord.shared.packetdisplay;

import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.jetbrains.annotations.NotNull;

/**
 * パケット TextDisplay の表示オプションです。
 *
 * @param text            表示文字列
 * @param scale           表示倍率
 * @param lineWidth       折り返し幅
 * @param viewRange       クライアント側の表示距離
 * @param backgroundColor 背景色
 * @param shadowed        影付き表示にするか
 * @param seeThrough      ブロック越しに表示するか
 */
public record PacketTextDisplayOptions(
        @NotNull Component text,
        float scale,
        int lineWidth,
        float viewRange,
        @NotNull Color backgroundColor,
        boolean shadowed,
        boolean seeThrough
) {
    /**
     * TextDisplay オプションを生成します。
     *
     * @param text            表示文字列
     * @param scale           表示倍率
     * @param lineWidth       折り返し幅
     * @param viewRange       クライアント側の表示距離
     * @param backgroundColor 背景色
     * @param shadowed        影付き表示にするか
     * @param seeThrough      ブロック越しに表示するか
     */
    public PacketTextDisplayOptions {
        scale = Math.max(0.0F, scale);
        lineWidth = Math.max(1, lineWidth);
        viewRange = Math.max(1.0F, viewRange);
    }

    /**
     * スキルツリー向けの標準 TextDisplay オプションを返します。
     *
     * @param text 表示文字列
     * @return 標準 TextDisplay オプション
     */
    public static @NotNull PacketTextDisplayOptions skillTree(@NotNull Component text) {
        return skillTree(text, 1.0F);
    }

    /**
     * スキルツリー向けの TextDisplay オプションをサイズ指定付きで返します。
     *
     * @param text  表示文字列
     * @param scale 表示倍率
     * @return 標準 TextDisplay オプション
     */
    public static @NotNull PacketTextDisplayOptions skillTree(@NotNull Component text, float scale) {
        return new PacketTextDisplayOptions(
                text,
                scale,
                160,
                96.0F,
                Color.fromARGB(0, 0, 0, 0),
                true,
                true
        );
    }
}
