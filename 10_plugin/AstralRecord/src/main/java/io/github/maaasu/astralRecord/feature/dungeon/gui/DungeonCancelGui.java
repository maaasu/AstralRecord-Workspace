package io.github.maaasu.astralRecord.feature.dungeon.gui;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutGuiHolder;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/** パーティーリーダー向けダンジョン中止確認 GUI です。 */
public final class DungeonCancelGui {
    public static final int SIZE = 27;
    public static final int CANCEL_SLOT = 13;

    /** @param player 表示対象 @param sessionId 中止対象セッション */
    public void open(@NotNull Player player, @NotNull UUID sessionId) {
        Inventory inventory = Bukkit.createInventory(new Holder(sessionId), SIZE,
                PlayerMsgResource.getComponent(PlayerMsgId.P_7037.getId()));
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, GuiItems.create(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of()));
        }
        inventory.setItem(CANCEL_SLOT, GuiItems.create(
                Material.RED_CONCRETE,
                PlayerMsgResource.getComponent(PlayerMsgId.P_7038.getId()),
                List.of(PlayerMsgResource.getComponent(PlayerMsgId.P_7039.getId()))
        ));
        GuiOpenSupport.open(player, inventory);
    }

    /** @return 対象 GUI なら {@code true} */
    public boolean isInventory(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Holder;
    }

    /** @return holder のセッション ID。対象外なら {@code null} */
    public @Nullable UUID sessionId(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Holder holder ? holder.sessionId() : null;
    }

    private record Holder(@NotNull UUID sessionId) implements HotbarShortcutGuiHolder {
        @Override public @NotNull Inventory getInventory() { return Bukkit.createInventory(this, SIZE); }
    }
}
