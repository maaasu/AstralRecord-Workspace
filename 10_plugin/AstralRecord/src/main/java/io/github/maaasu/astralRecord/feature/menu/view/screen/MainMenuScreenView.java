package io.github.maaasu.astralRecord.feature.menu.view.screen;

import io.github.maaasu.astralRecord.feature.menu.model.MenuIconDefinition;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
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
    public static final int PARTY_SLOT = 33;
    public static final int PLAYER_INFO_SLOT = 34;
    public static final int ADVENTURE_RECORD_SLOT = 28;
    public static final int MAIL_SLOT = 29;

    public void render(@NotNull Inventory inventory, @NotNull Player player, long goldAmount, @NotNull List<String> activeBuffNames) {
        fill(inventory);
        inventory.setItem(STATUS_SLOT, createItem(
            MenuIconDefinition.ACCOUNT_INFO.getMaterial(),
            Component.text(MenuIconDefinition.ACCOUNT_INFO.getDisplayNameJa(), MenuIconDefinition.ACCOUNT_INFO.getColor()),
            List.of(Component.text("アカウント統計とステータスを確認", NamedTextColor.GRAY))
        ));
        inventory.setItem(PLAYER_SETTING_SLOT, createItem(
            Material.COMPARATOR,
            Component.text("プレイヤー設定", NamedTextColor.AQUA),
            List.of(Component.text("表示設定を変更", NamedTextColor.GRAY))
        ));
        inventory.setItem(EQUIPMENT_GUI_SLOT, createItem(
            MenuIconDefinition.EQUIPMENT.getMaterial(),
            Component.text(MenuIconDefinition.EQUIPMENT.getDisplayNameJa(), MenuIconDefinition.EQUIPMENT.getColor()),
            createEquipmentLore(player)
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
            createBuffLore(activeBuffNames)
        ));
        inventory.setItem(ADVENTURE_RECORD_SLOT, createItem(
            Material.WRITTEN_BOOK,
            Component.text("冒険記録", NamedTextColor.GOLD),
            List.of(Component.text("魔物録・厄災録・モブ検索を開く", NamedTextColor.GRAY))
        ));
        inventory.setItem(MAIL_SLOT, createItem(
            Material.WRITABLE_BOOK,
            Component.text("メール", NamedTextColor.GOLD),
            List.of(Component.text("お知らせと報酬を確認", NamedTextColor.GRAY))
        ));
        inventory.setItem(SKILL_BIND_SLOT, createItem(
            Material.ENCHANTED_BOOK,
            Component.text("スキル設定", NamedTextColor.AQUA),
            List.of(Component.text("スキルプリセットを設定", NamedTextColor.GRAY))
        ));
        inventory.setItem(CURRENCY_SLOT, createItem(
            MenuIconDefinition.CURRENCY.getMaterial(),
            Component.text(MenuIconDefinition.CURRENCY.getDisplayNameJa(), MenuIconDefinition.CURRENCY.getColor()),
            List.of(
                Component.text("所持通貨を確認", NamedTextColor.GRAY),
                Component.text("ゴールド " + goldAmount, NamedTextColor.YELLOW)
            )
        ));
        inventory.setItem(PARTY_SLOT, createItem(
            Material.PLAYER_HEAD,
            Component.text("パーティー", NamedTextColor.AQUA),
            List.of(Component.text("作成・招待・参加状況を確認", NamedTextColor.GRAY))
        ));
        inventory.setItem(PLAYER_INFO_SLOT, createItem(
            Material.SPYGLASS,
            Component.text("プレイヤー一覧", NamedTextColor.YELLOW),
            List.of(Component.text("参加中プレイヤーの基本情報を確認", NamedTextColor.GRAY))
        ));
    }

    private @NotNull List<Component> createBuffLore(@NotNull List<String> activeBuffNames) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("現在のバフを確認", NamedTextColor.GRAY));
        if (activeBuffNames.isEmpty()) {
            lore.add(Component.text("現在獲得中: なし", NamedTextColor.DARK_GRAY));
            return lore;
        }
        lore.add(Component.text("現在獲得中:", NamedTextColor.YELLOW));
        for (String buffName : activeBuffNames) {
            lore.add(Component.text("- " + buffName, NamedTextColor.WHITE));
        }
        return lore;
    }

    private @NotNull List<Component> createEquipmentLore(@NotNull Player player) {
        PlayerInventory inventory = player.getInventory();
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("現在装備中の防具", NamedTextColor.GRAY));
        lore.add(equipmentLine("頭", inventory.getHelmet()));
        lore.add(equipmentLine("胴", inventory.getChestplate()));
        lore.add(equipmentLine("脚", inventory.getLeggings()));
        lore.add(equipmentLine("足", inventory.getBoots()));
        return lore;
    }

    private @NotNull Component equipmentLine(@NotNull String label, @Nullable ItemStack itemStack) {
        return Component.text(label + ": ", NamedTextColor.GRAY)
            .append(itemName(itemStack));
    }

    private @NotNull Component itemName(@Nullable ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            return Component.text("なし", NamedTextColor.DARK_GRAY);
        }
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null && meta.hasDisplayName() && meta.displayName() != null) {
            return meta.displayName();
        }
        return Component.text(itemStack.getType().name(), NamedTextColor.WHITE);
    }
}
