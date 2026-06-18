package io.github.maaasu.astralRecord.shared.gui.sound;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GUI の開き直しで発生する内部的な close 音抑制を一元管理します。
 */
public final class GuiCloseSoundPolicy {
    private static final ConcurrentHashMap<UUID, SuppressedCloseInventory> SUPPRESSED_CLOSE_SOUNDS =
        new ConcurrentHashMap<>();

    private GuiCloseSoundPolicy() {
    }

    /**
     * 現在開いている GUI が閉じられるときの close 音を 1 回だけ抑制します。
     *
     * @param player 対象プレイヤー
     */
    public static void suppressNextCloseSound(@NotNull Player player) {
        suppressNextCloseSound(player, player.getOpenInventory().getTopInventory());
    }

    /**
     * 指定 GUI が閉じられるときの close 音を 1 回だけ抑制します。
     *
     * @param player 対象プレイヤー
     * @param closingInventory 抑制対象の GUI inventory
     */
    public static void suppressNextCloseSound(@NotNull Player player, @NotNull Inventory closingInventory) {
        SUPPRESSED_CLOSE_SOUNDS.put(
            player.getUniqueId(),
            new SuppressedCloseInventory(System.identityHashCode(closingInventory), closingInventory)
        );
    }

    /**
     * 指定 GUI の close 音を再生すべきかを返します。
     *
     * @param player 対象プレイヤー
     * @param closingInventory 閉じられた GUI inventory
     * @return close 音を再生する場合は true
     */
    public static boolean shouldPlayCloseSound(@NotNull Player player, @NotNull Inventory closingInventory) {
        return !consumeSuppressedCloseSound(player, closingInventory);
    }

    /**
     * 指定 GUI に対する close 音抑制を消費します。
     *
     * @param player 対象プレイヤー
     * @param closingInventory 閉じられた GUI inventory
     * @return 抑制対象と一致して消費した場合は true
     */
    public static boolean consumeSuppressedCloseSound(@NotNull Player player, @NotNull Inventory closingInventory) {
        SuppressedCloseInventory suppressed = SUPPRESSED_CLOSE_SOUNDS.remove(player.getUniqueId());
        return suppressed != null && suppressed.matches(closingInventory);
    }

    private record SuppressedCloseInventory(int identityHash, @NotNull Inventory inventory) {
        private boolean matches(@NotNull Inventory candidate) {
            return identityHash == System.identityHashCode(candidate) && inventory == candidate;
        }
    }
}
