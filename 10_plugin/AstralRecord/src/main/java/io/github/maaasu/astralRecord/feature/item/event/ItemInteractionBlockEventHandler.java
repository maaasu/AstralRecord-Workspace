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
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.NotNull;

/**
 * AstralRecord アイテムのバニラアクションを抑止しつつ、必要な使用処理へ橋渡しするイベントハンドラ。
 */
public class ItemInteractionBlockEventHandler extends AbstractEventHandler {

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

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerInteract(PlayerInteractEvent event) {
        runSafely(() -> {
            var action = event.getAction();
            if (action == Action.PHYSICAL) {
                return;
            }

            var astPlayer = AstPlayerCache.get(event.getPlayer());
            if (!isPlayerMode(astPlayer)) {
                return;
            }

            var hand = event.getHand();
            if (hand == null) {
                return;
            }

            ItemModel model = inventoryService.getItemModelInHand(astPlayer, hand);
            if (model == null) {
                return;
            }

            if (isBundleUseAction(action)) {
                ItemCategory category = ItemCategory.fromApiValue(model.getCategory());
                if (category == ItemCategory.BUNDLE) {
                    bundleUseService.beginBundleUse(astPlayer, hand, model);
                } else if (category == ItemCategory.CONSUMABLE) {
                    potionUseService.use(astPlayer, hand, model);
                }
            }

            event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
            event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
            event.setCancelled(true);
        }, LogId.E_5200, event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        runSafely(() -> {
            var astPlayer = AstPlayerCache.get(event.getPlayer());
            if (!isPlayerMode(astPlayer)) {
                return;
            }
            if (inventoryService.getItemReferenceInHand(astPlayer, event.getHand()) == null) {
                return;
            }
            event.setCancelled(true);
        }, LogId.E_5200, event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerInteractAtEntity(PlayerInteractAtEntityEvent event) {
        runSafely(() -> {
            var astPlayer = AstPlayerCache.get(event.getPlayer());
            if (!isPlayerMode(astPlayer)) {
                return;
            }
            if (inventoryService.getItemReferenceInHand(astPlayer, event.getHand()) == null) {
                return;
            }
            event.setCancelled(true);
        }, LogId.E_5200, event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        runSafely(() -> {
            if (!(event.getDamager() instanceof Player player)) {
                return;
            }

            var astPlayer = AstPlayerCache.get(player);
            if (!isPlayerMode(astPlayer)) {
                return;
            }
            if (inventoryService.getItemReferenceInHand(astPlayer, EquipmentSlot.HAND) == null) {
                return;
            }
            event.setCancelled(true);
        }, LogId.E_5200, event.getEntity().getName());
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

    private static boolean isBundleUseAction(@NotNull Action action) {
        return action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
    }

    private static boolean isPlayerMode(AstPlayer astPlayer) {
        return astPlayer != null && astPlayer.getAccount().getMode() == AccountMode.PLAYER;
    }

    private static boolean matchesItemId(ItemReference reference, @NotNull String itemId) {
        return reference != null && reference.itemId().equalsIgnoreCase(itemId);
    }
}
