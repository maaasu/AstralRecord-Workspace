package io.github.maaasu.astralRecord.feature.world.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.boss.service.BossChallengeService;
import io.github.maaasu.astralRecord.feature.dungeon.service.DungeonService;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.interaction.InputClaimPolicy;
import io.github.maaasu.astralRecord.shared.interaction.InputFamily;
import io.github.maaasu.astralRecord.shared.interaction.InteractionCandidateOrder;
import io.github.maaasu.astralRecord.shared.interaction.InteractionTier;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputCandidate;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputContext;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputResolver;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionSnapshot;
import io.github.maaasu.astralRecord.shared.challenge.ChallengeWaitingLeaveConfirmGui;
import io.github.maaasu.astralRecord.shared.challenge.ChallengeWaitingHubArrivalGuard;
import io.github.maaasu.astralRecord.shared.gui.confirm.ConfirmDialogView;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;

/**
 * 挑戦待機中プレイヤーのハブ初期スポーン離脱入力を共通入力 gateway へ提供します。
 */
public final class ChallengeWaitingHubEventHandler extends AbstractEventHandler
        implements PlayerInputResolver<PlayerInteractionSnapshot> {
    private static final double TRIGGER_RADIUS = 2.0D;
    private static final double TRIGGER_RADIUS_SQUARED = TRIGGER_RADIUS * TRIGGER_RADIUS;

    private final WorldService worldService;
    private final BossChallengeService bossChallengeService;
    private final DungeonService dungeonService;
    private final ChallengeWaitingHubArrivalGuard arrivalGuard;
    private final ChallengeWaitingLeaveConfirmGui confirmGui = new ChallengeWaitingLeaveConfirmGui();

    /**
     * ハブ待機離脱 resolver を構成します。
     *
     * @param worldService World サービス
     * @param bossChallengeService ボス挑戦サービス
     * @param dungeonService ダンジョンサービス
     */
    public ChallengeWaitingHubEventHandler(
            @NotNull WorldService worldService,
            @NotNull BossChallengeService bossChallengeService,
            @NotNull DungeonService dungeonService
    ) {
        this(worldService, bossChallengeService, dungeonService, new ChallengeWaitingHubArrivalGuard());
    }

    /**
     * 挑戦待機Hub離脱 resolver を共有到着抑止付きで構成します。
     *
     * @param worldService World サービス
     * @param bossChallengeService ボス挑戦サービス
     * @param dungeonService ダンジョンサービス
     * @param arrivalGuard Hub到着直後の離脱入力抑止
     */
    public ChallengeWaitingHubEventHandler(
            @NotNull WorldService worldService,
            @NotNull BossChallengeService bossChallengeService,
            @NotNull DungeonService dungeonService,
            @NotNull ChallengeWaitingHubArrivalGuard arrivalGuard
    ) {
        this.worldService = worldService;
        this.bossChallengeService = bossChallengeService;
        this.dungeonService = dungeonService;
        this.arrivalGuard = arrivalGuard;
    }

    @Override
    public @NotNull Collection<PlayerInputCandidate> resolve(
            @NotNull PlayerInputContext<PlayerInteractionSnapshot> context
    ) {
        PlayerInteractionSnapshot snapshot = context.inputSnapshot();
        if (context.family() != InputFamily.SNEAK
                || !(snapshot.event() instanceof PlayerToggleSneakEvent event)
                || !event.isSneaking()) {
            return List.of();
        }
        if (arrivalGuard.isSuppressed(snapshot.player().getUniqueId())) {
            return List.of();
        }
        Double distance = triggerDistance(snapshot.player());
        if (distance == null
                || (!bossChallengeService.isHubWaitingParticipant(snapshot.player())
                && !dungeonService.isHubWaitingParticipant(snapshot.player()))) {
            return List.of();
        }
        return List.of(new PlayerInputCandidate(
                "challenge-waiting-hub-return",
                InteractionTier.WORLD_INTERACTION,
                distance,
                InteractionCandidateOrder.WORLD_SPAWN_ACTION,
                snapshot.player().getWorld().getUID() + ":challenge-waiting",
                InputClaimPolicy.CLAIM,
                () -> runSafely(
                        () -> handleWaitingLeaveInput(snapshot.player()),
                        LogId.E_6111,
                        snapshot.player().getName(),
                        "challenge_waiting_hub_return"
                )
        ));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        ChallengeWaitingLeaveConfirmGui.Holder holder = confirmGui.holder(top);
        if (holder == null) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || !holder.playerId().equals(player.getUniqueId())
                || event.getRawSlot() >= top.getSize()) {
            return;
        }
        if (event.getRawSlot() == ConfirmDialogView.CANCEL_SLOT) {
            player.closeInventory();
            GuiSound.SELECT.play(player);
            return;
        }
        if (event.getRawSlot() != ConfirmDialogView.CONFIRM_SLOT) {
            return;
        }
        boolean left = holder.type() == ChallengeWaitingLeaveConfirmGui.ChallengeType.BOSS
                ? bossChallengeService.leaveHubWaiting(player)
                : dungeonService.leaveHubWaiting(player);
        player.closeInventory();
        if (left) {
            GuiSound.SELECT.play(player);
        } else {
            GuiSound.DENY.play(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(@NotNull InventoryDragEvent event) {
        if (confirmGui.isInventory(event.getView().getTopInventory())
                && event.getRawSlots().stream().anyMatch(slot -> slot < event.getView().getTopInventory().getSize())) {
            event.setCancelled(true);
        }
    }

    private void handleWaitingLeaveInput(@NotNull Player player) {
        ChallengeWaitingLeaveConfirmGui.ChallengeType type = challengeType(player);
        if (type == null) {
            return;
        }
        boolean requiresConfirmation = type == ChallengeWaitingLeaveConfirmGui.ChallengeType.BOSS
                ? bossChallengeService.requiresHubWaitingLeaveConfirmation(player)
                : dungeonService.requiresHubWaitingLeaveConfirmation(player);
        if (requiresConfirmation) {
            confirmGui.open(player, type);
            return;
        }
        leaveWaiting(player);
    }

    private @Nullable ChallengeWaitingLeaveConfirmGui.ChallengeType challengeType(@NotNull Player player) {
        if (bossChallengeService.isHubWaitingParticipant(player)) {
            return ChallengeWaitingLeaveConfirmGui.ChallengeType.BOSS;
        }
        if (dungeonService.isHubWaitingParticipant(player)) {
            return ChallengeWaitingLeaveConfirmGui.ChallengeType.DUNGEON;
        }
        return null;
    }

    private void leaveWaiting(@NotNull Player player) {
        if (bossChallengeService.leaveHubWaiting(player)) {
            return;
        }
        dungeonService.leaveHubWaiting(player);
    }

    private @Nullable Double triggerDistance(@NotNull Player player) {
        WorldMasterData worldData = worldService.findByBukkitWorld(player.getWorld());
        if (worldData == null || worldData.worldType() != WorldType.HUB) {
            return null;
        }
        Location center = worldService.resolveSpawnLocation(worldData);
        if (center == null || center.getWorld() == null
                || !center.getWorld().getUID().equals(player.getWorld().getUID())) {
            return null;
        }
        double deltaX = player.getLocation().getX() - center.getX();
        double deltaZ = player.getLocation().getZ() - center.getZ();
        double distanceSquared = deltaX * deltaX + deltaZ * deltaZ;
        return distanceSquared <= TRIGGER_RADIUS_SQUARED
                ? player.getLocation().distance(center)
                : null;
    }
}
