package io.github.maaasu.astralRecord.feature.world.gui;

import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldSpawnLocation;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.Inventory;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AdminWorldTeleportGuiTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/17-world/17_1-モデル定義.md
     * 章・見出し: # 17_1-モデル定義 > ## WorldMasterData
     * 検証契約: 管理者用ワールド一覧は master の日本語表示名を使い、内部 world ID を表示名へ露出しない。
     * 一時ワールドは [temp] 付きの合成表示名をそのまま表示する。
     */
    @Test
    void displaysJapaneseWorldNamesWithoutWorldIds() {
        var player = server().addPlayer();
        var gui = new AdminWorldTeleportGui();

        gui.open(player, List.of(
                world("base_world", "星灯りの拠点", WorldType.BASE),
                world("ancient_dungeon", "古代遺跡", WorldType.DUNGEON),
                world("boss_field", "", WorldType.BOSS_FIELD),
                world("temp_preview", "[temp]temp_preview", WorldType.TEMP)
        ));

        Inventory inventory = player.getOpenInventory().getTopInventory();
        assertEquals("星灯りの拠点", plainName(inventory, 0));
        assertEquals("古代遺跡", plainName(inventory, 1));
        assertEquals("ボスフィールド", plainName(inventory, 2));
        assertEquals("[temp]temp_preview", plainName(inventory, 3));
        assertFalse(plainName(inventory, 0).contains("base_world"));

        AdminWorldTeleportGui.Holder holder = (AdminWorldTeleportGui.Holder) inventory.getHolder();
        assertEquals("base_world", holder.worldIdsBySlot().get(0));
        assertEquals("ancient_dungeon", holder.worldIdsBySlot().get(1));
        assertEquals("boss_field", holder.worldIdsBySlot().get(2));
        assertEquals("temp_preview", holder.worldIdsBySlot().get(3));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/17-world/17_4-統合フロー.md
     * 章・見出し: # 17_4-統合フロー > ## 6. 管理者用ワールドテレポートアイテム > ### 処理要点
     * 検証契約: 46件以上のワールド定義は45件ずつページ分割し、次ページで残りのワールドを表示する。
     */
    @Test
    void pagesWorldDestinationsAfterFortyFiveEntries() {
        var player = server().addPlayer();
        var gui = new AdminWorldTeleportGui();
        List<WorldMasterData> worlds = IntStream.range(0, 46)
                .mapToObj(index -> world("world_" + index, "ワールド" + index, WorldType.BASE))
                .toList();

        gui.open(player, worlds);

        Inventory inventory = player.getOpenInventory().getTopInventory();
        AdminWorldTeleportGui.Holder holder = (AdminWorldTeleportGui.Holder) inventory.getHolder();
        assertEquals(0, holder.pageIndex());
        assertEquals("world_44", holder.worldIdsBySlot().get(44));
        assertNotNull(inventory.getItem(AdminWorldTeleportGui.NEXT_SLOT));

        gui.render(inventory, worlds, 1);

        assertEquals(1, holder.pageIndex());
        assertEquals("ワールド45", plainName(inventory, 0));
        assertEquals("world_45", holder.worldIdsBySlot().get(0));
        assertEquals(Material.AIR, inventory.getItem(1).getType());
    }

    private String plainName(Inventory inventory, int slot) {
        return PlainTextComponentSerializer.plainText().serialize(inventory.getItem(slot).getItemMeta().displayName());
    }

    private WorldMasterData world(String id, String displayName, WorldType type) {
        return new WorldMasterData(
                1,
                id,
                displayName,
                type,
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
                "管理者用テストワールド",
                null,
                null,
                null
        );
    }
}
