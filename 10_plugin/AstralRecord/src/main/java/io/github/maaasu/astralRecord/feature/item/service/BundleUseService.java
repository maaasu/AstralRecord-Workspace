package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemBundle;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.RuneInstance;
import io.github.maaasu.astralRecord.feature.loot.model.LootContent;
import io.github.maaasu.astralRecord.feature.loot.model.LootModel;
import io.github.maaasu.astralRecord.feature.loot.model.LootPoolModel;
import io.github.maaasu.astralRecord.feature.loot.service.LootService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * bundle アイテムの右クリック開封処理を担当します。
 */
public class BundleUseService {

    private static final String SOURCE_BUNDLE_USE = "bundle_use";

    private final ItemService itemService;
    private final LootService lootService;
    private final InventoryService inventoryService;
    private final ItemStackFactory itemStackFactory;

    /**
     * bundle 使用サービスを構築します。
     *
     * @param itemService       アイテム解決サービス
     * @param lootService       loot 解決サービス
     * @param inventoryService  インベントリ追加・消費サービス
     * @param itemStackFactory  余剰ドロップ生成用 ItemStackFactory
     */
    public BundleUseService(
        @NotNull ItemService itemService,
        @NotNull LootService lootService,
        @NotNull InventoryService inventoryService,
        @NotNull ItemStackFactory itemStackFactory
    ) {
        this.itemService = itemService;
        this.lootService = lootService;
        this.inventoryService = inventoryService;
        this.itemStackFactory = itemStackFactory;
    }

    /**
     * bundle を使用して報酬を配布します。
     *
     * @param astPlayer 使用プレイヤー
     * @param hand      使用した手
     * @param model     使用した bundle アイテム
     * @return 処理を完了した場合は {@code true}
     */
    public boolean useBundle(
        @NotNull AstPlayer astPlayer,
        @NotNull EquipmentSlot hand,
        @NotNull ItemModel model
    ) {
        ItemBundle bundle = model.getBundle();
        if (bundle == null || bundle.getLootTableId() == null || bundle.getLootTableId().isBlank()) {
            astPlayer.sendMessage(PlayerMsgId.P_5242, model.getId());
            return false;
        }

        LootModel lootModel = lootService.getLoadedOrFetch(bundle.getLootTableId());
        if (lootModel == null) {
            astPlayer.sendMessage(PlayerMsgId.P_5242, bundle.getLootTableId());
            return false;
        }

        Map<String, Integer> rewards = rollRewards(lootModel);
        if (!inventoryService.consumeHotbarItemInHand(astPlayer, hand, model.getId(), 1)) {
            astPlayer.sendMessage(PlayerMsgId.P_5245);
            return false;
        }

        int rewardKinds = 0;
        int totalGranted = 0;
        int totalDropped = 0;
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
            int granted = inventoryService.addItemToNormalInventory(
                astPlayer, rewardModel, requestedAmount, SOURCE_BUNDLE_USE);
            totalGranted += granted;

            int overflow = requestedAmount - granted;
            if (overflow > 0) {
                totalDropped += dropOverflow(astPlayer, rewardModel, overflow);
            }
        }

        playUseEffects(astPlayer, bundle);
        astPlayer.sendMessage(PlayerMsgId.P_5243, rewardKinds, totalGranted);
        if (totalDropped > 0) {
            astPlayer.sendMessage(PlayerMsgId.P_5244, totalDropped);
        }
        return true;
    }

    private @NotNull Map<String, Integer> rollRewards(@NotNull LootModel lootModel) {
        Map<String, Integer> rewards = new LinkedHashMap<>();
        ThreadLocalRandom random = ThreadLocalRandom.current();

        int rolls = Math.max(1, lootModel.getRolls());
        for (int rollIndex = 0; rollIndex < rolls; rollIndex++) {
            for (LootPoolModel pool : lootModel.getPools()) {
                int picks = Math.max(1, pool.getPick());
                for (int pickIndex = 0; pickIndex < picks; pickIndex++) {
                    LootContent content = selectContent(pool.getContents(), random);
                    if (content == null) {
                        continue;
                    }
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

    private @Nullable LootContent selectContent(
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

    private int rollAmount(@NotNull LootContent content, @NotNull ThreadLocalRandom random) {
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
                    world.dropItemNaturally(location, itemStackFactory.create(rewardModel, instance, 1));
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
                    world.dropItemNaturally(location, itemStackFactory.create(rewardModel, instance, 1));
                    dropped++;
                }
            }
            default -> {
                int remaining = amount;
                int maxStack = Math.max(1, rewardModel.getMaxStack());
                while (remaining > 0) {
                    int stackAmount = Math.min(maxStack, remaining);
                    ItemStack stack = itemStackFactory.create(rewardModel, stackAmount);
                    world.dropItemNaturally(location, stack);
                    dropped += stackAmount;
                    remaining -= stackAmount;
                }
            }
        }

        return dropped;
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

        String sound = bundle.getOnUse().getSound();
        if (sound != null && !sound.isBlank()) {
            astPlayer.getBukkit().playSound(location, sound, 1.0f, 1.0f);
        }

        String particleKey = bundle.getOnUse().getParticle();
        if (particleKey != null && !particleKey.isBlank()) {
            Particle particle = parseParticle(particleKey);
            if (particle != null) {
                world.spawnParticle(
                    particle,
                    location.clone().add(0.0, 1.0, 0.0),
                    24,
                    0.4,
                    0.5,
                    0.4,
                    0.0
                );
            }
        }
    }

    private @Nullable Particle parseParticle(@NotNull String raw) {
        String normalized = raw.trim()
            .replace(' ', '_')
            .replace('-', '_')
            .toUpperCase(Locale.ROOT);
        try {
            return Particle.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
