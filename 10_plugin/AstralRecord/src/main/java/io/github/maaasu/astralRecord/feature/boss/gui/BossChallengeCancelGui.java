package io.github.maaasu.astralRecord.feature.boss.gui;

import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutGuiHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * ボス挑戦中止操作 GUI です。
 */
public final class BossChallengeCancelGui {
    public static final int SIZE = 27;
    public static final int CANCEL_SLOT = 13;

    /**
     * ボス挑戦中止 GUI を開きます。
     *
     * @param player 表示対象プレイヤー
     * @param challengeId 対象挑戦 ID
     */
    public void open(@NotNull Player player, @NotNull UUID challengeId) {
        Inventory inventory = Bukkit.createInventory(
                new Holder(challengeId),
                SIZE,
                Component.text("ボス挑戦操作", NamedTextColor.RED)
        );
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, GuiItems.create(
                    Material.GRAY_STAINED_GLASS_PANE,
                    Component.text(" "),
                    java.util.List.of()
            ));
        }
        inventory.setItem(CANCEL_SLOT, GuiItems.create(
                Material.RED_CONCRETE,
                Component.text("ボス挑戦を中止", NamedTextColor.RED),
                java.util.List.of(Component.text("クリックで挑戦を中止します", NamedTextColor.GRAY))
        ));
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(player, inventory);
    }

    /**
     * 対象インベントリがボス挑戦中止 GUI か判定します。
     *
     * @param inventory 判定対象
     * @return 対象 GUI の場合 true
     */
    public boolean isInventory(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Holder;
    }

    /**
     * GUI から挑戦 ID を取得します。
     *
     * @param inventory 対象インベントリ
     * @return 挑戦 ID。対象外なら null
     */
    public @Nullable UUID getChallengeId(@Nullable Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof Holder holder) {
            return holder.challengeId();
        }
        return null;
    }

    private record Holder(@NotNull UUID challengeId) implements HotbarShortcutGuiHolder {
        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, SIZE);
        }
    }
}
