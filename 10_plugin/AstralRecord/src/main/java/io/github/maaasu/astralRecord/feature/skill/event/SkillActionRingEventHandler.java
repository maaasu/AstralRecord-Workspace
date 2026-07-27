package io.github.maaasu.astralRecord.feature.skill.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.service.SkillActionRingService;
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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;

/**
 * 右クリック入力をアクションリング表示へ差し替えるイベントハンドラです。
 */
public final class SkillActionRingEventHandler extends AbstractEventHandler
    implements PlayerInputResolver<PlayerInteractionSnapshot> {
    private final SkillActionRingService actionRingService;
    private final InventoryService inventoryService;

    /**
     * ハンドラを生成します。
     *
     * @param actionRingService アクションリング表示サービス
     */
    public SkillActionRingEventHandler(
        @NotNull SkillActionRingService actionRingService,
        @NotNull InventoryService inventoryService
    ) {
        this.actionRingService = actionRingService;
        this.inventoryService = inventoryService;
    }

    @Override
    public @NotNull Collection<PlayerInputCandidate> resolve(
        @NotNull PlayerInputContext<PlayerInteractionSnapshot> context
    ) {
        if (context.family() == InputFamily.HOTBAR_SLOT) {
            Player player = context.inputSnapshot().player();
            return actionRingService.isOpen(player)
                ? List.of(candidate(
                    "skill-action-ring-hotbar-guard",
                    InteractionCandidateOrder.OPEN_ACTION_RING,
                    player,
                    () -> {
                    }
                ))
                : List.of();
        }
        if (context.family() != InputFamily.RIGHT_CLICK && context.family() != InputFamily.LEFT_CLICK) {
            return List.of();
        }
        PlayerInteractionSnapshot snapshot = context.inputSnapshot();
        Player player = snapshot.player();
        if (snapshot.hand() == EquipmentSlot.OFF_HAND) {
            return actionRingService.isOpen(player)
                ? List.of(candidate(
                    "skill-action-ring-offhand-guard",
                    InteractionCandidateOrder.OPEN_ACTION_RING,
                    player,
                    () -> {
                    }
                ))
                : List.of();
        }
        if (!snapshot.isMainHandInput()) {
            return List.of();
        }
        if (player.getInventory().getHeldItemSlot() == 8) {
            return List.of(new PlayerInputCandidate(
                "skill-action-ring-reserved-slot",
                InteractionTier.EXCLUSIVE_CONTEXT,
                0.0D,
                0,
                player.getUniqueId().toString(),
                InputClaimPolicy.CLAIM_AND_CANCEL,
                () -> actionRingService.close(player)
            ));
        }

        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (actionRingService.isOpen(player)) {
            Runnable executor = context.family() == InputFamily.RIGHT_CLICK
                ? () -> {
                    if (isPlayerMode(astPlayer)) {
                        actionRingService.toggle(astPlayer);
                    } else {
                        actionRingService.close(player);
                    }
                }
                : () -> {
                    actionRingService.suppressAttack(player);
                    if (isPlayerMode(astPlayer)) {
                        actionRingService.activateSelected(astPlayer);
                    } else {
                        actionRingService.close(player);
                    }
                };
            return List.of(candidate(
                "skill-action-ring-open-control",
                InteractionCandidateOrder.OPEN_ACTION_RING,
                player,
                executor
            ));
        }

        if (context.family() == InputFamily.RIGHT_CLICK
            && isPlayerMode(astPlayer)
            && isWeapon(astPlayer)) {
            return List.of(candidate(
                "skill-action-ring-open",
                InteractionCandidateOrder.NEW_ACTION_RING,
                player,
                () -> actionRingService.toggle(astPlayer)
            ));
        }
        return List.of();
    }

    private PlayerInputCandidate candidate(
        String id,
        int stableOrder,
        Player player,
        Runnable executor
    ) {
        return new PlayerInputCandidate(
            id,
            InteractionTier.FALLBACK,
            0.0D,
            stableOrder,
            player.getUniqueId().toString(),
            InputClaimPolicy.CLAIM_AND_CANCEL,
            executor
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        runSafely(
            () -> actionRingService.close(event.getPlayer()),
            LogId.E_3002,
            "skill_action_ring_quit:" + event.getPlayer().getName()
        );
    }

    private boolean isPlayerMode(AstPlayer astPlayer) {
        return astPlayer != null && astPlayer.getAccount().getMode() == AccountMode.PLAYER;
    }

    private boolean isWeapon(@NotNull AstPlayer astPlayer) {
        ItemModel item = inventoryService.getItemModelInHand(astPlayer, EquipmentSlot.HAND);
        return item != null
            && ItemCategory.EQUIPMENT.getApiValue().equalsIgnoreCase(item.getCategory())
            && item.getEquipment() != null
            && item.getEquipment().getSlot() == ItemEquipmentSlot.WEAPON;
    }
}
