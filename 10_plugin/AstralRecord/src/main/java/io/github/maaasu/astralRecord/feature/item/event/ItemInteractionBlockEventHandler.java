package io.github.maaasu.astralRecord.feature.item.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemReference;
import io.github.maaasu.astralRecord.feature.item.service.BundleUseService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.item.service.PotionUseService;
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
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;

/**
 * AstralRecord アイテムのバニラアクションを抑止しつつ、必要な使用処理へ橋渡しするイベントハンドラ。
 */
public class ItemInteractionBlockEventHandler extends AbstractEventHandler
    implements PlayerInputResolver<PlayerInteractionSnapshot> {

    private final InventoryService inventoryService;
    private final BundleUseService bundleUseService;
    private final PotionUseService potionUseService;

    /**
     * アイテム使用抑止と bundle 使用開始を扱います。
     *
     * @param inventoryService インベントリ正本サービス
     * @param bundleUseService bundle 使用サービス
     * @param potionUseService ポーション使用サービス
     */
    public ItemInteractionBlockEventHandler(
        @NotNull InventoryService inventoryService,
        @NotNull BundleUseService bundleUseService,
        @NotNull PotionUseService potionUseService
    ) {
        this.inventoryService = inventoryService;
        this.bundleUseService = bundleUseService;
        this.potionUseService = potionUseService;
    }

    @Override
    public @NotNull Collection<PlayerInputCandidate> resolve(
        @NotNull PlayerInputContext<PlayerInteractionSnapshot> context
    ) {
        if (context.family() != InputFamily.RIGHT_CLICK && context.family() != InputFamily.LEFT_CLICK) {
            return List.of();
        }
        PlayerInteractionSnapshot snapshot = context.inputSnapshot();
        AstPlayer astPlayer = AstPlayerCache.get(snapshot.player());
        EquipmentSlot hand = snapshot.hand();
        if (!isPlayerMode(astPlayer) || hand == null) {
            return List.of();
        }
        ItemModel model = inventoryService.getItemModelInHand(astPlayer, hand);
        if (model == null) {
            return List.of();
        }

        ItemCategory category = ItemCategory.fromApiValue(model.getCategory());
        if (context.family() == InputFamily.RIGHT_CLICK
            && (category == ItemCategory.BUNDLE || category == ItemCategory.CONSUMABLE)) {
            return List.of(new PlayerInputCandidate(
                "astral-item-use",
                InteractionTier.ITEM_USE,
                0.0D,
                0,
                model.getId() + ":" + hand.name(),
                InputClaimPolicy.CLAIM_AND_CANCEL,
                () -> runSafely(() -> {
                    if (category == ItemCategory.BUNDLE) {
                        bundleUseService.beginBundleUse(astPlayer, hand, model);
                    } else {
                        potionUseService.use(astPlayer, hand, model);
                    }
                }, LogId.E_5200, snapshot.player().getName())
            ));
        }

        return List.of(new PlayerInputCandidate(
            "astral-item-vanilla-guard",
            InteractionTier.FALLBACK,
            0.0D,
            InteractionCandidateOrder.ITEM_VANILLA_GUARD,
            model.getId() + ":" + hand.name(),
            InputClaimPolicy.CLAIM_AND_CANCEL,
            () -> {
            }
        ));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        runSafely(() -> {
            var astPlayer = AstPlayerCache.get(event.getPlayer());
            if (!isPlayerMode(astPlayer)) {
                return;
            }

            var consumedItemId = ItemStackFactory.getAstralItemId(event.getItem());
            if (consumedItemId == null || consumedItemId.isBlank()) {
                return;
            }

            var mainHandReference = inventoryService.getItemReferenceInHand(astPlayer, EquipmentSlot.HAND);
            var offHandReference = inventoryService.getItemReferenceInHand(astPlayer, EquipmentSlot.OFF_HAND);
            if (!matchesItemId(mainHandReference, consumedItemId)
                && !matchesItemId(offHandReference, consumedItemId)) {
                return;
            }
            event.setCancelled(true);
        }, LogId.E_5200, event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerItemHeld(PlayerItemHeldEvent event) {
        runSafely(() -> {
            var astPlayer = AstPlayerCache.get(event.getPlayer());
            if (astPlayer == null) {
                return;
            }
            bundleUseService.cancelPendingOpen(astPlayer, true);
            potionUseService.cancelPendingUse(astPlayer, true);
        }, LogId.E_5200, event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        runSafely(() -> {
            bundleUseService.cancelPendingOpen(event.getPlayer().getUniqueId());
            potionUseService.cancelPendingUse(event.getPlayer().getUniqueId());
        }, LogId.E_5200, event.getPlayer().getName());
    }

    private static boolean isPlayerMode(AstPlayer astPlayer) {
        return astPlayer != null && astPlayer.getAccount().getMode() == AccountMode.PLAYER;
    }

    private static boolean matchesItemId(ItemReference reference, @NotNull String itemId) {
        return reference != null && reference.itemId().equalsIgnoreCase(itemId);
    }
}
