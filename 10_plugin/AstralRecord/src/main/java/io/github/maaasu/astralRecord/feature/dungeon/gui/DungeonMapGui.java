package io.github.maaasu.astralRecord.feature.dungeon.gui;

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
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** カルトグラフへ登録された現在ダンジョンの相対地図を表示します。 */
public final class DungeonMapGui {
    public static final int SIZE = 54;
    public static final int PREVIOUS_SLOT = 45;
    public static final int CLOSE_SLOT = 49;
    public static final int NEXT_SLOT = 53;
    private final DungeonMapLayoutPlanner layoutPlanner = new DungeonMapLayoutPlanner();

    /** 現在スナップショットを指定ページで表示します。 */
    public void open(
            @NotNull Player player,
            @NotNull DungeonService.MapSnapshot snapshot,
            int requestedPage
    ) {
        List<DungeonMapLayoutPlanner.Placement> placements = layoutPlanner.plan(snapshot.layout());
        int maxPage = placements.stream().mapToInt(DungeonMapLayoutPlanner.Placement::page).max().orElse(0);
        int page = Math.max(0, Math.min(requestedPage, maxPage));
        Inventory inventory = Bukkit.createInventory(
                new Holder(snapshot.sessionId(), player.getUniqueId(), page),
                SIZE,
                PlayerMsgResource.formatComponent(PlayerMsgId.P_7048.getId(), snapshot.displayName())
        );
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
            inventory.setItem(placement.slot(), roomItem(room, state, current));
        }
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

    private @NotNull ItemStack roomItem(
            @NotNull DungeonLayout.Room room,
            @NotNull DungeonMapRoomState state,
            boolean current
    ) {
        if (state == DungeonMapRoomState.LOCKED) {
            return GuiItems.create(
                    Material.BLACK_STAINED_GLASS_PANE,
                    PlayerMsgResource.getComponent(PlayerMsgId.P_7049.getId()),
                    List.of());
        }
        Material material = current
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
        lore.add(PlayerMsgResource.getComponent(switch (room.role()) {
            case START -> PlayerMsgId.P_7054.getId();
            case NORMAL -> PlayerMsgId.P_7055.getId();
            case BOSS -> PlayerMsgId.P_7056.getId();
        }));
        lore.add(PlayerMsgResource.formatComponent(
                PlayerMsgId.P_7057.getId(),
                PlayerMsgResource.getMessage(roomTypeMessage(room.type()).getId())
        ));
        return GuiItems.create(material, name, lore);
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
    public record Holder(@NotNull UUID sessionId, @NotNull UUID playerId, int pageIndex)
            implements HotbarShortcutGuiHolder {
        @Override public int getBackSlot() { return CLOSE_SLOT; }
        @Override public boolean isAlwaysCloseNavigation() { return true; }
        @Override public @NotNull Inventory getInventory() { return Bukkit.createInventory(this, SIZE); }
    }
}
