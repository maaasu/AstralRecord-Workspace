package io.github.maaasu.astralRecord.feature.menu.view.screen;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class MainMenuScreenView extends BaseMenuScreenView {
    public static final int STATUS_SLOT = 20;
    public static final int PLAYER_SETTING_SLOT = 21;
    public static final int EQUIPMENT_GUI_SLOT = 22;
    public static final int TRASH_SLOT = 23;
    public static final int GUIDE_SLOT = 24;
    public static final int BUFF_SLOT = 30;
    public static final int SKILL_BIND_SLOT = 31;
    public static final int CURRENCY_SLOT = 32;

    public void render(@NotNull Inventory inventory) {
        fill(inventory);
        inventory.setItem(STATUS_SLOT, createItem(
            Material.PLAYER_HEAD,
            Component.text("アカウント情報", NamedTextColor.GREEN),
            List.of(Component.text("キャラクター情報を確認", NamedTextColor.GRAY))
        ));
        inventory.setItem(PLAYER_SETTING_SLOT, createItem(
            Material.COMPARATOR,
            Component.text("プレイヤー設定", NamedTextColor.AQUA),
            List.of(Component.text("表示設定を変更", NamedTextColor.GRAY))
        ));
        inventory.setItem(EQUIPMENT_GUI_SLOT, createItem(
            Material.NETHERITE_CHESTPLATE,
            Component.text("装備", NamedTextColor.GOLD),
            List.of(Component.text("武器・防具・アクセサリを確認", NamedTextColor.GRAY))
        ));
        inventory.setItem(TRASH_SLOT, createItem(
            Material.LAVA_BUCKET,
            Component.text("ゴミ箱", NamedTextColor.RED),
            List.of(Component.text("アイテムを破棄する", NamedTextColor.GRAY))
        ));
        inventory.setItem(GUIDE_SLOT, createItem(
            Material.BOOK,
            Component.text("ガイド", NamedTextColor.LIGHT_PURPLE),
            List.of(Component.text("ヘルプを開く", NamedTextColor.GRAY))
        ));
        inventory.setItem(BUFF_SLOT, createItem(
            Material.POTION,
            Component.text("バフ", NamedTextColor.AQUA),
            List.of(Component.text("現在のバフを確認", NamedTextColor.GRAY))
        ));
        inventory.setItem(SKILL_BIND_SLOT, createItem(
            Material.ENCHANTED_BOOK,
            Component.text("スキル設定", NamedTextColor.AQUA),
            List.of(Component.text("スキルプリセットを編集", NamedTextColor.GRAY))
        ));
        inventory.setItem(CURRENCY_SLOT, createItem(
            Material.EMERALD,
            Component.text("通貨", NamedTextColor.GOLD),
            List.of(Component.text("所持通貨を確認", NamedTextColor.GRAY))
        ));
    }
}
