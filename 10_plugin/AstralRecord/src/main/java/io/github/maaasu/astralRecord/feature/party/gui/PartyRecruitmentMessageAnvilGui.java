package io.github.maaasu.astralRecord.feature.party.gui;

import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * 金床の名前変更欄でパーティーの募集内容を入力するGUIです。
 */
public final class PartyRecruitmentMessageAnvilGui {
    public static final int INPUT_SLOT = 0;
    public static final int CONFIRM_SLOT = INPUT_SLOT;

    /**
     * 募集内容入力用の金床GUIを開きます。
     * 入力紙自体を確定ボタンとし、経験値処理を伴う金床の結果スロットは使用しません。
     *
     * @param player 入力するパーティーリーダー
     * @param currentMessage 現在の募集内容
     */
    public void open(@NotNull Player player, @NotNull String currentMessage) {
        Inventory inventory = Bukkit.createInventory(
            new Holder(player.getUniqueId()),
            InventoryType.ANVIL,
            Component.text("募集内容を入力", NamedTextColor.AQUA)
        );
        inventory.setItem(INPUT_SLOT, GuiItems.create(
            Material.PAPER,
            Component.text(currentMessage, NamedTextColor.WHITE),
            List.of(Component.text("募集内容を入力してこの紙をクリック", NamedTextColor.GRAY))
        ));
        GuiOpenSupport.open(player, inventory);
    }

    /**
     * 対象インベントリが募集内容入力GUIか判定します。
     *
     * @param inventory 判定対象
     * @return 募集内容入力GUIなら {@code true}
     */
    public boolean isInventory(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Holder;
    }

    private record Holder(@NotNull UUID viewerId) implements InventoryHolder {
        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, InventoryType.ANVIL);
        }
    }
}
