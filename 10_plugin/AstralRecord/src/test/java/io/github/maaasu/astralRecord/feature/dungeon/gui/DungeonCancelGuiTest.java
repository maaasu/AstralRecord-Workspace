package io.github.maaasu.astralRecord.feature.dungeon.gui;

import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DungeonCancelGuiTest extends MockBukkitTestBase {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 5. 離脱・再参加・中止
     * 検証契約: 緊急未踏査区画転送ボタンはカルトグラフではなく中止確認GUIの右下に表示する。
     */
    @Test
    void rendersEmergencyTeleportButtonAtBottomRight() {
        var player = server().addPlayer();
        new DungeonCancelGui().open(player, UUID.randomUUID(), 30L);

        Inventory inventory = player.getOpenInventory().getTopInventory();
        assertEquals(Material.ENDER_PEARL,
                inventory.getItem(DungeonCancelGui.EMERGENCY_TELEPORT_SLOT).getType());
        assertEquals("緊急時の未踏査区画への移動", PlainTextComponentSerializer.plainText().serialize(
                inventory.getItem(DungeonCancelGui.EMERGENCY_TELEPORT_SLOT).getItemMeta().displayName()));
    }
}
