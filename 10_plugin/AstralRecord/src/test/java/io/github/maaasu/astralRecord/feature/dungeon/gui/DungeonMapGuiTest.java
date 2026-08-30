package io.github.maaasu.astralRecord.feature.dungeon.gui;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.LodestoneTracker;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonLayout;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonMapRoomState;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonRoomShape;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonRoomType;
import io.github.maaasu.astralRecord.feature.dungeon.service.DungeonService;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class DungeonMapGuiTest extends MockBukkitTestBase {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 8. カルトグラフ
     * 検証契約: LOCKEDは?、AVAILABLE/ACTIVE/CLEARED/currentは別素材で表示し、内部room IDを表示文言へ含めない。
     */
    @Test
    void visuallySeparatesRoomStatesWithoutExposingInternalIds() {
        DungeonLayout layout = new DungeonLayout(
                123L,
                128,
                128,
                64,
                8,
                List.of(
                        room(101, 0, 0),
                        room(202, 50, 0),
                        room(303, 0, 50),
                        room(404, 50, 50)
                ),
                List.of(),
                101,
                404
        );
        Map<Integer, DungeonMapRoomState> states = new LinkedHashMap<>();
        states.put(101, DungeonMapRoomState.LOCKED);
        states.put(202, DungeonMapRoomState.AVAILABLE);
        states.put(303, DungeonMapRoomState.ACTIVE);
        states.put(404, DungeonMapRoomState.CLEARED);
        DungeonService.MapSnapshot snapshot = new DungeonService.MapSnapshot(
                UUID.randomUUID(), "internal_master_id", "表示名", layout, states, 303);
        var player = server().addPlayer();

        new DungeonMapGui().open(player, snapshot, 0);

        Inventory inventory = player.getOpenInventory().getTopInventory();
        Map<Integer, DungeonMapLayoutPlanner.Placement> placementByRoom = new LinkedHashMap<>();
        new DungeonMapLayoutPlanner().plan(layout).forEach(placement ->
                placementByRoom.put(placement.roomId(), placement));
        assertItem(inventory, placementByRoom.get(101).slot(), Material.EMERALD_BLOCK, "?");
        assertItem(inventory, placementByRoom.get(202).slot(), Material.YELLOW_STAINED_GLASS_PANE, "未踏査の部屋");
        assertItem(inventory, placementByRoom.get(303).slot(), Material.RECOVERY_COMPASS, "現在地");
        assertItem(inventory, placementByRoom.get(404).slot(), Material.LIME_STAINED_GLASS_PANE, "攻略済みの部屋");
        assertItem(inventory, DungeonMapGui.DIRECTION_SLOT, Material.ARROW, "↓ 南");
        assertNull(inventory.getItem(53));
        assertEquals(404, new DungeonMapGui().holder(inventory)
                .roomIdAt(placementByRoom.get(404).slot()));
        for (ItemStack item : inventory.getContents()) {
            if (item == null || item.getType().isAir()) continue;
            String rendered = PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName());
            if (item.getItemMeta().lore() != null) {
                rendered += item.getItemMeta().lore().stream()
                        .map(PlainTextComponentSerializer.plainText()::serialize)
                        .reduce("", String::concat);
            }
            assertFalse(rendered.contains("101") || rendered.contains("202")
                    || rendered.contains("303") || rendered.contains("404"));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 8. カルトグラフ > ### 8.2 現在ダンジョンマップ
     * 検証契約: MapSnapshotのyawをGUI内の8方向矢印へ変換する。
     */
    @Test
    void rendersDirectionArrowFromPlayerYaw() {
        DungeonLayout layout = new DungeonLayout(
                123L,
                128,
                128,
                64,
                8,
                List.of(room(101, 0, 0)),
                List.of(),
                101,
                101
        );
        DungeonService.MapSnapshot snapshot = new DungeonService.MapSnapshot(
                UUID.randomUUID(), "dungeon", "表示名", layout,
                Map.of(101, DungeonMapRoomState.CLEARED), 101, 90.0F);
        var player = server().addPlayer();

        new DungeonMapGui().open(player, snapshot, 0);

        assertItem(player.getOpenInventory().getTopInventory(), DungeonMapGui.DIRECTION_SLOT,
                Material.ARROW, "← 西");
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 8. カルトグラフ > ### 8.2 現在ダンジョンマップ
     * 検証契約: 現在地に表示するRECOVERY_COMPASSは、本人のyawへ向く仮想lodestoneを持つ。
     *
     * <p>MockBukkit 4.110.0のInventoryMockはItemStackのclone時にPaperのdata componentを
     * 複製しないため、GUIから取得したItemStackではtargetを検証できない。GUI上の素材は
     * {@link #visuallySeparatesRoomStatesWithoutExposingInternalIds()}で検証し、ここではopenが
     * 使用する適用処理へ設定されるdata componentを、inventoryへのclone前のItemStackで検証する。
     */
    @Test
    void pointsRecoveryCompassAlongPlayerYaw() {
        var player = server().addPlayer();

        Location target = DungeonMapGui.compassTarget(player.getLocation(), 90.0F);
        ItemStack compass = DungeonMapGui.applyCompassTarget(
                new ItemStack(Material.RECOVERY_COMPASS), target);
        LodestoneTracker tracker = compass.getData(DataComponentTypes.LODESTONE_TRACKER);
        assertNotNull(tracker);
        assertFalse(tracker.tracked());
        Location appliedTarget = tracker.location();
        assertNotNull(appliedTarget);
        Vector direction = appliedTarget.toVector().subtract(player.getLocation().toVector())
                .setY(0.0D).normalize();
        assertEquals(-1.0D, direction.getX(), 0.0001D);
        assertEquals(0.0D, direction.getZ(), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 8. カルトグラフ > ### 8.2 現在ダンジョンマップ
     * 検証契約: 各部屋 icon の lore はプレイヤー向け区間番号を表示する。
     */
    @Test
    void rendersPlayerFacingSectionNumberInEachRoomLore() {
        DungeonLayout layout = new DungeonLayout(
                123L, 128, 128, 64, 8,
                List.of(room(101, 0, 0), room(202, 50, 0)),
                List.of(), 101, 202);
        var player = server().addPlayer();
        new DungeonMapGui().open(player, new DungeonService.MapSnapshot(
                UUID.randomUUID(), "dungeon", "表示名", layout,
                Map.of(101, DungeonMapRoomState.CLEARED, 202, DungeonMapRoomState.AVAILABLE), 101), 0);

        Map<Integer, DungeonMapLayoutPlanner.Placement> placementByRoom = new LinkedHashMap<>();
        new DungeonMapLayoutPlanner().plan(layout).forEach(placement ->
                placementByRoom.put(placement.roomId(), placement));
        assertEquals("区間 1", PlainTextComponentSerializer.plainText().serialize(
                player.getOpenInventory().getTopInventory().getItem(placementByRoom.get(101).slot())
                        .getItemMeta().lore().getFirst()));
        assertEquals("区間 2", PlainTextComponentSerializer.plainText().serialize(
                player.getOpenInventory().getTopInventory().getItem(placementByRoom.get(202).slot())
                        .getItemMeta().lore().getFirst()));
    }

    private void assertItem(Inventory inventory, int slot, Material material, String displayName) {
        ItemStack item = inventory.getItem(slot);
        assertEquals(material, item.getType());
        assertEquals(displayName,
                PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName()));
    }

    private DungeonLayout.Room room(int id, int x, int z) {
        return new DungeonLayout.Room(
                id,
                new DungeonLayout.Rect(x, z, x + 8, z + 8),
                DungeonRoomShape.RECTANGLE,
                DungeonRoomType.STANDARD,
                id == 101 ? DungeonLayout.RoomRole.START : DungeonLayout.RoomRole.NORMAL,
                id
        );
    }
}
