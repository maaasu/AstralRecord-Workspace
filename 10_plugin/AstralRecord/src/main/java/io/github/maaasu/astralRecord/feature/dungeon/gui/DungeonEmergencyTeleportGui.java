package io.github.maaasu.astralRecord.feature.dungeon.gui;

import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonLayout;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonMapRoomState;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutGuiHolder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 緊急時に未踏査区画を選ぶカルトグラフ用 GUI です。 */
public final class DungeonEmergencyTeleportGui {
    public static final int SIZE = 54;
    public static final int PREVIOUS_SLOT = 45;
    public static final int CLOSE_SLOT = 49;
    public static final int NEXT_SLOT = 53;
    private static final int PAGE_SIZE = 45;

    /**
     * 複数の未踏査区画を選択肢として表示します。
     *
     * @param player 操作プレイヤー
     * @param sessionId ダンジョンセッション ID
     * @param rooms 選択可能な部屋一覧
     */
    public void open(
            @NotNull Player player,
            @NotNull UUID sessionId,
            @NotNull List<RoomOption> rooms
    ) {
        open(player, sessionId, rooms, 0);
    }

    /**
     * 複数の未踏査区画を指定ページで選択肢として表示します。
     *
     * @param player 操作プレイヤー
     * @param sessionId ダンジョンセッション ID
     * @param rooms 選択可能な部屋一覧
     * @param requestedPage 0始まりの表示ページ
     */
    public void open(
            @NotNull Player player,
            @NotNull UUID sessionId,
            @NotNull List<RoomOption> rooms,
            int requestedPage
    ) {
        int maxPage = Math.max(0, (rooms.size() - 1) / PAGE_SIZE);
        int page = Math.max(0, Math.min(requestedPage, maxPage));
        int start = page * PAGE_SIZE;
        int end = Math.min(rooms.size(), start + PAGE_SIZE);
        Map<Integer, Integer> roomIdsBySlot = new LinkedHashMap<>();
        for (int index = start; index < end; index++) {
            roomIdsBySlot.put(index - start, rooms.get(index).roomId());
        }
        Inventory inventory = Bukkit.createInventory(
                new Holder(sessionId, player.getUniqueId(), page, roomIdsBySlot),
                SIZE,
                PlayerMsgResource.getComponent(PlayerMsgId.P_7098.getId())
        );
        for (int index = start; index < end; index++) {
            RoomOption room = rooms.get(index);
            inventory.setItem(index - start, GuiItems.create(
                    room.state() == DungeonMapRoomState.ACTIVE
                            ? Material.ORANGE_STAINED_GLASS_PANE
                            : Material.YELLOW_STAINED_GLASS_PANE,
                    PlayerMsgResource.formatComponent(PlayerMsgId.P_7099.getId(), room.sectionNumber()),
                    List.of(PlayerMsgResource.getComponent(room.state() == DungeonMapRoomState.ACTIVE
                            ? PlayerMsgId.P_7051.getId()
                            : PlayerMsgId.P_7050.getId()))));
        }
        if (page > 0) inventory.setItem(PREVIOUS_SLOT, GuiItems.create(
                Material.ARROW, PlayerMsgResource.getComponent(PlayerMsgId.P_7041.getId()), List.of()));
        inventory.setItem(CLOSE_SLOT, GuiItems.closeButton());
        if (page < maxPage) inventory.setItem(NEXT_SLOT, GuiItems.create(
                Material.ARROW, PlayerMsgResource.getComponent(PlayerMsgId.P_7042.getId()), List.of()));
        GuiOpenSupport.open(player, inventory);
    }

    /** @return 対象 inventory の holder。対象外なら {@code null} */
    public @Nullable Holder holder(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Holder holder ? holder : null;
    }

    /** 緊急転送先として表示する部屋です。 */
    public record RoomOption(int roomId, int sectionNumber, @NotNull DungeonMapRoomState state) {
    }

    /** 緊急区画選択 GUI の不変識別情報です。 */
    public record Holder(
            @NotNull UUID sessionId,
            @NotNull UUID playerId,
            int pageIndex,
            @NotNull Map<Integer, Integer> roomIdsBySlot
    ) implements HotbarShortcutGuiHolder {
        public Holder {
            roomIdsBySlot = Map.copyOf(roomIdsBySlot);
        }

        /** @return 指定 slot の部屋 ID。選択肢以外なら {@code null} */
        public @Nullable Integer roomIdAt(int slot) {
            return roomIdsBySlot.get(slot);
        }

        @Override public int getBackSlot() { return CLOSE_SLOT; }
        @Override public boolean isAlwaysCloseNavigation() { return true; }
        @Override public @NotNull Inventory getInventory() { return Bukkit.createInventory(this, SIZE); }
    }
}
