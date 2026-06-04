package io.github.maaasu.astralRecord.shared.packetdisplay;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * パケット ItemDisplay の表示オプションです。
 *
 * @param itemStack 表示する ItemStack
 * @param scale     表示倍率
 * @param viewRange クライアント側の表示距離
 */
public record PacketItemDisplayOptions(
        @NotNull ItemStack itemStack,
        float scale,
        float viewRange
) {
    /**
     * ItemDisplay オプションを生成します。
     *
     * @param itemStack 表示する ItemStack
     * @param scale     表示倍率
     * @param viewRange クライアント側の表示距離
     */
    public PacketItemDisplayOptions {
        itemStack = itemStack.clone();
        scale = Math.max(0.0F, scale);
        viewRange = Math.max(1.0F, viewRange);
    }

    /**
     * スキルツリー向けの標準 ItemDisplay オプションを返します。
     *
     * @param itemStack 表示する ItemStack
     * @return 標準 ItemDisplay オプション
     */
    public static @NotNull PacketItemDisplayOptions skillTree(@NotNull ItemStack itemStack) {
        return new PacketItemDisplayOptions(itemStack, 0.72F, 96.0F);
    }
}
