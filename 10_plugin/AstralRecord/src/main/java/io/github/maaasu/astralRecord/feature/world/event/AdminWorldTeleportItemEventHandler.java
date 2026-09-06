package io.github.maaasu.astralRecord.feature.world.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.world.gui.AdminWorldTeleportGui;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.service.AdminWorldTeleportItemService;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import io.github.maaasu.astralRecord.shared.interaction.InputClaimPolicy;
import io.github.maaasu.astralRecord.shared.interaction.InputFamily;
import io.github.maaasu.astralRecord.shared.interaction.InteractionCandidateOrder;
import io.github.maaasu.astralRecord.shared.interaction.InteractionTier;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputCandidate;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputContext;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputResolver;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionGatewayEventHandler;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;

/**
 * 管理者用ワールドテレポートアイテムの右クリックと GUI 操作を処理します。
 */
public final class AdminWorldTeleportItemEventHandler extends AbstractEventHandler
        implements PlayerInputResolver<PlayerInteractionSnapshot> {
    private final AstralRecord plugin;
    private final WorldService worldService;
    private final AdminWorldTeleportItemService itemService;
    private final AdminWorldTeleportGui gui;

    public AdminWorldTeleportItemEventHandler(
            @NotNull AstralRecord plugin,
            @NotNull WorldService worldService,
            @NotNull AdminWorldTeleportItemService itemService,
            @NotNull AdminWorldTeleportGui gui
    ) {
        this.plugin = plugin;
        this.worldService = worldService;
        this.itemService = itemService;
        this.gui = gui;
    }

    /**
     * 管理者用ワールド一覧 GUI を開きます。
     *
     * @param player 表示対象プレイヤー
     * @return GUI を開く処理を開始した場合は {@code true}
     */
    public boolean open(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || !astPlayer.hasAdminPermission()) {
            if (astPlayer != null) {
                PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_5707);
            }
            GuiSound.DENY.play(player);
            return false;
        }

        List<WorldMasterData> worlds = worldService.getAll().stream().toList();
        if (worlds.isEmpty()) {
            PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_5751);
            GuiSound.DENY.play(player);
            return false;
        }

        GuiSound.OPEN.play(player);
        gui.open(player, worlds);
        return true;
    }

    /**
     * 外部のアイテムツールが管理者用コンパスの右クリックを処理する前に、入力を抑止します。
     *
     * <p>{@link PlayerInteractionGatewayEventHandler} は Bukkit の
     * {@link PlayerInteractEvent#isCancelled()} と異なり、item use 側まで DENY の場合だけ
     * 初期キャンセルとして扱います。そのため、ブロック側を DENY にしたまま item use 側を
     * DEFAULT に戻し、外部ツールにはキャンセル済みとして見せつつ、gateway には候補解決を
     * 継続させます。左クリックは対象外で、Compass のナビゲーション操作を維持します。
     *
     * @param event プレイヤーの汎用 interact event
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlayerInteract(@NotNull PlayerInteractEvent event) {
        if (!isRightClick(event.getAction()) || !itemService.isTeleportItem(event.getItem())) {
            return;
        }

        event.setCancelled(true);
        event.setUseItemInHand(Event.Result.DEFAULT);
    }

    @Override
    public @NotNull Collection<PlayerInputCandidate> resolve(
            @NotNull PlayerInputContext<PlayerInteractionSnapshot> context
    ) {
        if (context.family() != InputFamily.RIGHT_CLICK) {
            return List.of();
        }

        PlayerInteractionSnapshot snapshot = context.inputSnapshot();
        EquipmentSlot hand = snapshot.hand();
        if (hand == null) {
            return List.of();
        }

        ItemStack item = snapshot.player().getInventory().getItem(hand);
        if (!itemService.isTeleportItem(item)) {
            return List.of();
        }

        return List.of(new PlayerInputCandidate(
                "admin-world-teleport-item",
                InteractionTier.INPUT_LOCK,
                0.0D,
                InteractionCandidateOrder.ADMIN_WORLD_TELEPORT_ITEM,
                snapshot.player().getUniqueId() + ":" + hand.name(),
                InputClaimPolicy.CLAIM_AND_CANCEL,
                () -> itemService.isTeleportItem(snapshot.player().getInventory().getItem(hand)),
                () -> runSafely(
                        () -> open(snapshot.player()),
                        LogId.E_5755,
                        snapshot.player().getName(),
                        "admin_world_teleport_item"
                )
        ));
    }

    /**
     * 入力 action が右クリックか判定します。
     *
     * @param action PlayerInteractEvent の action。{@code null} を許容します
     * @return ブロックまたは空気への右クリックなら {@code true}
     */
    private boolean isRightClick(@Nullable Action action) {
        return action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        runSafely(() -> {
            Inventory topInventory = event.getView().getTopInventory();
            if (!gui.isInventory(topInventory)) {
                return;
            }
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            handleTopClick(player, topInventory, event.getRawSlot());
        }, LogId.E_5755, event.getWhoClicked().getName(), "admin_world_teleport_item_click");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(@NotNull InventoryDragEvent event) {
        runSafely(() -> {
            if (!gui.isInventory(event.getView().getTopInventory())) {
                return;
            }
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                GuiSound.DENY.play(player);
            }
        }, LogId.E_5755, event.getWhoClicked().getName(), "admin_world_teleport_item_drag");
    }

    private void handleTopClick(
            @NotNull Player player,
            @NotNull Inventory inventory,
            int rawSlot
    ) {
        if (rawSlot == AdminWorldTeleportGui.BACK_SLOT) {
            player.closeInventory();
            GuiSound.CLOSE.play(player);
            return;
        }
        if (rawSlot < 0 || rawSlot >= inventory.getSize()) {
            GuiSound.DENY.play(player);
            return;
        }

        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || !astPlayer.hasAdminPermission()) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5707);
            GuiSound.DENY.play(player);
            return;
        }

        AdminWorldTeleportGui.Holder holder = gui.holder(inventory);
        if (holder == null) {
            GuiSound.DENY.play(player);
            return;
        }

        List<WorldMasterData> worlds = worldService.getAll().stream().toList();
        if (rawSlot == AdminWorldTeleportGui.PREVIOUS_SLOT) {
            if (!gui.hasPreviousPage(holder.pageIndex())) {
                GuiSound.DENY.play(player);
                return;
            }
            GuiSound.PAGE.play(player);
            gui.render(inventory, worlds, holder.pageIndex() - 1);
            return;
        }
        if (rawSlot == AdminWorldTeleportGui.NEXT_SLOT) {
            if (!gui.hasNextPage(holder.pageIndex(), worlds.size())) {
                GuiSound.DENY.play(player);
                return;
            }
            GuiSound.PAGE.play(player);
            gui.render(inventory, worlds, holder.pageIndex() + 1);
            return;
        }
        if (rawSlot >= AdminWorldTeleportGui.CONTENT_SLOT_COUNT) {
            GuiSound.DENY.play(player);
            return;
        }

        String worldId = holder.worldIdsBySlot().get(rawSlot);
        if (worldId == null) {
            GuiSound.DENY.play(player);
            return;
        }

        WorldMasterData world = worldService.getById(worldId);
        if (world == null) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5754, worldId);
            GuiSound.DENY.play(player);
            return;
        }
        teleport(player, world);
    }

    private void teleport(@NotNull Player player, @NotNull WorldMasterData world) {
        Location spawnLocation = worldService.resolveOrLoadSpawnLocation(world);
        if (spawnLocation == null || spawnLocation.getWorld() == null) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5760, world.id());
            GuiSound.DENY.play(player);
            return;
        }

        String displayName = ColorCodeUtil.toPlainText(
                world.displayName(),
                world.worldType().getRegionDisplayName()
        );
        String bukkitWorldName = spawnLocation.getWorld().getName();
        player.closeInventory();
        GuiSound.TELEPORT.play(player);
        worldService.teleportToSpawnAsync(player, world).whenComplete((success, throwable) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (throwable != null || !Boolean.TRUE.equals(success)) {
                        PlayerMessageService.getInstance().send(
                                player,
                                PlayerMsgId.P_5766,
                                displayName,
                                bukkitWorldName
                        );
                        return;
                    }
                    PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5761, displayName);
                })
        );
    }
}
