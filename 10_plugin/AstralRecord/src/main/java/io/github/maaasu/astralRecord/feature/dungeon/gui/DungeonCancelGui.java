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
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

/** パーティーリーダー向けダンジョン中止確認 GUI です。 */
public final class DungeonCancelGui {
    public static final int SIZE = 27;
    public static final int CANCEL_SLOT = 13;
    public static final int EMERGENCY_TELEPORT_SLOT = SIZE - 1;

    /** @param player 表示対象 @param sessionId 中止対象セッション */
    public void open(@NotNull Player player, @NotNull UUID sessionId) {
        open(player, sessionId, 0L);
    }

    /**
     * ダンジョン中止確認 GUI と緊急転送ボタンを開きます。
     *
     * @param player 表示対象
     * @param sessionId 中止対象セッション
     * @param emergencyCooldownRemainingSeconds 緊急転送の残りクールダウン秒数
     */
    public void open(
            @NotNull Player player,
            @NotNull UUID sessionId,
            long emergencyCooldownRemainingSeconds
    ) {
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
        inventory.setItem(EMERGENCY_TELEPORT_SLOT, emergencyTeleportItem(emergencyCooldownRemainingSeconds));
        GuiOpenSupport.open(player, inventory);
    }

    private @NotNull ItemStack emergencyTeleportItem(long cooldownRemainingSeconds) {
        List<Component> lore = new ArrayList<>();
        lore.add(PlayerMsgResource.getComponent(PlayerMsgId.P_7096.getId()));
        lore.add(PlayerMsgResource.getComponent(PlayerMsgId.P_7097.getId()));
        lore.add(PlayerMsgResource.getComponent(
                (cooldownRemainingSeconds > 0L ? PlayerMsgId.P_7095 : PlayerMsgId.P_7098).getId()));
        return GuiItems.create(Material.ENDER_PEARL,
                PlayerMsgResource.getComponent(PlayerMsgId.P_7092.getId()), lore);
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
