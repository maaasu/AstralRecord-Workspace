package io.github.maaasu.astralRecord.shared.gui.confirm;

import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 27 スロットの共通確認ダイアログを描画します。
 */
public final class ConfirmDialogView {
    public static final int SIZE = 27;
    public static final int MESSAGE_SLOT = 4;
    public static final int CONFIRM_SLOT = 12;
    public static final int CANCEL_SLOT = 14;

    /**
     * 確認ダイアログを描画します。
     *
     * @param inventory 描画先インベントリ
     * @param message メッセージ表示アイテム名
     * @param confirmName 確定ボタン名
     * @param cancelName キャンセルボタン名
     */
    public void render(
        @NotNull Inventory inventory,
        @NotNull Component message,
        @NotNull Component confirmName,
        @NotNull Component cancelName
    ) {
        render(
            inventory,
            message,
            List.of(Component.text("選択してください", NamedTextColor.GRAY)),
            confirmName,
            cancelName
        );
    }

    /**
     * 見出しと複数行の説明を持つ確認ダイアログを描画します。
     *
     * @param inventory 描画先インベントリ
     * @param message 見出し表示アイテム名
     * @param messageLore 見出しの説明行
     * @param confirmName 確定ボタン名
     * @param cancelName キャンセルボタン名
     */
    public void render(
        @NotNull Inventory inventory,
        @NotNull Component message,
        @NotNull List<Component> messageLore,
        @NotNull Component confirmName,
        @NotNull Component cancelName
    ) {
        ItemStack dummy = createItem(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, dummy);
        }
        inventory.setItem(MESSAGE_SLOT, createItem(
            Material.PAPER,
            message,
            messageLore
        ));
        inventory.setItem(CONFIRM_SLOT, createItem(
            Material.LIME_CONCRETE,
            confirmName,
            List.of(Component.text("この操作を確定します", NamedTextColor.GRAY))
        ));
        inventory.setItem(CANCEL_SLOT, createItem(
            Material.RED_CONCRETE,
            cancelName,
            List.of(Component.text("前の画面へ戻ります", NamedTextColor.GRAY))
        ));
    }

    private @NotNull ItemStack createItem(
        @NotNull Material material,
        @NotNull Component name,
        @NotNull List<Component> lore
    ) {
        return GuiItems.create(material, name, lore);
    }
}
