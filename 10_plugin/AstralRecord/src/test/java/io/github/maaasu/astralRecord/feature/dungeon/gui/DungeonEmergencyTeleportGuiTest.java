package io.github.maaasu.astralRecord.feature.dungeon.gui;

import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonMapRoomState;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DungeonEmergencyTeleportGuiTest extends MockBukkitTestBase {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 8. カルトグラフ > ### 8.2 現在ダンジョンマップ
     * 検証契約: 未踏査区画が複数ある場合、選択 GUI は区間番号と固定 room ID 対応を保持する。
     */
    @Test
    void exposesOnlyPinnedEmergencyRoomChoices() {
        DungeonEmergencyTeleportGui gui = new DungeonEmergencyTeleportGui();
        var player = server().addPlayer();
        UUID sessionId = UUID.randomUUID();

        gui.open(player, sessionId, List.of(
                new DungeonEmergencyTeleportGui.RoomOption(42, 2, DungeonMapRoomState.AVAILABLE),
                new DungeonEmergencyTeleportGui.RoomOption(84, 3, DungeonMapRoomState.ACTIVE)));

        var inventory = player.getOpenInventory().getTopInventory();
        assertEquals(Material.YELLOW_STAINED_GLASS_PANE, inventory.getItem(0).getType());
        assertEquals("区間 2", PlainTextComponentSerializer.plainText().serialize(
                inventory.getItem(0).getItemMeta().displayName()));
        assertEquals(42, gui.holder(inventory).roomIdAt(0));
        assertEquals(84, gui.holder(inventory).roomIdAt(1));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 8. カルトグラフ > ### 8.2 現在ダンジョンマップ
     * 検証契約: 最大64部屋の未踏査区画もページ送りで選択できる。
     */
    @Test
    void pagesEmergencyRoomChoicesBeyondFirstFortyFiveRooms() {
        DungeonEmergencyTeleportGui gui = new DungeonEmergencyTeleportGui();
        var player = server().addPlayer();
        List<DungeonEmergencyTeleportGui.RoomOption> rooms = IntStream.range(0, 64)
                .mapToObj(index -> new DungeonEmergencyTeleportGui.RoomOption(
                        index + 100, index + 1, DungeonMapRoomState.AVAILABLE))
                .toList();

        gui.open(player, UUID.randomUUID(), rooms, 1);

        var holder = gui.holder(player.getOpenInventory().getTopInventory());
        assertEquals(1, holder.pageIndex());
        assertEquals(145, holder.roomIdAt(0));
        assertEquals(163, holder.roomIdAt(18));
        assertEquals(Material.ARROW, player.getOpenInventory().getTopInventory()
                .getItem(DungeonEmergencyTeleportGui.PREVIOUS_SLOT).getType());
    }
}
