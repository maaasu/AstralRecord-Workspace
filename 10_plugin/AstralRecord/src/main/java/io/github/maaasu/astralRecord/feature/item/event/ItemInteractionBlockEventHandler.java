package io.github.maaasu.astralRecord.feature.item.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemReference;
import io.github.maaasu.astralRecord.feature.item.service.BundleUseService;
import io.github.maaasu.astralRecord.feature.item.service.EquipmentRequirementService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.item.service.PotionUseService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.event.PlayerRuntimeDiscardEvent;
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
import io.papermc.paper.event.entity.EntityEquipmentChangedEvent;
import io.papermc.paper.event.player.PlayerItemFrameChangeEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
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

    /**
     * 使用可能な手のアイテムに対し、カスタム使用またはバニラ使用抑止の候補を返します。
     *
     * @param context 仲裁対象のプレイヤー入力
     * @return 実行時に手の使用可否を再確認する候補一覧
     */
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
        if (!isPlayerMode(astPlayer) || hand == null || isHandUseBlocked(astPlayer, hand)) {
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
                () -> !isHandUseBlocked(astPlayer, hand),
                () -> runSafely(() -> {
                    if (category == ItemCategory.BUNDLE) {
                        bundleUseService.beginBundleUse(astPlayer, hand, model);
                    } else {
                        var benefits = io.github.maaasu.astralRecord.AstralRecord.getInstance().getAccountBenefitsService();
                        if (benefits.supports(model)) benefits.use(astPlayer, hand, model);
                        else potionUseService.use(astPlayer, hand, model);
                    }
                }, LogId.E_3002, "item_use_candidate:" + snapshot.player().getName())
            ));
        }

        return List.of(new PlayerInputCandidate(
            "astral-item-vanilla-guard",
            InteractionTier.FALLBACK,
            0.0D,
            context.family() == InputFamily.RIGHT_CLICK
                ? InteractionCandidateOrder.RIGHT_CLICK_ITEM_VANILLA_GUARD
                : InteractionCandidateOrder.ITEM_VANILLA_GUARD,
            model.getId() + ":" + hand.name(),
            InputClaimPolicy.CLAIM_AND_CANCEL,
            () -> !isHandUseBlocked(astPlayer, hand),
            () -> {
                if (category == ItemCategory.EQUIPMENT && model.getEquipment() != null) {
                    EquipmentRequirementService.checkAndNotify(astPlayer, model.getEquipment());
                }
            }
        ));
    }

    /**
     * 使用中に装備が切り替わった場合は、制限対象のオフハンド使用を中断します。
     *
     * @param event Paper の装備変更イベント
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEquipmentChanged(@NotNull EntityEquipmentChangedEvent event) {
        if (event.getEntity() instanceof Player player
            && player.hasActiveItem()
            && player.getActiveItemHand() == EquipmentSlot.OFF_HAND
            && inventoryService.isOffhandUseBlocked(AstPlayerCache.get(player))) {
            player.clearActiveItem();
        }
    }

    /**
     * 制限中の補助装備を防具立てへ渡す操作を拒否します。
     *
     * @param event Bukkit の防具立て装備操作イベント
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onArmorStandManipulate(@NotNull PlayerArmorStandManipulateEvent event) {
        if (isHandUseBlocked(AstPlayerCache.get(event.getPlayer()), event.getHand())) {
            event.setCancelled(true);
        }
    }

    /**
     * 制限中の補助装備を額縁に入れる操作を拒否します。
     *
     * @param event Paper の額縁変更イベント。アイテムの設置時に判定します
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemFrameChange(@NotNull PlayerItemFrameChangeEvent event) {
        Player player = event.getPlayer();
        if (event.getAction() == PlayerItemFrameChangeEvent.ItemFrameChangeAction.PLACE
            && inventoryService.isOffhandUseBlocked(AstPlayerCache.get(player))
            && player.getInventory().getItemInOffHand().isSimilar(event.getItemStack())) {
            event.setCancelled(true);
        }
    }

    /**
     * 制限対象のオフハンド使用と、AstralRecord アイテムのバニラ消費を拒否します。
     *
     * @param event Bukkit のアイテム消費イベント
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        runSafely(() -> {
            var astPlayer = AstPlayerCache.get(event.getPlayer());
            if (isHandUseBlocked(astPlayer, event.getHand())) {
                event.setCancelled(true);
                return;
            }
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
        }, LogId.E_3002, "item_consume:" + event.getPlayer().getName());
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
        }, LogId.E_3002, "item_held_change:" + event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        runSafely(() -> {
            clearPlayerRuntime(event.getPlayer());
        }, LogId.E_3002, "item_quit_cleanup:" + event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRuntimeDiscard(PlayerRuntimeDiscardEvent event) {
        clearPlayerRuntime(event.getPlayer());
    }

    private void clearPlayerRuntime(Player player) {
        bundleUseService.cancelPendingOpen(player.getUniqueId());
        potionUseService.cancelPendingUse(player.getUniqueId());
    }

    private static boolean isPlayerMode(AstPlayer astPlayer) {
        return astPlayer != null && astPlayer.getAccount().getMode() == AccountMode.PLAYER;
    }

    /** 実際に無効化中のオフハンドからの入力かを判定します。 */
    private boolean isHandUseBlocked(AstPlayer astPlayer, EquipmentSlot hand) {
        return hand == EquipmentSlot.OFF_HAND && inventoryService.isOffhandUseBlocked(astPlayer);
    }

    private static boolean matchesItemId(ItemReference reference, @NotNull String itemId) {
        return reference != null && reference.itemId().equalsIgnoreCase(itemId);
    }
}
