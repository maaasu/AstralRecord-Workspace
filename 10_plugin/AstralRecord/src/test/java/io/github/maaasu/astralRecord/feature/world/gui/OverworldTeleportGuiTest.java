package io.github.maaasu.astralRecord.feature.world.gui;

import io.github.maaasu.astralRecord.feature.world.model.OverworldTeleportGuiSetting;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldSpawnLocation;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OverworldTeleportGuiTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/17-world/17_1-モデル定義.md
     * 章・見出し: # 17_1-モデル定義 > ## 補助モデル > ### OverworldTeleportGuiSetting
     * 検証契約: 定義されたslot 10・22だけへworld itemを配置し、holderのslot別world IDを一致させる。
     */
    @Test
    void openPlacesWorldsOnlyAtConfiguredSlots() {
        var player = server().addPlayer();
        var gui = new OverworldTeleportGui();

        gui.open(player, List.of(world("small_field", 10), world("open_world", 22)));

        Inventory inventory = player.getOpenInventory().getTopInventory();
        assertEquals(Material.AIR, inventory.getItem(0).getType());
        assertEquals(Material.GRASS_BLOCK, inventory.getItem(10).getType());
        assertEquals(Material.GRASS_BLOCK, inventory.getItem(22).getType());
        OverworldTeleportGui.Holder holder = (OverworldTeleportGui.Holder) inventory.getHolder();
        assertEquals("small_field", holder.worldIdsBySlot().get(10));
        assertEquals("open_world", holder.worldIdsBySlot().get(22));
    }

    private WorldMasterData world(String id, int slot) {
        return new WorldMasterData(
            1,
            id,
            id,
            WorldType.OVERWORLD,
            id,
            "world_instances",
            false,
            false,
            0,
            false,
            false,
            false,
            true,
            WorldSpawnLocation.defaultLocation(),
            id,
            null,
            null,
            new OverworldTeleportGuiSetting(slot)
        );
    }
}
