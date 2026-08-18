package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ItemChatShareServiceTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-コマンド.md
     * 章・見出し: # 04_3-コマンド > ## 9. showitem コマンド
     * 検証契約: AstralRecord itemだけを所持品の表示順で重複なく補完候補にし、同じ表示名を指定した場合は最初の実アイテムを解決する。
     */
    @Test
    void listsAndResolvesAstralInventoryItemsByDisplayName() {
        ItemChatShareService service = new ItemChatShareService();
        ItemStack first = astralItem("star_sword", "星詠みの剣");
        ItemStack duplicate = astralItem("star_sword", "星詠みの剣");
        ItemStack second = astralItem("moon_staff", "月影の杖");
        ItemStack vanilla = new ItemStack(Material.DIAMOND);
        ItemStack[] contents = {first, duplicate, vanilla, second};

        assertEquals(List.of("星詠みの剣", "月影の杖"), service.getShareableItemNames(contents));
        assertSame(first, service.findShareableItem(contents, "星詠みの剣"));
    }

    private @NotNull ItemStack astralItem(@NotNull String itemId, @NotNull String displayName) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(displayName));
        meta.getPersistentDataContainer().set(
            new NamespacedKey("astralrecord", "item_id"),
            PersistentDataType.STRING,
            itemId
        );
        item.setItemMeta(meta);
        return item;
    }
}
