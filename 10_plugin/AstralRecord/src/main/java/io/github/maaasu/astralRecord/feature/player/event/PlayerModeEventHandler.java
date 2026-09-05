package io.github.maaasu.astralRecord.feature.player.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.account.service.AccountModeApplicationService;
import io.github.maaasu.astralRecord.feature.account.service.AccountDisplayNameFormatter;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.GameModeChangeGuard;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.AsyncTaskUtil;
import io.github.maaasu.astralRecord.shared.interaction.InputClaimPolicy;
import io.github.maaasu.astralRecord.shared.interaction.InputFamily;
import io.github.maaasu.astralRecord.shared.interaction.InputSource;
import io.github.maaasu.astralRecord.shared.interaction.InteractionCandidateOrder;
import io.github.maaasu.astralRecord.shared.interaction.InteractionTier;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputCandidate;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputContext;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputResolver;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionSnapshot;
import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerModeEventHandler extends AbstractEventHandler
    implements PlayerInputResolver<PlayerInteractionSnapshot> {
    private static final String PLUGIN_PACKAGE_PREFIX = "io.github.maaasu.astralRecord.";
    private final AccountModeApplicationService accountModeApplicationService;
    private final Map<UUID, PendingModeChange> pendingModeChanges = new ConcurrentHashMap<>();

    public PlayerModeEventHandler(@NotNull AccountModeApplicationService accountModeApplicationService) {
        this.accountModeApplicationService = accountModeApplicationService;
    }

    /**
     * プレイヤーモード中の通常インベントリ操作を抑止します。
     * プラグイン GUI 上のクリックは各 GUI ハンドラへ処理を委譲するため遮断しません。
     *
     * @param event インベントリクリックイベント
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        runSafely(() -> {
            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            if (!isPlayerMode(player)) {
                return;
            }
            if (isPluginGui(event.getView().getTopInventory())) {
                return;
            }
            event.setCancelled(true);
        }, LogId.E_5072, event.getWhoClicked().getName());
    }

    /**
     * プレイヤーモード中の通常インベントリドラッグを抑止します。
     * プラグイン GUI 上のドラッグは各 GUI ハンドラへ処理を委譲するため遮断しません。
     *
     * @param event インベントリドラッグイベント
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        runSafely(() -> {
            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            if (!isPlayerMode(player)) {
                return;
            }
            if (isPluginGui(event.getView().getTopInventory())) {
                return;
            }
            event.setCancelled(true);
        }, LogId.E_5072, event.getWhoClicked().getName());
    }

    /**
     * 通常プレイ中の表示用インベントリアイテムが、ドロップ操作で実体化しないように抑止します。
     *
     * @param event ドロップイベント
     */
    @Override
    public @NotNull Collection<PlayerInputCandidate> resolve(
        @NotNull PlayerInputContext<PlayerInteractionSnapshot> context
    ) {
        PlayerInteractionSnapshot snapshot = context.inputSnapshot();
        if (!isPlayerMode(snapshot.player())) {
            return List.of();
        }
        Collection<PlayerInputCandidate> playerModeGuard = resolvePlayerModeGuard(context, snapshot);
        if (!playerModeGuard.isEmpty()) {
            return playerModeGuard;
        }
        return List.of();
    }

    private @NotNull Collection<PlayerInputCandidate> resolvePlayerModeGuard(
        @NotNull PlayerInputContext<PlayerInteractionSnapshot> context,
        @NotNull PlayerInteractionSnapshot snapshot
    ) {
        if (context.family() == InputFamily.RIGHT_CLICK
            && isEntityInteractionSource(context.source())
            && snapshot.targetEntity() != null) {
            Entity target = snapshot.targetEntity();
            Double hitDistance = snapshot.hitDistance(target);
            double candidateDistance = hitDistance != null
                ? hitDistance
                : snapshot.blockingDistance();
            return List.of(new PlayerInputCandidate(
                "player-mode-entity-interaction-guard",
                InteractionTier.WORLD_INTERACTION,
                candidateDistance,
                InteractionCandidateOrder.PLAYER_MODE_ENTITY_INTERACTION_GUARD,
                snapshot.directTargetKey(),
                InputClaimPolicy.CLAIM_AND_CANCEL,
                () -> {
                }
            ));
        }

        if (context.family() == InputFamily.LEFT_CLICK
            && context.source() == InputSource.PRE_PLAYER_ATTACK_ENTITY
            && snapshot.willAttack()
            && !isPvpAttackAllowed(snapshot)) {
            return List.of(new PlayerInputCandidate(
                "player-mode-vanilla-combat-guard",
                InteractionTier.FALLBACK,
                0.0D,
                InteractionCandidateOrder.PLAYER_MODE_VANILLA_COMBAT_GUARD,
                snapshot.directTargetKey(),
                InputClaimPolicy.CLAIM_AND_CANCEL,
                () -> {
                }
            ));
        }

        if (context.family() != InputFamily.DROP_ITEM) {
            return List.of();
        }
        return List.of(new PlayerInputCandidate(
            "player-mode-drop-guard",
            InteractionTier.FALLBACK,
            0.0D,
            InteractionCandidateOrder.PLAYER_CONTROL,
            context.playerId().toString(),
            InputClaimPolicy.CLAIM_AND_CANCEL,
            () -> {
            }
        ));
    }

    private boolean isEntityInteractionSource(InputSource source) {
        return source == InputSource.PLAYER_INTERACT_ENTITY
            || source == InputSource.PLAYER_INTERACT_AT_ENTITY;
    }

    private boolean isPvpAttackAllowed(PlayerInteractionSnapshot snapshot) {
        if (!(snapshot.targetEntity() instanceof Player target)) {
            return false;
        }
        var attacker = AstPlayerCache.get(snapshot.player());
        var victim = AstPlayerCache.get(target);
        return attacker != null
            && victim != null
            && attacker.isPvpEnabled()
            && victim.isPvpEnabled();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        runSafely(() -> {
            if (GameModeChangeGuard.isManagedChange(event.getPlayer())) {
                return;
            }
            var astPlayer = AstPlayerCache.get(event.getPlayer());
            if (astPlayer == null) {
                return;
            }
            AccountMode requestedMode = switch (event.getNewGameMode()) {
                case ADVENTURE -> AccountMode.PLAYER;
                case CREATIVE -> AccountMode.ADMIN;
                default -> null;
            };
            if (requestedMode != null && astPlayer.hasAdminPermission()) {
                AccountModel currentAccount = astPlayer.getAccount();
                if (currentAccount.getMode() == requestedMode) {
                    return;
                }

                event.setCancelled(true);
                event.cancelMessage(PlayerMsgResource.getComponent(PlayerMsgId.P_5334.getId()));
                requestModeChange(event.getPlayer(), currentAccount, requestedMode, event.getNewGameMode());
                return;
            }
            if (!isPlayerMode(event.getPlayer())) {
                return;
            }
            event.setCancelled(true);
            GameModeChangeGuard.setGameMode(event.getPlayer(), GameMode.ADVENTURE);
        }, LogId.E_5072, event.getPlayer().getName());
    }

    private void requestModeChange(
        @NotNull Player player,
        @NotNull AccountModel currentAccount,
        @NotNull AccountMode requestedMode,
        @NotNull GameMode requestedGameMode
    ) {
        UUID playerId = player.getUniqueId();
        PendingModeChange pending = new PendingModeChange(
            player,
            currentAccount.getUuid(),
            requestedMode,
            requestedGameMode
        );
        if (pendingModeChanges.putIfAbsent(playerId, pending) != null) {
            return;
        }

        AstralRecord plugin = AstralRecord.getInstance();
        if (plugin == null) {
            pendingModeChanges.remove(playerId, pending);
            return;
        }
        try {
            AsyncTaskUtil.supplyAsync(plugin, () -> accountModeApplicationService.persistModeChange(
                pending.accountUuid(),
                pending.requestedMode(),
                playerId
            )).whenComplete((updated, throwable) -> AsyncTaskUtil.runSync(plugin, () ->
                completeModeChange(playerId, pending, updated, throwable)
            ));
        } catch (RuntimeException exception) {
            pendingModeChanges.remove(playerId, pending);
            throw exception;
        }
    }

    private void completeModeChange(
        @NotNull UUID playerId,
        @NotNull PendingModeChange pending,
        AccountModeApplicationService.PersistedModeChange persisted,
        Throwable throwable
    ) {
        if (!pendingModeChanges.remove(playerId, pending)) {
            return;
        }
        if (throwable != null) {
            Logger.log(LogId.E_5154, throwable, pending.accountUuid());
            if (pending.player().isOnline()) {
                PlayerMessageService.getInstance().send(pending.player(), PlayerMsgId.P_5062);
            }
            return;
        }
        if (persisted == null) {
            return;
        }
        if (!accountModeApplicationService.applyPersistedMode(persisted)) {
            return;
        }
        if (!pending.player().isOnline()) {
            return;
        }
        var current = AstPlayerCache.get(pending.player());
        if (current == null || !current.getAccount().getUuid().equals(pending.accountUuid())) {
            return;
        }
        AccountModel updated = persisted.account();
        GameModeChangeGuard.setGameMode(pending.player(), pending.requestedGameMode());
        PlayerMessageService.getInstance().send(
            pending.player(),
            PlayerMsgId.P_5332,
            AccountDisplayNameFormatter.toLegacy(updated),
            updated.getMode().getDisplayName()
        );
    }

    private boolean isPlayerMode(Player player) {
        var astPlayer = AstPlayerCache.get(player);
        return astPlayer != null && astPlayer.getAccount().getMode() == AccountMode.PLAYER;
    }

    private boolean isPluginGui(Inventory topInventory) {
        InventoryHolder inventoryHolder = topInventory.getHolder();
        return inventoryHolder != null
            && inventoryHolder.getClass().getName().startsWith(PLUGIN_PACKAGE_PREFIX);
    }

    private record PendingModeChange(
        @NotNull Player player,
        @NotNull UUID accountUuid,
        @NotNull AccountMode requestedMode,
        @NotNull GameMode requestedGameMode
    ) {
    }
}
