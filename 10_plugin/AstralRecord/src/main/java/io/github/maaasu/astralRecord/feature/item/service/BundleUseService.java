package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemBundle;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemReference;
import io.github.maaasu.astralRecord.feature.item.model.RuneInstance;
import io.github.maaasu.astralRecord.feature.loot.model.LootContent;
import io.github.maaasu.astralRecord.feature.loot.model.LootModel;
import io.github.maaasu.astralRecord.feature.loot.model.LootPoolModel;
import io.github.maaasu.astralRecord.feature.loot.service.LootService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * bundle アイテムの右クリック開封処理を担当します。
 */
public class BundleUseService {

    private static final String SOURCE_BUNDLE_USE = "bundle_use";
    private static final long OPEN_DURATION_TICKS = 60L;
    private static final double MOVE_CANCEL_DISTANCE_SQUARED = 0.0001d;

    private final AstralRecord plugin;
    private final ItemService itemService;
    private final LootService lootService;
    private final InventoryService inventoryService;
    private final ItemStackFactory itemStackFactory;
    private final ItemDropAnimationService itemDropAnimationService;
    private final BundleUseEffectService bundleUseEffectService;
    private final ParticleDisplayService particleDisplayService;
    private final Map<UUID, PendingBundleUse> pendingUses = new ConcurrentHashMap<>();

    /**
     * bundle 使用サービスを構築します。
     *
     * @param plugin プラグインインスタンス
     * @param itemService アイテム解決サービス
     * @param lootService loot 解決サービス
     * @param inventoryService インベントリ操作サービス
     * @param itemStackFactory ドロップ生成用 ItemStackFactory
     * @param itemDropAnimationService 報酬アイテムの落下・回収演出サービス
     * @param bundleUseEffectService bundle 演出マスタ解決サービス
     */
    public BundleUseService(
        @NotNull AstralRecord plugin,
        @NotNull ItemService itemService,
        @NotNull LootService lootService,
        @NotNull InventoryService inventoryService,
        @NotNull ItemStackFactory itemStackFactory,
        @NotNull ItemDropAnimationService itemDropAnimationService,
        @NotNull BundleUseEffectService bundleUseEffectService,
        @NotNull ParticleDisplayService particleDisplayService
    ) {
        this.plugin = plugin;
        this.itemService = itemService;
        this.lootService = lootService;
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
        if (bundle == null || bundle.getLootTableId() == null || bundle.getLootTableId().isBlank()) {
            PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_5242, model.getId());
            return false;
        }

        LootModel lootModel = lootService.getLoadedOrFetch(bundle.getLootTableId());
        if (lootModel == null) {
            PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_5242, bundle.getLootTableId());
            return false;
        }

        cancelPendingOpen(astPlayer.getBukkit().getUniqueId(), false);

        Player player = astPlayer.getBukkit();
        BossBar bossBar = Bukkit.createBossBar(
            ColorCodeUtil.translateAlternateColorCodes("&6使用中 &7" + model.getName()),
            BarColor.YELLOW,
            BarStyle.SOLID
        );
        bossBar.setVisible(true);
        bossBar.setProgress(0.0d);
        bossBar.addPlayer(player);
        showUsingSubtitle(player, model.getName());

        PendingBundleUse pending = new PendingBundleUse(
            astPlayer,
            hand,
            model,
            bundle,
            lootModel,
            bossBar
        );

        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(
            plugin,
            () -> tickPendingUse(player.getUniqueId()),
            1L,
            1L
        );
        pending.setTask(task);
        pendingUses.put(player.getUniqueId(), pending);
        PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_5246, model.getName());
        return true;
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
        PendingBundleUse pending = pendingUses.remove(playerId);
        if (pending == null) {
            return false;
        }

        cleanupPendingUse(pending);
        if (notify) {
            PlayerMessageService.getInstance().send(pending.astPlayer(), PlayerMsgId.P_5247);
        }
        return true;
    }

    private void tickPendingUse(@NotNull UUID playerId) {
        PendingBundleUse pending = pendingUses.get(playerId);
        if (pending == null) {
            return;
        }

        Player player = pending.astPlayer().getBukkit();
        if (!player.isOnline()) {
            cancelPendingOpen(playerId, false);
            return;
        }
        if (hasMoved(pending, player)) {
            cancelPendingOpen(playerId, true);
            return;
        }

        long elapsedTicks = pending.incrementElapsedTicks();
        double progress = Math.min(1.0d, (double) elapsedTicks / (double) OPEN_DURATION_TICKS);
        pending.bossBar().setProgress(progress);

        if (elapsedTicks >= OPEN_DURATION_TICKS) {
            completePendingUse(playerId, pending);
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

        Map<String, Integer> rewards = rollRewards(pending.lootModel());
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
        int totalDropped = 0;
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

            rewardKinds++;
            int requestedAmount = reward.getValue();
            resolvedRewards.add(new ResolvedReward(rewardModel, requestedAmount));
            rewardSummaries.add(buildRewardSummary(rewardModel, requestedAmount));
            int granted = inventoryService.addItemToNormalInventory(
                pending.astPlayer(), rewardModel, requestedAmount, SOURCE_BUNDLE_USE);
            totalGranted += granted;

            int overflow = requestedAmount - granted;
            if (overflow > 0) {
                totalDropped += dropOverflow(pending.astPlayer(), rewardModel, overflow);
            }
        }

        playUseEffects(pending.astPlayer(), pending.bundle());
        playRewardDropAnimations(pending.astPlayer(), resolvedRewards);
        PlayerMessageService.getInstance().send(pending.astPlayer(), PlayerMsgId.P_5243, rewardKinds, totalGranted);
        for (String rewardSummary : rewardSummaries) {
            PlayerMessageService.getInstance().send(pending.astPlayer(), PlayerMsgId.P_5248, rewardSummary);
        }
        if (totalDropped > 0) {
            PlayerMessageService.getInstance().send(pending.astPlayer(), PlayerMsgId.P_5244, totalDropped);
        }
    }

    private @NotNull String buildRewardSummary(@NotNull ItemModel rewardModel, int amount) {
        String displayName = rewardModel.getName();
        if (displayName == null || displayName.isBlank()) {
            displayName = rewardModel.getId();
        } else {
            displayName = ColorCodeUtil.translateAlternateColorCodes(displayName);
        }
        return displayName + ColorCodeUtil.GRAY + " x" + amount;
    }

    private boolean isStillHoldingBundle(@NotNull PendingBundleUse pending) {
        ItemReference currentReference = inventoryService.getItemReferenceInHand(
            pending.astPlayer(),
            pending.hand()
        );
        return currentReference != null && pending.model().getId().equalsIgnoreCase(currentReference.itemId());
    }

    private void cleanupPendingUse(@NotNull PendingBundleUse pending) {
        if (pending.task() != null) {
            pending.task().cancel();
        }
        pending.astPlayer().getBukkit().resetTitle();
        pending.bossBar().removeAll();
        pending.bossBar().setVisible(false);
    }

    private boolean hasMoved(@NotNull PendingBundleUse pending, @NotNull Player player) {
        Location current = player.getLocation();
        Location start = pending.startLocation();
        if (current.getWorld() == null || start.getWorld() == null || current.getWorld() != start.getWorld()) {
            return true;
        }
        return current.distanceSquared(start) > MOVE_CANCEL_DISTANCE_SQUARED;
    }

    static @NotNull Map<String, Integer> rollRewards(@NotNull LootModel lootModel) {
        Map<String, Integer> rewards = new LinkedHashMap<>();
        ThreadLocalRandom random = ThreadLocalRandom.current();

        int rolls = Math.max(1, lootModel.getRolls());
        for (int rollIndex = 0; rollIndex < rolls; rollIndex++) {
            for (LootPoolModel pool : lootModel.getPools()) {
                int picks = Math.max(1, pool.getPick());
                List<LootContent> availableContents = new ArrayList<>(pool.getContents());
                for (int pickIndex = 0; pickIndex < picks && !availableContents.isEmpty(); pickIndex++) {
                    LootContent content = selectContent(availableContents, random);
                    if (content == null) {
                        break;
                    }
                    availableContents.remove(content);

                    int amount = rollAmount(content, random);
                    if (amount <= 0) {
                        continue;
                    }
                    rewards.merge(content.getItemId(), amount, Integer::sum);
                }
            }
        }
        return rewards;
    }

    private static @Nullable LootContent selectContent(
        @NotNull List<LootContent> contents,
        @NotNull ThreadLocalRandom random
    ) {
        List<LootContent> candidates = new ArrayList<>();
        double totalWeight = 0.0;
        for (LootContent content : contents) {
            if (content.getRate() <= 0.0) {
                continue;
            }
            candidates.add(content);
            totalWeight += content.getRate();
        }
        if (candidates.isEmpty() || totalWeight <= 0.0) {
            return null;
        }

        double target = random.nextDouble(totalWeight);
        double accum = 0.0;
        for (LootContent candidate : candidates) {
            accum += candidate.getRate();
            if (target < accum) {
                return candidate;
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    private static int rollAmount(@NotNull LootContent content, @NotNull ThreadLocalRandom random) {
        int minAmount = Math.min(content.getMinAmount(), content.getMaxAmount());
        int maxAmount = Math.max(content.getMinAmount(), content.getMaxAmount());
        if (maxAmount <= 0) {
            return 0;
        }
        return minAmount == maxAmount ? minAmount : random.nextInt(minAmount, maxAmount + 1);
    }

    private int dropOverflow(
        @NotNull AstPlayer astPlayer,
        @NotNull ItemModel rewardModel,
        int amount
    ) {
        World world = astPlayer.getBukkit().getWorld();
        Location location = astPlayer.getBukkit().getLocation();
        if (world == null) {
            return 0;
        }

        int dropped = 0;
        ItemCategory category = ItemCategory.fromApiValue(rewardModel.getCategory());
        switch (category) {
            case EQUIPMENT -> {
                for (int i = 0; i < amount; i++) {
                    EquipmentInstance instance = itemService.createEquipmentInstance(
                        rewardModel.getId(),
                        astPlayer.getAccount().getUuid().toString(),
                        SOURCE_BUNDLE_USE,
                        astPlayer.getAccount().getUuid().toString()
                    );
                    if (instance == null) {
                        continue;
                    }
                    ItemStack stack = itemStackFactory.create(rewardModel, instance, 1);
                    world.dropItemNaturally(location, itemStackFactory.asDisplayStack(stack));
                    dropped++;
                }
            }
            case RUNE -> {
                for (int i = 0; i < amount; i++) {
                    RuneInstance instance = itemService.createRuneInstance(
                        rewardModel.getId(),
                        astPlayer.getAccount().getUuid().toString(),
                        SOURCE_BUNDLE_USE,
                        astPlayer.getAccount().getUuid().toString()
                    );
                    if (instance == null) {
                        continue;
                    }
                    ItemStack stack = itemStackFactory.create(rewardModel, instance, 1);
                    world.dropItemNaturally(location, itemStackFactory.asDisplayStack(stack));
                    dropped++;
                }
            }
            default -> {
                int remaining = amount;
                int maxStack = Math.max(1, rewardModel.getMaxStack());
                while (remaining > 0) {
                    int stackAmount = Math.min(maxStack, remaining);
                    ItemStack stack = itemStackFactory.create(rewardModel, stackAmount);
                    world.dropItemNaturally(location, itemStackFactory.asDisplayStack(stack));
                    dropped += stackAmount;
                    remaining -= stackAmount;
                }
            }
        }

        return dropped;
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
        if (bundle.getOnUse() == null) {
            return;
        }

        Location location = astPlayer.getBukkit().getLocation();
        World world = astPlayer.getBukkit().getWorld();
        if (world == null) {
            return;
        }

        BundleUseEffectService.BundleUseSound soundDefinition =
            bundleUseEffectService.findSound(bundle.getOnUse().getSound());
        if (soundDefinition != null) {
            astPlayer.getBukkit().playSound(
                location,
                soundDefinition.soundKey(),
                soundDefinition.volume(),
                soundDefinition.pitch()
            );
        }

        BundleUseEffectService.BundleUseParticle particleDefinition =
            bundleUseEffectService.findParticle(bundle.getOnUse().getParticle());
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
            Component.text(ColorCodeUtil.translateAlternateColorCodes("&6使用中... &f" + itemName)),
            Title.Times.times(Duration.ZERO, Duration.ofSeconds(4), Duration.ofMillis(250))
        ));
    }

    private static final class PendingBundleUse {
        private final AstPlayer astPlayer;
        private final EquipmentSlot hand;
        private final ItemModel model;
        private final ItemBundle bundle;
        private final LootModel lootModel;
        private final BossBar bossBar;
        private final Location startLocation;
        private long elapsedTicks;
        private BukkitTask task;

        private PendingBundleUse(
            @NotNull AstPlayer astPlayer,
            @NotNull EquipmentSlot hand,
            @NotNull ItemModel model,
            @NotNull ItemBundle bundle,
            @NotNull LootModel lootModel,
            @NotNull BossBar bossBar
        ) {
            this.astPlayer = astPlayer;
            this.hand = hand;
            this.model = model;
            this.bundle = bundle;
            this.lootModel = lootModel;
            this.bossBar = bossBar;
            this.startLocation = astPlayer.getBukkit().getLocation().clone();
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

        private @NotNull LootModel lootModel() {
            return lootModel;
        }

        private @NotNull BossBar bossBar() {
            return bossBar;
        }

        private @NotNull Location startLocation() {
            return startLocation;
        }

        private long incrementElapsedTicks() {
            elapsedTicks++;
            return elapsedTicks;
        }

        private @Nullable BukkitTask task() {
            return task;
        }

        private void setTask(@NotNull BukkitTask task) {
            this.task = task;
        }
    }

    private record ResolvedReward(@NotNull ItemModel model, int amount) {
        private ResolvedReward {
            amount = Math.max(1, amount);
        }
    }
}
