package io.github.maaasu.astralRecord.feature.world.service;

import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminWorldTeleportItemServiceTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/17-world/3-メソッド仕様/17_3-サービス.md
     * 章・見出し: # 17_3-サービス > ## 管理者用ワールドテレポートアイテム・GUI
     * 検証契約: 管理者用アイテムは専用 PDC マーカーで識別でき、プレイヤー向けの日本語名と使用方法を持つ。
     */
    @Test
    void createsMarkedCompassWithJapaneseUsageText() {
        var service = new AdminWorldTeleportItemService();

        ItemStack item = service.createItem();

        assertEquals(Material.COMPASS, item.getType());
        assertTrue(service.isTeleportItem(item));
        assertFalse(service.isTeleportItem(new ItemStack(Material.COMPASS)));
        assertNotNull(item.getItemMeta());
        assertEquals(
                "ワールドテレポート",
                PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName())
        );
    }
}
