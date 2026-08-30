package io.github.maaasu.astralRecord.feature.dungeon.gui;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.LodestoneTracker;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonLayout;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonMapRoomState;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonRoomType;
import io.github.maaasu.astralRecord.feature.dungeon.service.DungeonService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutGuiHolder;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** カルトグラフへ登録された現在ダンジョンの相対地図を表示します。 */
public final class DungeonMapGui {
    public static final int SIZE = 54;
    public static final int PREVIOUS_SLOT = 45;
    public static final int DIRECTION_SLOT = 47;
    public static final int CLOSE_SLOT = 49;
    public static final int NEXT_SLOT = 51;
    private static final double COMPASS_TARGET_DISTANCE = 100.0D;
    private final DungeonMapLayoutPlanner layoutPlanner = new DungeonMapLayoutPlanner();

    /** 現在スナップショットを指定ページで表示します。 */
    public void open(
            @NotNull Player player,
            @NotNull DungeonService.MapSnapshot snapshot,
            int requestedPage
    ) {
        List<DungeonMapLayoutPlanner.Placement> placements = layoutPlanner.plan(snapshot.layout());
        List<DungeonMapLayoutPlanner.CorridorPlacement> corridors =
                layoutPlanner.planCorridors(snapshot.layout(), placements);
        int maxPage = placements.stream().mapToInt(DungeonMapLayoutPlanner.Placement::page).max().orElse(0);
        int page = Math.max(0, Math.min(requestedPage, maxPage));
        Map<Integer, Integer> visibleRoomIds = new LinkedHashMap<>();
        placements.stream()
                .filter(placement -> placement.page() == page)
                .forEach(placement -> visibleRoomIds.put(placement.slot(), placement.roomId()));
        Set<Integer> roomSlots = placements.stream()
                .filter(placement -> placement.page() == page)
                .map(DungeonMapLayoutPlanner.Placement::slot)
                .collect(java.util.stream.Collectors.toSet());
        Inventory inventory = Bukkit.createInventory(
                new Holder(snapshot.sessionId(), player.getUniqueId(), page, visibleRoomIds),
                SIZE,
                PlayerMsgResource.formatComponent(PlayerMsgId.P_7048.getId(), snapshot.displayName())
        );
        Location compassTarget = compassTarget(player.getLocation(), snapshot.playerYaw());
        Map<Integer, DungeonLayout.Connection> connectionsById = new HashMap<>();
        snapshot.layout().connections().forEach(connection ->
                connectionsById.put(connection.id(), connection));
        for (DungeonMapLayoutPlanner.CorridorPlacement corridor : corridors) {
            if (corridor.page() != page || roomSlots.contains(corridor.slot())) {
                continue;
            }
            DungeonLayout.Connection connection = connectionsById.get(corridor.connectionId());
            if (connection != null) {
                inventory.setItem(corridor.slot(), corridorItem(isConnectionOpen(snapshot, connection)));
            }
        }
        for (DungeonMapLayoutPlanner.Placement placement : placements) {
            if (placement.page() != page) {
                continue;
            }
            DungeonLayout.Room room = snapshot.layout().rooms().stream()
                    .filter(candidate -> candidate.id() == placement.roomId())
                    .findFirst()
                    .orElseThrow();
            DungeonMapRoomState state = snapshot.roomStates().getOrDefault(
                    room.id(), DungeonMapRoomState.LOCKED);
            boolean current = snapshot.currentRoomId() != null
                    && snapshot.currentRoomId() == room.id();
            inventory.setItem(placement.slot(), roomItem(
                    room,
                    sectionNumber(snapshot.layout(), room.id()),
                    state,
                    current,
                    state == DungeonMapRoomState.CLEARED,
                    compassTarget));
        }
        inventory.setItem(DIRECTION_SLOT, directionItem(snapshot.playerYaw()));
        if (page > 0) {
            inventory.setItem(PREVIOUS_SLOT, GuiItems.create(
                    Material.ARROW,
                    PlayerMsgResource.getComponent(PlayerMsgId.P_7041.getId()),
                    List.of()));
        }
        inventory.setItem(CLOSE_SLOT, GuiItems.closeButton());
        if (page < maxPage) {
            inventory.setItem(NEXT_SLOT, GuiItems.create(
                    Material.ARROW,
                    PlayerMsgResource.getComponent(PlayerMsgId.P_7042.getId()),
                    List.of()));
        }
        GuiOpenSupport.open(player, inventory);
    }

    private boolean isConnectionOpen(
            @NotNull DungeonService.MapSnapshot snapshot,
            @NotNull DungeonLayout.Connection connection
    ) {
        DungeonMapRoomState from = snapshot.roomStates().getOrDefault(
                connection.fromRoomId(), DungeonMapRoomState.LOCKED);
        DungeonMapRoomState to = snapshot.roomStates().getOrDefault(
                connection.toRoomId(), DungeonMapRoomState.LOCKED);
        return from == DungeonMapRoomState.CLEARED && to != DungeonMapRoomState.LOCKED;
    }

    private @NotNull ItemStack corridorItem(boolean open) {
        return GuiItems.create(
                open ? Material.WHITE_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE,
                PlayerMsgResource.getComponent(PlayerMsgId.P_7088.getId()),
                List.of());
    }

    private @NotNull ItemStack directionItem(float yaw) {
        return GuiItems.create(
                Material.ARROW,
                PlayerMsgResource.getComponent(LookDirection.fromYaw(yaw).messageId().getId()),
                List.of());
    }

    private @NotNull ItemStack roomItem(
            @NotNull DungeonLayout.Room room,
            int sectionNumber,
            @NotNull DungeonMapRoomState state,
            boolean current,
            boolean teleportable,
            @NotNull Location compassTarget
    ) {
        if (state == DungeonMapRoomState.LOCKED) {
            return GuiItems.create(
                    room.role() == DungeonLayout.RoomRole.START
                            ? Material.EMERALD_BLOCK
                            : Material.BLACK_STAINED_GLASS_PANE,
                    PlayerMsgResource.getComponent(PlayerMsgId.P_7049.getId()),
                    List.of(PlayerMsgResource.formatComponent(PlayerMsgId.P_7099.getId(), sectionNumber)));
        }
        Material material = room.role() == DungeonLayout.RoomRole.START
                ? Material.EMERALD_BLOCK
                : current
                ? Material.RECOVERY_COMPASS
                : switch (state) {
                    case AVAILABLE -> Material.YELLOW_STAINED_GLASS_PANE;
                    case ACTIVE -> Material.ORANGE_STAINED_GLASS_PANE;
                    case CLEARED -> Material.LIME_STAINED_GLASS_PANE;
                    case LOCKED -> Material.BLACK_STAINED_GLASS_PANE;
                };
        Component name = current
                ? PlayerMsgResource.getComponent(PlayerMsgId.P_7053.getId())
                : PlayerMsgResource.getComponent(switch (state) {
                    case AVAILABLE -> PlayerMsgId.P_7050.getId();
                    case ACTIVE -> PlayerMsgId.P_7051.getId();
                    case CLEARED -> PlayerMsgId.P_7052.getId();
                    case LOCKED -> PlayerMsgId.P_7049.getId();
                });
        List<Component> lore = new ArrayList<>();
        lore.add(PlayerMsgResource.formatComponent(PlayerMsgId.P_7099.getId(), sectionNumber));
        lore.add(PlayerMsgResource.getComponent(switch (room.role()) {
            case START -> PlayerMsgId.P_7054.getId();
            case NORMAL -> PlayerMsgId.P_7055.getId();
            case BOSS -> PlayerMsgId.P_7056.getId();
        }));
        lore.add(PlayerMsgResource.formatComponent(
                PlayerMsgId.P_7057.getId(),
                PlayerMsgResource.getMessage(roomTypeMessage(room.type()).getId())
        ));
        if (teleportable) {
            lore.add(PlayerMsgResource.getComponent(PlayerMsgId.P_7089.getId()));
        }
        ItemStack item = GuiItems.create(material, name, lore);
        if (current && material == Material.RECOVERY_COMPASS) {
            applyCompassTarget(item, compassTarget);
        }
        return item;
    }

    /**
     * 表示用コンパスへ、実在する Lodestone を要求しない目標を設定します。
     *
     * @param item 対象のコンパス ItemStack
     * @param target コンパスが指す目標地点
     * @return 目標を設定した ItemStack
     */
    static @NotNull ItemStack applyCompassTarget(
            @NotNull ItemStack item,
            @NotNull Location target
    ) {
        item.setData(
                DataComponentTypes.LODESTONE_TRACKER,
                LodestoneTracker.lodestoneTracker(target, false));
        return item;
    }

    /**
     * 現在地から本人の水平視線方向へ仮想的なコンパス目標を作成します。
     * 実在する Lodestone の設置を要求しない表示用の目標です。
     *
     * @param playerLocation 現在地
     * @param yaw 本人の現在の yaw
     * @return コンパスが指す仮想目標地点
     */
    static @NotNull Location compassTarget(@NotNull Location playerLocation, float yaw) {
        Location horizontalFacing = playerLocation.clone();
        horizontalFacing.setYaw(yaw);
        horizontalFacing.setPitch(0.0F);
        Vector direction = horizontalFacing.getDirection().normalize();
        return playerLocation.clone().add(direction.multiply(COMPASS_TARGET_DISTANCE));
    }

    private int sectionNumber(@NotNull DungeonLayout layout, int roomId) {
        for (int index = 0; index < layout.rooms().size(); index++) {
            if (layout.rooms().get(index).id() == roomId) {
                return index + 1;
            }
        }
        return 0;
    }

    private @NotNull PlayerMsgId roomTypeMessage(@NotNull DungeonRoomType type) {
        return switch (type) {
            case STANDARD -> PlayerMsgId.P_7058;
            case SUPPORT_HALL -> PlayerMsgId.P_7059;
            case COLLAPSED -> PlayerMsgId.P_7060;
            case ORE_CHAMBER -> PlayerMsgId.P_7061;
        };
    }

    public @Nullable Holder holder(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Holder holder ? holder : null;
    }

    /** 現在地図の不変識別情報です。 */
    public record Holder(
            @NotNull UUID sessionId,
            @NotNull UUID playerId,
            int pageIndex,
            @NotNull Map<Integer, Integer> visibleRoomIds
    )
            implements HotbarShortcutGuiHolder {
        public Holder(@NotNull UUID sessionId, @NotNull UUID playerId, int pageIndex) {
            this(sessionId, playerId, pageIndex, Map.of());
        }

        public Holder {
            visibleRoomIds = Map.copyOf(visibleRoomIds);
        }

        /**
         * 指定 slot の部屋 ID を返します。
         *
         * @param slot inventory GUI の raw slot
         * @return 部屋 ID。部屋でない slot、または別ページの slot なら {@code null}
         */
        public @Nullable Integer roomIdAt(int slot) {
            return visibleRoomIds.get(slot);
        }

        @Override public int getBackSlot() { return CLOSE_SLOT; }
        @Override public boolean isAlwaysCloseNavigation() { return true; }
        @Override public @NotNull Inventory getInventory() { return Bukkit.createInventory(this, SIZE); }
    }

    private enum LookDirection {
        NORTH(PlayerMsgId.P_7080),
        NORTH_EAST(PlayerMsgId.P_7081),
        EAST(PlayerMsgId.P_7082),
        SOUTH_EAST(PlayerMsgId.P_7083),
        SOUTH(PlayerMsgId.P_7084),
        SOUTH_WEST(PlayerMsgId.P_7085),
        WEST(PlayerMsgId.P_7086),
        NORTH_WEST(PlayerMsgId.P_7087);

        private final PlayerMsgId messageId;

        LookDirection(@NotNull PlayerMsgId messageId) {
            this.messageId = messageId;
        }

        private @NotNull PlayerMsgId messageId() {
            return messageId;
        }

        private static @NotNull LookDirection fromYaw(float yaw) {
            int octant = Math.floorMod((int) Math.floor((yaw + 22.5F) / 45.0F), 8);
            return switch (octant) {
                case 0 -> SOUTH;
                case 1 -> SOUTH_WEST;
                case 2 -> WEST;
                case 3 -> NORTH_WEST;
                case 4 -> NORTH;
                case 5 -> NORTH_EAST;
                case 6 -> EAST;
                default -> SOUTH_EAST;
            };
        }
    }
}
