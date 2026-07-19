package io.github.maaasu.astralRecord.feature.player.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.service.AccountModeApplicationService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.GameModeChangeGuard;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.interaction.InputClaimPolicy;
import io.github.maaasu.astralRecord.shared.interaction.InputFamily;
import io.github.maaasu.astralRecord.shared.interaction.InteractionCandidateOrder;
import io.github.maaasu.astralRecord.shared.interaction.InteractionTier;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputCandidate;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputContext;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputResolver;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionSnapshot;
import org.bukkit.GameMode;
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

public class PlayerModeEventHandler extends AbstractEventHandler
    implements PlayerInputResolver<PlayerInteractionSnapshot> {
    private static final String PLUGIN_PACKAGE_PREFIX = "io.github.maaasu.astralRecord.";
    private final AccountModeApplicationService accountModeApplicationService;

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
        if (context.family() != InputFamily.DROP_ITEM
            || !isPlayerMode(context.inputSnapshot().player())) {
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
                if (astPlayer.getAccount().getMode() != requestedMode) {
                    accountModeApplicationService.changeMode(
                        astPlayer.getAccount().getUuid(),
                        requestedMode,
                        event.getPlayer().getUniqueId()
                    );
                }
                return;
            }
            if (!isPlayerMode(event.getPlayer())) {
                return;
            }
            event.setCancelled(true);
            GameModeChangeGuard.setGameMode(event.getPlayer(), GameMode.ADVENTURE);
        }, LogId.E_5072, event.getPlayer().getName());
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
}
