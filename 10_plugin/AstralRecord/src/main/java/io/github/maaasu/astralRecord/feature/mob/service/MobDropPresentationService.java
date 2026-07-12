package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.inventory.model.InventoryInstanceType;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.RuneInstance;
import io.github.maaasu.astralRecord.feature.item.service.ItemDropAnimationService;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropResult;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropResultItem;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Mob ドロップの結果表示、回収アニメーション、インベントリ付与を扱います。
 */
public final class MobDropPresentationService {
    private static final double RESULT_HEIGHT = 1.9D;
    private static final int MAX_ANIMATED_ITEMS_PER_DEFEAT = 3;
    private static final int MAX_RESULT_TEXT_ITEMS = 5;
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final String DROP_SOURCE = "mob_drop";
    private static final double ENEMY_RARE_DROP_MAX_RATE = 0.1D;
    private static final double BOSS_RARE_DROP_MAX_RATE = 5.0D;

    private final Plugin plugin;
    private final ItemService itemService;
    private final InventoryService inventoryService;
    private final ItemStackFactory itemStackFactory;
    private final ItemDropAnimationService itemDropAnimationService;
    private final PlayerSettingService playerSettingService;
    private final Executor asyncExecutor;

    /**
     * Mob ドロップの表示・付与サービスを構築します。
     *
     * @param plugin Plugin インスタンス
     * @param itemService アイテム参照サービス
     * @param inventoryService インベントリ操作サービス
     * @param itemStackFactory ItemStack 生成サービス
     * @param itemDropAnimationService ドロップ回収演出サービス
     * @param playerSettingService 通知受信設定の参照サービス
     */
    public MobDropPresentationService(
        @NotNull Plugin plugin,
        @NotNull ItemService itemService,
        @NotNull InventoryService inventoryService,
        @NotNull ItemStackFactory itemStackFactory,
        @NotNull ItemDropAnimationService itemDropAnimationService,
        @NotNull PlayerSettingService playerSettingService
    ) {
        this.plugin = plugin;
        this.itemService = itemService;
        this.inventoryService = inventoryService;
        this.itemStackFactory = itemStackFactory;
        this.itemDropAnimationService = itemDropAnimationService;
        this.playerSettingService = playerSettingService;
        this.asyncExecutor = command -> plugin.getServer().getScheduler().runTaskAsynchronously(plugin, command);
    }

    /**
     * Mob 以外の共通 drops 利用元について、結果表示と報酬付与を行います。
     *
     * @param recipient 受取プレイヤー
     * @param deathLocation ドロップ発生位置
     * @param mobName 表示名
     * @param result 抽選結果
     */
    public void presentAndGrant(
        @NotNull AstPlayer recipient,
        @NotNull Location deathLocation,
        @NotNull String mobName,
        @NotNull MobDropResult result
    ) {
        presentAndGrant(recipient, deathLocation, mobName, result, DROP_SOURCE, null);
    }

    /**
     * Mob のカテゴリを考慮し、結果表示、報酬付与、レアドロップ通知を行います。
     *
     * @param recipient 受取プレイヤー
     * @param deathLocation Mob の死亡位置
     * @param mobName Mob 表示名
     * @param result 抽選結果
     * @param mobCategory Mob カテゴリ
     */
    public void presentAndGrant(
        @NotNull AstPlayer recipient,
        @NotNull Location deathLocation,
        @NotNull String mobName,
        @NotNull MobDropResult result,
        @NotNull MobCategory mobCategory
    ) {
        presentAndGrant(recipient, deathLocation, mobName, result, DROP_SOURCE, mobCategory);
    }

    /**
     * 任意のドロップ取得元について、結果表示と報酬付与を行います。
     *
     * @param recipient 受取プレイヤー
     * @param deathLocation ドロップ発生位置
     * @param sourceName 取得元表示名
     * @param result 抽選結果
     * @param dropSource インベントリ履歴用取得元
     */
    public void presentAndGrant(
        @NotNull AstPlayer recipient,
        @NotNull Location deathLocation,
        @NotNull String sourceName,
        @NotNull MobDropResult result,
        @NotNull String dropSource
    ) {
        presentAndGrant(recipient, deathLocation, sourceName, result, dropSource, null);
    }

    /**
     * 結果表示、任意のレア通知、付与用アイテム展開と回収処理をまとめて実行します。
     *
     * @param recipient 受取プレイヤー
     * @param deathLocation ドロップ発生位置
     * @param sourceName 取得元表示名
     * @param result 抽選結果
     * @param dropSource インベントリ履歴用取得元
     * @param mobCategory レア判定対象の Mob カテゴリ。Mob 以外は {@code null}
     */
    private void presentAndGrant(
        @NotNull AstPlayer recipient,
        @NotNull Location deathLocation,
        @NotNull String sourceName,
        @NotNull MobDropResult result,
        @NotNull String dropSource,
        @Nullable MobCategory mobCategory
    ) {
        Player player = recipient.getBukkit();
        if (!player.isOnline()) {
            return;
        }

        List<ResolvedDropItem> resolvedItems = resolveItems(result);
        spawnResultText(player, deathLocation, sourceName, result, resolvedItems);
        if (mobCategory != null) {
            announceRareDrops(recipient, mobCategory, resolvedItems);
        }
        List<ResolvedDropItem> grantItems = expandInstanceItems(resolvedItems);
        for (int index = 0; index < grantItems.size(); index++) {
            ResolvedDropItem item = grantItems.get(index);
            if (index < MAX_ANIMATED_ITEMS_PER_DEFEAT) {
                spawnCollectingItem(recipient, deathLocation, item, index, dropSource);
                continue;
            }
            grantWithoutAnimation(recipient, deathLocation, item, dropSource);
        }
    }

    /**
     * 当選 item ID をロード済みアイテムモデルへ解決します。
     *
     * @param result ドロップ抽選結果
     * @return 解決できた当選アイテム一覧
     */
    private @NotNull List<ResolvedDropItem> resolveItems(@NotNull MobDropResult result) {
        List<ResolvedDropItem> resolved = new ArrayList<>();
        for (MobDropResultItem entry : result.items()) {
            ItemModel model = itemService.findLoadedById(entry.itemId());
            if (model == null) {
                model = itemService.loadItem(entry.itemId());
            }
            if (model == null) {
                continue;
            }

            int amount = Math.max(1, entry.amount());
            resolved.add(new ResolvedDropItem(model, amount, entry.dropRate()));
        }
        return resolved;
    }

    /**
     * 装備・ルーンの複数個ドロップを、インスタンス生成単位の 1 個ずつに展開します。
     *
     * @param items 解決済みドロップ一覧
     * @return 付与単位へ展開した一覧
     */
    private @NotNull List<ResolvedDropItem> expandInstanceItems(@NotNull List<ResolvedDropItem> items) {
        List<ResolvedDropItem> expanded = new ArrayList<>();
        for (ResolvedDropItem item : items) {
            ItemCategory category = ItemCategory.fromApiValue(item.model().getCategory());
            if (category == ItemCategory.EQUIPMENT || category == ItemCategory.RUNE) {
                for (int index = 0; index < item.amount(); index++) {
                    expanded.add(new ResolvedDropItem(item.model(), 1, item.dropRate()));
                }
                continue;
            }
            expanded.add(item);
        }
        return expanded;
    }

    private void spawnResultText(
        @NotNull Player viewer,
        @NotNull Location deathLocation,
        @NotNull String mobName,
        @NotNull MobDropResult result,
        @NotNull List<ResolvedDropItem> items
    ) {
        World world = deathLocation.getWorld();
        if (world == null) {
            return;
        }

        Location location = deathLocation.clone().add(0.0D, RESULT_HEIGHT, 0.0D);
        TextDisplay display = world.spawn(location, TextDisplay.class, text -> {
            text.setPersistent(false);
            text.setGravity(false);
            text.setInvulnerable(true);
            text.setSilent(true);
            text.setVisibleByDefault(false);
            text.setBillboard(Display.Billboard.CENTER);
            text.setShadowed(true);
            text.setLineWidth(280);
            text.setViewRange(48.0F);
            text.setDefaultBackground(false);
            text.setBackgroundColor(Color.fromARGB(128, 8, 4, 18));
            text.text(LEGACY.deserialize(ColorCodeUtil.translateAlternateColorCodes(formatResultText(mobName, result, items))));
            text.setTransformation(scale(0.8F));
        });
        viewer.showEntity(plugin, display);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> removeIfValid(display), 86L);
    }

    /**
     * ドロップ結果を、アイテム名・数量・設定上の確率を含む TextDisplay 用文字列へ整形します。
     *
     * @param mobName Mob または取得元の表示名
     * @param result 経験値・金銭を含む抽選結果
     * @param items 解決済み当選アイテム
     * @return legacy color code を含むリザルト文字列
     */
    private @NotNull String formatResultText(
        @NotNull String mobName,
        @NotNull MobDropResult result,
        @NotNull List<ResolvedDropItem> items
    ) {
        StringBuilder text = new StringBuilder("&6&lRESULT &f").append(mobName);
        text.append("\n&eEXP &f+").append(result.exp());
        text.append("  &6Money &f+").append(result.money());
        text.append("\n&aDrop &f");
        if (items.isEmpty()) {
            text.append("none");
            return text.toString();
        }
        int shownItems = Math.min(items.size(), MAX_RESULT_TEXT_ITEMS);
        for (int index = 0; index < shownItems; index++) {
            if (index > 0) {
                text.append("&7, &f");
            }
            ResolvedDropItem item = items.get(index);
            text.append(ColorCodeUtil.toLegacyText(item.model().getName(), item.model().getId()))
                .append(" x").append(item.amount())
                .append(" &7(").append(formatDropRate(item.dropRate())).append("%)&f");
        }
        int hiddenItems = items.size() - shownItems;
        if (hiddenItems > 0) {
            text.append("&7, &f+").append(hiddenItems).append(" more");
        }
        return text.toString();
    }

    /**
     * 当選したレアドロップを、表示設定が有効なオンラインプレイヤーへ通知します。
     *
     * @param recipient ドロップ受取プレイヤー
     * @param mobCategory 撃破 Mob カテゴリ
     * @param items 解決済み当選アイテム
     */
    private void announceRareDrops(
        @NotNull AstPlayer recipient,
        @NotNull MobCategory mobCategory,
        @NotNull List<ResolvedDropItem> items
    ) {
        for (ResolvedDropItem item : items) {
            if (!isRareDrop(mobCategory, item.dropRate())) {
                continue;
            }
            String itemName = ColorCodeUtil.toLegacyText(item.model().getName(), item.model().getId());
            for (Player viewer : plugin.getServer().getOnlinePlayers()) {
                if (playerSettingService.isDropLogDisplayEnabled(viewer.getUniqueId())) {
                    PlayerMessageService.getInstance().send(
                        viewer,
                        PlayerMsgId.P_5728,
                        recipient.getBukkit().getName(),
                        itemName,
                        item.amount(),
                        formatDropRate(item.dropRate())
                    );
                }
            }
        }
    }

    /**
     * Mob カテゴリ別の閾値に基づいてレアドロップかを判定します。
     *
     * @param mobCategory Mob カテゴリ
     * @param dropRate 設定上のドロップ確率（%）
     * @return レアドロップなら {@code true}
     */
    static boolean isRareDrop(@NotNull MobCategory mobCategory, double dropRate) {
        double threshold = mobCategory == MobCategory.BOSS
            ? BOSS_RARE_DROP_MAX_RATE
            : ENEMY_RARE_DROP_MAX_RATE;
        return dropRate >= 0.0D && dropRate <= threshold;
    }

    /**
     * ドロップ確率を不要な末尾ゼロを除いた表示へ整形します。
     *
     * @param dropRate ドロップ確率（%）
     * @return パーセント記号を含まない表示文字列
     */
    public static @NotNull String formatDropRate(double dropRate) {
        return BigDecimal.valueOf(dropRate).stripTrailingZeros().toPlainString();
    }

    private void spawnCollectingItem(
        @NotNull AstPlayer recipient,
        @NotNull Location deathLocation,
        @NotNull ResolvedDropItem item,
        int index,
        @NotNull String dropSource
    ) {
        Player player = recipient.getBukkit();
        World world = deathLocation.getWorld();
        if (world == null || !player.isOnline()) {
            return;
        }
        if (!inventoryService.canAddItemToNormalInventory(recipient, item.model(), item.amount())) {
            return;
        }

        CompletableFuture<PreparedDropGrant> future = prepareDropAsync(recipient, item, dropSource);
        future.whenComplete((ignored, ex) -> {
            if (ex != null) {
                Logger.error(LogId.E_5202, ex, item.model().getId());
            }
        });

        Location dropLocation = deathLocation.clone();
        itemDropAnimationService.playCollectingDrop(
            player,
            deathLocation,
            item.model(),
            item.amount(),
            index,
            future,
            () -> grantPreparedItem(recipient, future, dropSource),
            () -> handleCancelledPreparedItem(dropLocation, future)
        );
    }

    private void grantWithoutAnimation(
        @NotNull AstPlayer recipient,
        @NotNull Location deathLocation,
        @NotNull ResolvedDropItem item,
        @NotNull String dropSource
    ) {
        if (!inventoryService.canAddItemToNormalInventory(recipient, item.model(), item.amount())) {
            return;
        }
        CompletableFuture<PreparedDropGrant> future = prepareDropAsync(recipient, item, dropSource);
        future.whenComplete((prepared, ex) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (ex != null) {
                Logger.error(LogId.E_5202, ex, item.model().getId());
                return;
            }
            if (prepared == null || !recipient.getBukkit().isOnline()) {
                if (prepared != null) {
                    dropPreparedItem(deathLocation, prepared);
                }
                return;
            }
            grantPreparedItem(recipient, CompletableFuture.completedFuture(prepared), dropSource);
        }));
    }

    private @NotNull CompletableFuture<PreparedDropGrant> prepareDropAsync(
        @NotNull AstPlayer recipient,
        @NotNull ResolvedDropItem item,
        @NotNull String dropSource
    ) {
        ItemCategory category = ItemCategory.fromApiValue(item.model().getCategory());
        if (category == ItemCategory.EQUIPMENT) {
            return CompletableFuture.supplyAsync(() -> {
                EquipmentInstance instance = itemService.createEquipmentInstance(
                    item.model().getId(),
                    recipient.getAccount().getUuid().toString(),
                    dropSource,
                    recipient.getAccount().getUuid().toString()
                );
                UUID instanceId = instance == null ? null : parseUuidOrNull(instance.getEquipmentInstanceId());
                if (instance == null || instanceId == null) {
                    throw new IllegalStateException("Failed to create equipment instance for " + item.model().getId());
                }
                return PreparedDropGrant.equipment(item, instanceId, instance);
            }, asyncExecutor);
        }
        if (category == ItemCategory.RUNE) {
            return CompletableFuture.supplyAsync(() -> {
                RuneInstance instance = itemService.createRuneInstance(
                    item.model().getId(),
                    recipient.getAccount().getUuid().toString(),
                    dropSource,
                    recipient.getAccount().getUuid().toString()
                );
                UUID instanceId = instance == null ? null : parseUuidOrNull(instance.getRuneInstanceId());
                if (instance == null || instanceId == null) {
                    throw new IllegalStateException("Failed to create rune instance for " + item.model().getId());
                }
                return PreparedDropGrant.rune(item, instanceId, instance);
            }, asyncExecutor);
        }
        return CompletableFuture.completedFuture(PreparedDropGrant.stacked(item));
    }

    private void grantPreparedItem(
        @NotNull AstPlayer recipient,
        @NotNull CompletableFuture<PreparedDropGrant> future,
        @NotNull String dropSource
    ) {
        PreparedDropGrant prepared = joinPrepared(future);
        if (prepared == null || !recipient.getBukkit().isOnline()) {
            return;
        }

        switch (prepared.kind()) {
            case STACKED -> grantStackedItem(recipient, prepared, dropSource);
            case EQUIPMENT, RUNE -> grantPreparedInstance(recipient, prepared);
        }
    }

    private int grantStackedItem(@NotNull AstPlayer recipient, @NotNull PreparedDropGrant prepared, @NotNull String dropSource) {
        inventoryService.addItemToNormalInventory(
            recipient,
            prepared.item().model(),
            prepared.item().amount(),
            dropSource
        );
        return 0;
    }

    private int grantPreparedInstance(@NotNull AstPlayer recipient, @NotNull PreparedDropGrant prepared) {
        inventoryService.addPreparedInstanceToNormalInventory(
            recipient,
            prepared.item().model(),
            prepared.instanceType(),
            prepared.instanceId()
        );
        return 0;
    }

    private void handleCancelledPreparedItem(
        @NotNull Location dropLocation,
        @NotNull CompletableFuture<PreparedDropGrant> future
    ) {
        future.thenAccept(prepared ->
            plugin.getServer().getScheduler().runTask(plugin, () -> dropPreparedItem(dropLocation, prepared))
        ).exceptionally(ex -> null);
    }

    private int dropPreparedItem(@NotNull Location location, @NotNull PreparedDropGrant prepared) {
        return switch (prepared.kind()) {
            case STACKED -> dropStackedItem(location, prepared.item().model(), prepared.item().amount());
            case EQUIPMENT -> dropEquipmentInstance(location, prepared.item().model(), prepared.equipmentInstance());
            case RUNE -> dropRuneInstance(location, prepared.item().model(), prepared.runeInstance());
        };
    }

    private int dropStackedItem(@NotNull Location location, @NotNull ItemModel model, int amount) {
        World world = location.getWorld();
        if (world == null) {
            return 0;
        }

        int dropped = 0;
        int remaining = Math.max(0, amount);
        int maxStack = Math.max(1, model.getMaxStack());
        while (remaining > 0) {
            int stackAmount = Math.min(maxStack, remaining);
            ItemStack stack = itemStackFactory.create(model, stackAmount);
            world.dropItemNaturally(location, itemStackFactory.asDisplayStack(stack));
            dropped += stackAmount;
            remaining -= stackAmount;
        }
        return dropped;
    }

    private int dropEquipmentInstance(
        @NotNull Location location,
        @NotNull ItemModel model,
        @Nullable EquipmentInstance instance
    ) {
        World world = location.getWorld();
        if (world == null || instance == null) {
            return 0;
        }

        ItemStack stack = itemStackFactory.create(model, instance, 1);
        world.dropItemNaturally(location, itemStackFactory.asDisplayStack(stack));
        return 1;
    }

    private int dropRuneInstance(
        @NotNull Location location,
        @NotNull ItemModel model,
        @Nullable RuneInstance instance
    ) {
        World world = location.getWorld();
        if (world == null || instance == null) {
            return 0;
        }

        ItemStack stack = itemStackFactory.create(model, instance, 1);
        world.dropItemNaturally(location, itemStackFactory.asDisplayStack(stack));
        return 1;
    }

    private @Nullable PreparedDropGrant joinPrepared(@NotNull CompletableFuture<PreparedDropGrant> future) {
        try {
            return future.join();
        } catch (RuntimeException ex) {
            Logger.error(LogId.E_5202, ex, "mob_drop_prepare");
            return null;
        }
    }

    private @Nullable UUID parseUuidOrNull(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static @NotNull Transformation scale(float value) {
        return new Transformation(
            new Vector3f(),
            new Quaternionf(),
            new Vector3f(value, value, value),
            new Quaternionf()
        );
    }

    private void removeIfValid(@Nullable Entity entity) {
        if (entity != null && entity.isValid()) {
            entity.remove();
        }
    }

    private enum PreparedDropKind {
        STACKED,
        EQUIPMENT,
        RUNE
    }

    /**
     * 表示名を解決済みのドロップアイテム。
     *
     * @param model アイテムモデル
     * @param amount 数量
     * @param dropRate 設定上のドロップ確率（%）
     */
    private record ResolvedDropItem(@NotNull ItemModel model, int amount, double dropRate) {
        private ResolvedDropItem {
            amount = Math.max(1, amount);
            dropRate = Math.max(0.0D, Math.min(100.0D, dropRate));
        }
    }

    private record PreparedDropGrant(
        @NotNull PreparedDropKind kind,
        @NotNull ResolvedDropItem item,
        @Nullable InventoryInstanceType instanceType,
        @Nullable UUID instanceId,
        @Nullable EquipmentInstance equipmentInstance,
        @Nullable RuneInstance runeInstance
    ) {
        private static @NotNull PreparedDropGrant stacked(@NotNull ResolvedDropItem item) {
            return new PreparedDropGrant(PreparedDropKind.STACKED, item, null, null, null, null);
        }

        private static @NotNull PreparedDropGrant equipment(
            @NotNull ResolvedDropItem item,
            @NotNull UUID instanceId,
            @NotNull EquipmentInstance instance
        ) {
            return new PreparedDropGrant(
                PreparedDropKind.EQUIPMENT,
                item,
                InventoryInstanceType.EQUIPMENT,
                instanceId,
                instance,
                null
            );
        }

        private static @NotNull PreparedDropGrant rune(
            @NotNull ResolvedDropItem item,
            @NotNull UUID instanceId,
            @NotNull RuneInstance instance
        ) {
            return new PreparedDropGrant(
                PreparedDropKind.RUNE,
                item,
                InventoryInstanceType.RUNE,
                instanceId,
                null,
                instance
            );
        }
    }
}
