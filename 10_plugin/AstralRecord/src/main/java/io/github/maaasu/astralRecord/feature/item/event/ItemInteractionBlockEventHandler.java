package io.github.maaasu.astralRecord.feature.item.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.BundleUseService;
import io.github.maaasu.astralRecord.feature.item.service.ItemReferenceResolver;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.PotionUseService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * AstralRecord アイテムのバニラアクション（左/右クリック等）を抑止するイベントハンドラ。
 */
public class ItemInteractionBlockEventHandler extends AbstractEventHandler {

    private final ItemReferenceResolver itemReferenceResolver;
    private final BundleUseService bundleUseService;
    private final PotionUseService potionUseService;

    /**
     * アイテム操作抑止・bundle 開封イベントを構築します。
     *
     * @param itemService      アイテム定義サービス
     * @param bundleUseService bundle 使用サービス
     * @param potionUseService ポーション使用サービス
     */
    public ItemInteractionBlockEventHandler(
        @NotNull ItemService itemService,
        @NotNull BundleUseService bundleUseService,
        @NotNull PotionUseService potionUseService
    ) {
        this.itemReferenceResolver = new ItemReferenceResolver(itemService);
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
            if (!isPlayerMode(event.getPlayer())) {
                return;
            }

            if (!isAstralItem(event.getItem())) {
                return;
            }

            if (isBundleUseAction(action) && event.getHand() != null) {
                var astPlayer = AstPlayerCache.get(event.getPlayer());
                if (astPlayer != null) {
                    ItemModel model = resolveItemModel(event.getItem());
                    if (model != null && ItemCategory.fromApiValue(model.getCategory()) == ItemCategory.BUNDLE) {
                        bundleUseService.beginBundleUse(astPlayer, event.getHand(), model);
                    } else if (model != null && ItemCategory.fromApiValue(model.getCategory()) == ItemCategory.CONSUMABLE) {
                        potionUseService.use(astPlayer, event.getHand(), model);
                    }
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
            if (!isAstralItem(getItemInHand(event.getPlayer(), event.getHand()))) {
                return;
            }
            if (!isPlayerMode(event.getPlayer())) {
                return;
            }
            event.setCancelled(true);
        }, LogId.E_5200, event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerInteractAtEntity(PlayerInteractAtEntityEvent event) {
        runSafely(() -> {
            if (!isAstralItem(getItemInHand(event.getPlayer(), event.getHand()))) {
                return;
            }
            if (!isPlayerMode(event.getPlayer())) {
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
            if (!isAstralItem(player.getInventory().getItemInMainHand())) {
                return;
            }
            if (!isPlayerMode(player)) {
                return;
            }
            event.setCancelled(true);
        }, LogId.E_5200, event.getEntity().getName());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        runSafely(() -> {
            if (!isAstralItem(event.getItem())) {
                return;
            }
            if (!isPlayerMode(event.getPlayer())) {
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
        }, LogId.E_5200, event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        runSafely(() -> {
            if (event.getTo() == null) {
                return;
            }
            if (event.getFrom().getX() == event.getTo().getX()
                && event.getFrom().getY() == event.getTo().getY()
                && event.getFrom().getZ() == event.getTo().getZ()) {
                return;
            }

            var astPlayer = AstPlayerCache.get(event.getPlayer());
            if (astPlayer == null) {
                return;
            }
            bundleUseService.cancelPendingOpen(astPlayer, true);
        }, LogId.E_5200, event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        runSafely(() -> bundleUseService.cancelPendingOpen(event.getPlayer().getUniqueId()),
            LogId.E_5200, event.getPlayer().getName());
    }

    private boolean isAstralItem(@Nullable ItemStack item) {
        return itemReferenceResolver.isAstralItem(item);
    }

    private @Nullable ItemModel resolveItemModel(@NotNull ItemStack itemStack) {
        return itemReferenceResolver.resolveItemModel(itemStack);
    }

    private static boolean isBundleUseAction(@NotNull Action action) {
        return action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
    }

    private static @NotNull ItemStack getItemInHand(@NotNull Player player, @NotNull EquipmentSlot hand) {
        return hand == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
    }

    private static boolean isPlayerMode(@NotNull Player player) {
        var astPlayer = AstPlayerCache.get(player);
        return astPlayer != null && astPlayer.getAccount().getMode() == AccountMode.PLAYER;
    }
}


