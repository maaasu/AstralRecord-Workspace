package io.github.maaasu.astralRecord.feature.menu.view.screen;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class GuideScreenView extends BaseMenuScreenView {

    public void render(@NotNull Inventory inventory) {
        fill(inventory);
        inventory.setItem(BACK_SLOT, backItem());
        inventory.setItem(13, createItem(
            Material.WRITABLE_BOOK,
            Component.text("ガイド（準備中）", NamedTextColor.LIGHT_PURPLE),
            List.of(Component.text("ヘルプ機能は現在準備中です。", NamedTextColor.GRAY))
        ));
    }
}
