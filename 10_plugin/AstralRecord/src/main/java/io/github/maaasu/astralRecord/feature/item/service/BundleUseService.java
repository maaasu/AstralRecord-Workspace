package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemBundle;
import io.github.maaasu.astralRecord.feature.item.model.ItemBundleReward;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemReference;
import io.github.maaasu.astralRecord.feature.loot.model.LootModel;
import io.github.maaasu.astralRecord.feature.loot.model.LootRollResult;
import io.github.maaasu.astralRecord.feature.loot.service.LootRollService;
import io.github.maaasu.astralRecord.feature.loot.service.LootService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.timing.MovementCancelableWait;
import io.github.maaasu.astralRecord.shared.timing.MovementCancelableWaitCallbacks;
import io.github.maaasu.astralRecord.shared.timing.MovementCancelableWaitCancelReason;
import io.github.maaasu.astralRecord.shared.timing.MovementCancelableWaitService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * bundle アイテムの右クリック開封処理を担当します。
 */
public class BundleUseService {

    private static final String SOURCE_BUNDLE_USE = "bundle_use";
    private static final long OPEN_DURATION_TICKS = 60L;

    private final MovementCancelableWaitService movementCancelableWaitService;
    private final ItemService itemService;
    private final LootService lootService;
    private final LootRollService lootRollService;
    private final InventoryService inventoryService;
    private final ItemStackFactory itemStackFactory;
    private final ItemDropAnimationService itemDropAnimationService;
    private final BundleUseEffectService bundleUseEffectService;
    private final ParticleDisplayService particleDisplayService;
    private final Map<UUID, PendingBundleUse> pendingUses = new ConcurrentHashMap<>();
    private BiConsumer<AstPlayer, String> bundleOpenedListener = (player, bundleId) -> { };

    /**
     * bundle 使用サービスを構築します。
     *
     * @param movementCancelableWaitService 移動キャンセル付き待機サービス
     * @param itemService アイテム解決サービス
     * @param lootService loot 解決サービス
     * @param inventoryService インベントリ操作サービス
     * @param itemStackFactory ドロップ生成用 ItemStackFactory
     * @param itemDropAnimationService 報酬アイテムの落下・回収演出サービス
     * @param bundleUseEffectService bundle 演出マスタ解決サービス
     */
    public BundleUseService(
        @NotNull MovementCancelableWaitService movementCancelableWaitService,
        @NotNull ItemService itemService,
        @NotNull LootService lootService,
        @NotNull InventoryService inventoryService,
        @NotNull ItemStackFactory itemStackFactory,
        @NotNull ItemDropAnimationService itemDropAnimationService,
        @NotNull BundleUseEffectService bundleUseEffectService,
        @NotNull ParticleDisplayService particleDisplayService
    ) {
        this.movementCancelableWaitService = movementCancelableWaitService;
        this.itemService = itemService;
        this.lootService = lootService;
        this.lootRollService = new LootRollService();
        this.inventoryService = inventoryService;
        this.itemStackFactory = itemStackFactory;
        this.itemDropAnimationService = itemDropAnimationService;
        this.bundleUseEffectService = bundleUseEffectService;
        this.particleDisplayService = particleDisplayService;
    }

    /**
     * bundle の開封を開始します。開封完了までは約 3 秒の待機時間を設けます。
     *
     * @param astPlayer 使用プレイヤー
     * @param hand 使用した手
     * @param model 使用した bundle アイテム
     * @return 開封処理の開始に成功した場合は {@code true}
     */
    public boolean beginBundleUse(
        @NotNull AstPlayer astPlayer,
        @NotNull EquipmentSlot hand,
        @NotNull ItemModel model
    ) {
        ItemBundle bundle = model.getBundle();
        if (bundle == null || (bundle.getLootTableId() == null || bundle.getLootTableId().isBlank())
            && bundle.getItems().isEmpty() && bundle.getGold() <= 0L) {
            PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_5242, model.getId());
            return false;
        }

        LootModel lootModel = bundle.getLootTableId() == null || bundle.getLootTableId().isBlank()
            ? null
            : lootService.getLoadedOrFetch(bundle.getLootTableId());
        if (bundle.getLootTableId() != null && !bundle.getLootTableId().isBlank() && lootModel == null) {
            PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_5242, bundle.getLootTableId());
            return false;
        }

        cancelPendingOpen(astPlayer.getBukkit().getUniqueId(), false);

        Player player = astPlayer.getBukkit();
        String displayName = ColorCodeUtil.toLegacyText(model.getName(), model.getId());
        BossBar bossBar = Bukkit.createBossBar(
            ColorCodeUtil.translateAlternateColorCodes("&6使用中 &7" + displayName),
            BarColor.YELLOW,
            BarStyle.SOLID
        );
        bossBar.setVisible(true);
        bossBar.setProgress(0.0d);
        bossBar.addPlayer(player);
        showUsingSubtitle(player, displayName);

        PendingBundleUse pending = new PendingBundleUse(
            astPlayer,
            hand,
            model,
            bundle,
            lootModel,
            bossBar
        );

        pendingUses.put(player.getUniqueId(), pending);
        pending.setWait(movementCancelableWaitService.begin(
            player,
            OPEN_DURATION_TICKS,
            new MovementCancelableWaitCallbacks() {
                @Override
                public void onTick(long elapsedTicks, double progress) {
                    tickPendingUse(player.getUniqueId(), pending, progress);
                }

                @Override
                public void onComplete() {
                    completePendingUse(player.getUniqueId(), pending);
                }

                @Override
                public void onCancel(@NotNull MovementCancelableWaitCancelReason reason) {
                    cancelPendingOpen(player.getUniqueId(), pending, shouldNotifyCancel(reason));
                }
            }
        ));
        PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_5246, displayName);
        return true;
    }

    /**
     * bundle開封成功時の通知先を設定します。
     *
     * @param bundleOpenedListener 開封者とbundle item IDを受け取る通知先
     */
    public void setBundleOpenedListener(@NotNull BiConsumer<AstPlayer, String> bundleOpenedListener) {
        this.bundleOpenedListener = bundleOpenedListener;
    }

    /**
     * 進行中の bundle 開封をキャンセルします。
     *
     * @param astPlayer キャンセル対象プレイヤー
     * @param notify プレイヤーへ通知する場合は {@code true}
     * @return キャンセル対象が存在した場合は {@code true}
     */
    public boolean cancelPendingOpen(@NotNull AstPlayer astPlayer, boolean notify) {
        return cancelPendingOpen(astPlayer.getBukkit().getUniqueId(), notify && astPlayer.getBukkit().isOnline());
    }

    /**
     * 進行中の bundle 開封を破棄します。切断や内部都合のクリーンアップ用途です。
     *
     * @param playerId プレイヤー UUID
     * @return キャンセル対象が存在した場合は {@code true}
     */
    public boolean cancelPendingOpen(@NotNull UUID playerId) {
        return cancelPendingOpen(playerId, false);
    }

    private boolean cancelPendingOpen(@NotNull UUID playerId, boolean notify) {
        PendingBundleUse pending = pendingUses.get(playerId);
        if (pending == null) {
            return false;
        }
        MovementCancelableWaitCancelReason reason = notify
            ? MovementCancelableWaitCancelReason.HELD_ITEM_CHANGED
            : MovementCancelableWaitCancelReason.MANUAL;
        if (pending.waitHandle() != null) {
            return pending.waitHandle().cancel(reason);
        }

        cancelPendingOpen(playerId, pending, notify);
        return true;
    }

    private void tickPendingUse(@NotNull UUID playerId, @NotNull PendingBundleUse pending, double progress) {
        if (pendingUses.get(playerId) != pending) {
            return;
        }

        Player player = pending.astPlayer().getBukkit();
        if (!player.isOnline()) {
            cancelPendingOpen(playerId, false);
            return;
        }
        pending.bossBar().setProgress(progress);
    }

    private void cancelPendingOpen(@NotNull UUID playerId, @NotNull PendingBundleUse pending, boolean notify) {
        if (!pendingUses.remove(playerId, pending)) {
            return;
        }

        cleanupPendingUse(pending);
        if (notify && pending.astPlayer().getBukkit().isOnline()) {
            PlayerMessageService.getInstance().send(pending.astPlayer(), PlayerMsgId.P_5247);
        }
    }

    private void completePendingUse(@NotNull UUID playerId, @NotNull PendingBundleUse pending) {
        if (!pendingUses.remove(playerId, pending)) {
            return;
        }

        cleanupPendingUse(pending);
        if (!isStillHoldingBundle(pending)) {
            return;
        }

        Map<String, Integer> rewards = rollRewards(pending.bundle(), pending.lootModel());
        for (Map.Entry<String, Integer> reward : rewards.entrySet()) {
            if (reward.getValue() <= 0) continue;
            ItemModel rewardModel = itemService.findLoadedById(reward.getKey());
            if (rewardModel == null) rewardModel = itemService.loadItem(reward.getKey());
            if (rewardModel == null || !inventoryService.canAddItemToNormalInventory(
                pending.astPlayer(), rewardModel, reward.getValue()
            )) {
                PlayerMessageService.getInstance().send(pending.astPlayer(), PlayerMsgId.P_5245);
                return;
            }
        }
        if (!inventoryService.consumeHotbarItemInHand(
            pending.astPlayer(),
            pending.hand(),
            pending.model().getId(),
            1
        )) {
            PlayerMessageService.getInstance().send(pending.astPlayer(), PlayerMsgId.P_5245);
            return;
        }

        int rewardKinds = 0;
        int totalGranted = 0;
        List<String> rewardSummaries = new ArrayList<>();
        List<ResolvedReward> resolvedRewards = new ArrayList<>();
        for (Map.Entry<String, Integer> reward : rewards.entrySet()) {
            if (reward.getValue() <= 0) {
                continue;
            }

            ItemModel rewardModel = itemService.findLoadedById(reward.getKey());
            if (rewardModel == null) {
                rewardModel = itemService.loadItem(reward.getKey());
            }
            if (rewardModel == null) {
                continue;
            }

            int requestedAmount = reward.getValue();
            int granted = inventoryService.addItemToNormalInventory(
                pending.astPlayer(), rewardModel, requestedAmount, SOURCE_BUNDLE_USE);
            totalGranted += granted;
            if (granted > 0) {
                rewardKinds++;
                resolvedRewards.add(new ResolvedReward(rewardModel, granted));
                rewardSummaries.add(buildRewardSummary(rewardModel, granted));
            }

        }
        if (pending.bundle().getGold() > 0L
            && inventoryService.addGold(pending.astPlayer(), pending.bundle().getGold())) {
            rewardKinds++;
            totalGranted++;
        }

        playUseEffects(pending.astPlayer(), pending.bundle());
        playRewardDropAnimations(pending.astPlayer(), resolvedRewards);
        PlayerMessageService.getInstance().send(pending.astPlayer(), PlayerMsgId.P_5243, rewardKinds, totalGranted);
        for (String rewardSummary : rewardSummaries) {
            PlayerMessageService.getInstance().send(pending.astPlayer(), PlayerMsgId.P_5248, rewardSummary);
        }
        bundleOpenedListener.accept(pending.astPlayer(), pending.model().getId());
    }

    private @NotNull String buildRewardSummary(@NotNull ItemModel rewardModel, int amount) {
        return ColorCodeUtil.toLegacyText(rewardModel.getName(), rewardModel.getId())
            + ColorCodeUtil.GRAY + " x" + amount;
    }

    private boolean isStillHoldingBundle(@NotNull PendingBundleUse pending) {
        ItemReference currentReference = inventoryService.getItemReferenceInHand(
            pending.astPlayer(),
            pending.hand()
        );
        return currentReference != null && pending.model().getId().equalsIgnoreCase(currentReference.itemId());
    }

    private void cleanupPendingUse(@NotNull PendingBundleUse pending) {
        pending.astPlayer().getBukkit().resetTitle();
        pending.bossBar().removeAll();
        pending.bossBar().setVisible(false);
    }

    private @NotNull Map<String, Integer> rollRewards(@NotNull ItemBundle bundle, @Nullable LootModel lootModel) {
        Map<String, Integer> rewards = new LinkedHashMap<>();
        if (lootModel != null) {
            for (LootRollResult reward : lootRollService.roll(lootModel)) {
                rewards.merge(reward.getItemId(), reward.getAmount(), Integer::sum);
            }
        }
        for (ItemBundleReward reward : bundle.getItems()) {
            rewards.merge(reward.getItemId(), reward.getAmount(), Integer::sum);
        }
        return rewards;
    }

    private void playRewardDropAnimations(
        @NotNull AstPlayer astPlayer,
        @NotNull List<ResolvedReward> rewards
    ) {
        Player player = astPlayer.getBukkit();
        Location origin = player.getLocation();
        for (int index = 0; index < rewards.size(); index++) {
            ResolvedReward reward = rewards.get(index);
            itemDropAnimationService.playCollectingDrop(
                player,
                origin,
                reward.model(),
                reward.amount(),
                index,
                null
            );
        }
    }

    private void playUseEffects(@NotNull AstPlayer astPlayer, @NotNull ItemBundle bundle) {
        Location location = astPlayer.getBukkit().getLocation();
        World world = astPlayer.getBukkit().getWorld();
        if (world == null) {
            return;
        }

        BundleUseEffectService.BundleUseSound soundDefinition =
            bundleUseEffectService.findSound(bundle.getOnUse() == null ? null : bundle.getOnUse().getSound());
        if (soundDefinition != null) {
            astPlayer.getBukkit().playSound(
                location,
                soundDefinition.soundKey(),
                soundDefinition.volume(),
                soundDefinition.pitch()
            );
        }

        BundleUseEffectService.BundleUseParticle particleDefinition =
            bundleUseEffectService.findParticle(bundle.getOnUse() == null ? null : bundle.getOnUse().getParticle());
        if (particleDefinition != null) {
            particleDisplayService.spawnForNearbyViewers(
                location.clone().add(
                    particleDefinition.originOffsetX(),
                    particleDefinition.originOffsetY(),
                    particleDefinition.originOffsetZ()
                ),
                particleDefinition.particle(),
                particleDefinition.count(),
                particleDefinition.offsetX(),
                particleDefinition.offsetY(),
                particleDefinition.offsetZ(),
                particleDefinition.extra()
            );
        }
    }

    private void showUsingSubtitle(@NotNull Player player, @NotNull String itemName) {
        player.showTitle(Title.title(
            Component.empty(),
            ColorCodeUtil.toComponent("&6使用中... &f" + itemName, itemName),
            Title.Times.times(Duration.ZERO, Duration.ofSeconds(4), Duration.ofMillis(250))
        ));
    }

    private static boolean shouldNotifyCancel(@NotNull MovementCancelableWaitCancelReason reason) {
        return reason == MovementCancelableWaitCancelReason.MOVED
            || reason == MovementCancelableWaitCancelReason.HELD_ITEM_CHANGED;
    }

    private static final class PendingBundleUse {
        private final AstPlayer astPlayer;
        private final EquipmentSlot hand;
        private final ItemModel model;
        private final ItemBundle bundle;
        private final @Nullable LootModel lootModel;
        private final BossBar bossBar;
        private MovementCancelableWait wait;

        private PendingBundleUse(
            @NotNull AstPlayer astPlayer,
            @NotNull EquipmentSlot hand,
            @NotNull ItemModel model,
            @NotNull ItemBundle bundle,
            @Nullable LootModel lootModel,
            @NotNull BossBar bossBar
        ) {
            this.astPlayer = astPlayer;
            this.hand = hand;
            this.model = model;
            this.bundle = bundle;
            this.lootModel = lootModel;
            this.bossBar = bossBar;
        }

        private @NotNull AstPlayer astPlayer() {
            return astPlayer;
        }

        private @NotNull EquipmentSlot hand() {
            return hand;
        }

        private @NotNull ItemModel model() {
            return model;
        }

        private @NotNull ItemBundle bundle() {
            return bundle;
        }

        private @Nullable LootModel lootModel() {
            return lootModel;
        }

        private @NotNull BossBar bossBar() {
            return bossBar;
        }

        private @Nullable MovementCancelableWait waitHandle() {
            return wait;
        }

        private void setWait(@NotNull MovementCancelableWait wait) {
            this.wait = wait;
        }
    }

    private record ResolvedReward(@NotNull ItemModel model, int amount) {
        private ResolvedReward {
            amount = Math.max(1, amount);
        }
    }
}
