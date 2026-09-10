package io.github.maaasu.astralRecord.feature.item.castdisk;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.interaction.InputClaimPolicy;
import io.github.maaasu.astralRecord.shared.interaction.InputFamily;
import io.github.maaasu.astralRecord.shared.interaction.InteractionCandidateOrder;
import io.github.maaasu.astralRecord.shared.interaction.InteractionTier;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputCandidate;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputContext;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputResolver;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionSnapshot;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;

/** キャストディスクの入力候補、設定GUI操作、ドッジ状態の後片付けを橋渡しします。 */
public final class CastDiskInteractionEventHandler extends AbstractEventHandler implements PlayerInputResolver<PlayerInteractionSnapshot> {
    private final CastDiskUseService service;

    public CastDiskInteractionEventHandler(@NotNull CastDiskUseService service) {
        this.service = service;
    }

    @Override
    public @NotNull Collection<PlayerInputCandidate> resolve(@NotNull PlayerInputContext<PlayerInteractionSnapshot> context) {
        if ((context.family() != InputFamily.RIGHT_CLICK && context.family() != InputFamily.LEFT_CLICK)
            || !context.inputSnapshot().isMainHandInput()) return List.of();
        AstPlayer player = AstPlayerCache.get(context.inputSnapshot().player());
        if (player == null || player.getAccount().getMode() != AccountMode.PLAYER || !service.isCurrentCastDisk(player)) return List.of();
        return List.of(new PlayerInputCandidate(
            "cast-disk-use", InteractionTier.ITEM_USE, 0.0D, InteractionCandidateOrder.CAST_DISK,
            context.playerId().toString(), InputClaimPolicy.CLAIM_AND_CANCEL,
            () -> runSafely(() -> {
                if (context.family() == InputFamily.RIGHT_CLICK) service.handleRightClick(player);
                else service.handleLeftClick(player);
            }, LogId.E_3002, "cast_disk:" + context.inputSnapshot().player().getName())
        ));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)
            || !(event.getView().getTopInventory().getHolder() instanceof CastDiskInventoryHolder holder)) return;
        event.setCancelled(true);
        service.handleConfigurationClick(player, holder, event.getRawSlot());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(@NotNull InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof CastDiskInventoryHolder) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        service.clear(event.getPlayer().getUniqueId());
    }
}
