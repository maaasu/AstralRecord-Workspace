package io.github.maaasu.astralRecord.feature.menu.view.screen;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class MainMenuScreenView extends BaseMenuScreenView {
    public static final int STATUS_SLOT = 20;
    public static final int INVENTORY_SELECTOR_SLOT = 21;
    public static final int EQUIPMENT_GUI_SLOT = 22;
    public static final int GUIDE_SLOT = 24;

    public void render(@NotNull Inventory inventory) {
        fill(inventory);
        inventory.setItem(STATUS_SLOT, createItem(
            Material.PLAYER_HEAD,
            Component.text("ステータス", NamedTextColor.GREEN),
            List.of(Component.text("キャラクター情報を確認する", NamedTextColor.GRAY))
        ));
        inventory.setItem(EQUIPMENT_GUI_SLOT, createItem(
            Material.NETHERITE_CHESTPLATE,
            Component.text("装備", NamedTextColor.GOLD),
            List.of(Component.text("防具、オフハンド、アクセサリを確認する", NamedTextColor.GRAY))
        ));
        inventory.setItem(GUIDE_SLOT, createItem(
            Material.BOOK,
            Component.text("ガイド", NamedTextColor.LIGHT_PURPLE),
            List.of(Component.text("ヘルプを開く", NamedTextColor.GRAY))
        ));
    }
}
