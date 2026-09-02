package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.inventory.model.InventoryInstanceType;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemDropAnimationService;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropResult;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropResultItem;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.afk.service.AfkService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.display.DisplaySeparators;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
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
    private static final double RESULT_ANIMATION_START_HEIGHT = 1.55D;
    private static final long RESULT_ANIMATION_DELAY_TICKS = 1L;
    private static final int RESULT_ANIMATION_INTERPOLATION_TICKS = 8;
    private static final long RESULT_DISPLAY_DURATION_TICKS = 86L;
    private static final int MAX_ANIMATED_ITEMS_PER_DEFEAT = 3;
    private static final int MAX_RESULT_TEXT_ITEMS = 5;
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final String DROP_SOURCE = "mob_drop";
    private static final double ENEMY_RARE_DROP_MAX_RATE = 0.1D;
    private static final double BOSS_RARE_DROP_MAX_RATE = 5.0D;
    private static final Sound RARE_DROP_SOUND = Sound.BLOCK_AMETHYST_BLOCK_BREAK;
    private static final float RARE_DROP_SOUND_VOLUME = 1.0F;
    private static final float RARE_DROP_SOUND_PITCH = 1.0F;

    private final Plugin plugin;
    private final ItemService itemService;
    private final InventoryService inventoryService;
    private final ItemStackFactory itemStackFactory;
    private final ItemDropAnimationService itemDropAnimationService;
    private final PlayerSettingService playerSettingService;
    private final Executor asyncExecutor;
    private AfkService afkService;

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
     * AFK中の自動ドロップ付与を抑止する状態サービスを設定します。
     *
     * @param afkService AFK状態サービス
     */
    public void setAfkService(@NotNull AfkService afkService) {
        this.afkService = afkService;
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
        if (!player.isOnline() || (afkService != null && afkService.isAfk(recipient))) {
            return;
        }

        if (result.money() > 0) {
            inventoryService.addGold(recipient, result.money());
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
     * 装備の複数個ドロップを、インスタンス生成単位の 1 個ずつに展開します。
     *
     * @param items 解決済みドロップ一覧
     * @return 付与単位へ展開した一覧
     */
    private @NotNull List<ResolvedDropItem> expandInstanceItems(@NotNull List<ResolvedDropItem> items) {
        List<ResolvedDropItem> expanded = new ArrayList<>();
        for (ResolvedDropItem item : items) {
            ItemCategory category = ItemCategory.fromApiValue(item.model().getCategory());
            if (category == ItemCategory.EQUIPMENT) {
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

        Location location = deathLocation.clone().add(0.0D, RESULT_ANIMATION_START_HEIGHT, 0.0D);
        Location finalLocation = deathLocation.clone().add(0.0D, RESULT_HEIGHT, 0.0D);
        TextDisplay display = world.spawn(location, TextDisplay.class, text -> {
            text.setPersistent(false);
            text.setGravity(false);
            text.setInvulnerable(true);
            text.setSilent(true);
            text.setVisibleByDefault(false);
            text.setBillboard(Display.Billboard.CENTER);
            text.setShadowed(true);
            text.setLineWidth(220);
            text.setViewRange(48.0F);
            text.setDefaultBackground(false);
            text.setBackgroundColor(Color.fromARGB(128, 8, 4, 18));
            text.text(LEGACY.deserialize(ColorCodeUtil.translateAlternateColorCodes(formatResultText(mobName, result, items))));
            text.setInterpolationDelay(0);
            text.setInterpolationDuration(RESULT_ANIMATION_INTERPOLATION_TICKS);
            text.setTeleportDuration(RESULT_ANIMATION_INTERPOLATION_TICKS);
            text.setTransformation(scale(0.65F));
        });
        viewer.showEntity(plugin, display);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!display.isValid()) {
                return;
            }
            display.teleport(finalLocation);
            display.setTransformation(scale(0.8F));
        }, RESULT_ANIMATION_DELAY_TICKS);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> removeIfValid(display), RESULT_DISPLAY_DURATION_TICKS);
    }

    /**
     * ドロップ結果を、日本語の縦並びで TextDisplay 用文字列へ整形します。
     *
     * @param mobName Mob または取得元の表示名
     * @param result 経験値・金銭を含む抽選結果。1 未満の固定報酬は表示しない
     * @param items 解決済み当選アイテム
     * @return legacy color code を含むリザルト文字列
     */
    static @NotNull String formatResultText(
        @NotNull String mobName,
        @NotNull MobDropResult result,
        @NotNull List<ResolvedDropItem> items
    ) {
        StringBuilder text = new StringBuilder("&6&l◆ 討伐報酬 ◆");
        text.append("\n&f").append(mobName);
        if (result.exp() >= 1) {
            text.append("\n&e経験値 &f+").append(result.exp());
        }
        if (result.money() >= 1) {
            text.append("\n&6ゴールド &f+").append(result.money());
        }
        text.append("\n&8").append(DisplaySeparators.SECTION);
        text.append("\n&a獲得アイテム");
        if (items.isEmpty()) {
            text.append("\n&7・なし");
            return text.toString();
        }
        int shownItems = Math.min(items.size(), MAX_RESULT_TEXT_ITEMS);
        for (int index = 0; index < shownItems; index++) {
            ResolvedDropItem item = items.get(index);
            text.append("\n&7・ &f")
                .append(ColorCodeUtil.toLegacyText(item.model().getName(), item.model().getId()))
                .append(" x").append(item.amount())
                .append(" &8(").append(formatDropRate(item.dropRate())).append("%)");
        }
        int hiddenItems = items.size() - shownItems;
        if (hiddenItems > 0) {
            text.append("\n&7・ほか ").append(hiddenItems).append(" 件");
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
                    viewer.playSound(
                        viewer.getLocation(),
                        RARE_DROP_SOUND,
                        SoundCategory.PLAYERS,
                        RARE_DROP_SOUND_VOLUME,
                        RARE_DROP_SOUND_PITCH
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

    /**
     * 回収演出付きドロップを開始します。装備は演出開始前に BAG slot を予約します。
     *
     * @param recipient 受取プレイヤー
     * @param deathLocation ドロップ発生位置
     * @param item 付与対象アイテム
     * @param index 同時演出のずらし番号
     * @param dropSource インスタンス生成元
     */
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

        InventoryService.PreparedInstanceSlotReservation reservation = reserveInstanceSlotOrNotify(recipient, item);
        if (requiresPreparedInstanceSlot(item) && reservation == null) {
            return;
        }
        CompletableFuture<PreparedDropGrant> future = prepareDropAsync(recipient, item, dropSource);
        future.whenComplete((ignored, ex) -> {
            if (ex != null) {
                releaseReservation(reservation);
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
            () -> grantPreparedItem(recipient, dropLocation, future, dropSource, reservation),
            () -> handleCancelledPreparedItem(dropLocation, future, reservation)
        );
    }

    /**
     * 演出上限を超えたドロップを、同じ容量判定・個体予約契約で付与します。
     *
     * @param recipient 受取プレイヤー
     * @param deathLocation ドロップ発生位置
     * @param item 付与対象アイテム
     * @param dropSource インスタンス生成元
     */
    private void grantWithoutAnimation(
        @NotNull AstPlayer recipient,
        @NotNull Location deathLocation,
        @NotNull ResolvedDropItem item,
        @NotNull String dropSource
    ) {
        InventoryService.PreparedInstanceSlotReservation reservation = reserveInstanceSlotOrNotify(recipient, item);
        if (requiresPreparedInstanceSlot(item) && reservation == null) {
            return;
        }
        CompletableFuture<PreparedDropGrant> future = prepareDropAsync(recipient, item, dropSource);
        future.whenComplete((prepared, ex) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (ex != null) {
                releaseReservation(reservation);
                Logger.error(LogId.E_5202, ex, item.model().getId());
                return;
            }
            if (prepared == null || !recipient.getBukkit().isOnline()) {
                if (prepared != null) {
                    releaseReservation(reservation);
                    dropPreparedItem(deathLocation, prepared);
                } else {
                    releaseReservation(reservation);
                }
                return;
            }
            grantPreparedItem(
                recipient,
                deathLocation,
                CompletableFuture.completedFuture(prepared),
                dropSource,
                reservation
            );
        }));
    }

    /**
     * 装備の API 個体生成前に BAG slot を予約し、空き不足時は取得拒否を通知します。
     *
     * @param recipient 受取プレイヤー
     * @param item 付与対象アイテム
     * @return 装備で予約できた場合の予約。通常スタック品または予約不能時は {@code null}
     */
    private @Nullable InventoryService.PreparedInstanceSlotReservation reserveInstanceSlotOrNotify(
        @NotNull AstPlayer recipient,
        @NotNull ResolvedDropItem item
    ) {
        if (!requiresPreparedInstanceSlot(item)) {
            return null;
        }
        InventoryService.PreparedInstanceSlotReservationResult result =
            inventoryService.reserveBagSlotForPreparedInstance(recipient, item.model());
        if (!result.reserved()) {
            notifyInventoryFullIfKnown(recipient, result.remainingBagSlots());
            return null;
        }
        return result.reservation();
    }

    /**
     * 指定ドロップが個体 API を生成する装備またはルーンかを判定します。
     *
     * @param item 判定対象ドロップ
     * @return BAG slot の事前予約が必要な場合 {@code true}
     */
    private boolean requiresPreparedInstanceSlot(@NotNull ResolvedDropItem item) {
        ItemCategory category = ItemCategory.fromApiValue(item.model().getCategory());
        return category == ItemCategory.EQUIPMENT;
    }

    /**
     * 個体が必要なドロップだけを非同期で API 生成し、通常スタック品は即時結果に変換します。
     *
     * @param recipient 受取プレイヤー
     * @param item 生成対象ドロップ
     * @param dropSource インスタンス生成元
     * @return 付与可能な準備済みドロップの future
     */
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
        return CompletableFuture.completedFuture(PreparedDropGrant.stacked(item));
    }

    /**
     * 演出または非演出の完了時に、準備済みアイテムをインベントリへ確定付与します。
     *
     * @param recipient 受取プレイヤー
     * @param dropLocation 非容量系の取消・退出時に使用するドロップ位置
     * @param future 準備済みドロップ future
     * @param dropSource 通常スタック品の付与元
     * @param reservation 装備用の事前予約。通常スタック品では {@code null}
     */
    private void grantPreparedItem(
        @NotNull AstPlayer recipient,
        @NotNull Location dropLocation,
        @NotNull CompletableFuture<PreparedDropGrant> future,
        @NotNull String dropSource,
        @Nullable InventoryService.PreparedInstanceSlotReservation reservation
    ) {
        PreparedDropGrant prepared = joinPrepared(future);
        if (prepared == null) {
            releaseReservation(reservation);
            return;
        }
        if (!recipient.getBukkit().isOnline()) {
            releaseReservation(reservation);
            dropPreparedItem(dropLocation, prepared);
            return;
        }

        switch (prepared.kind()) {
            case STACKED -> grantStackedItem(recipient, prepared, dropSource);
            case EQUIPMENT -> grantPreparedInstance(recipient, dropLocation, prepared, reservation);
        }
    }

    /**
     * 通常スタック品を付与し、容量不足の残数はワールドへ出さず破棄します。
     *
     * @param recipient 受取プレイヤー
     * @param prepared 準備済み通常スタック品
     * @param dropSource 付与元
     * @return 実際に付与できた数
     */
    private int grantStackedItem(
        @NotNull AstPlayer recipient,
        @NotNull PreparedDropGrant prepared,
        @NotNull String dropSource
    ) {
        return grantStackedItemDiscardingShortfall(
            recipient,
            prepared.item().model(),
            prepared.item().amount(),
            dropSource
        );
    }

    /**
     * 通常スタック品の付与結果を容量通知へ変換し、入りきらない残数を破棄します。
     *
     * @param recipient 受取プレイヤー
     * @param model 追加するアイテム定義
     * @param requested 追加希望数
     * @param dropSource 付与元
     * @return 実際に付与できた数
     */
    int grantStackedItemDiscardingShortfall(
        @NotNull AstPlayer recipient,
        @NotNull ItemModel model,
        int requested,
        @NotNull String dropSource
    ) {
        InventoryService.NormalInventoryGrantResult result = inventoryService.addItemToNormalInventoryWithCapacityResult(
            recipient,
            model,
            requested,
            dropSource
        );
        notifyInventoryCapacity(recipient, result);
        return Math.max(0, result.grantedAmount());
    }

    /**
     * 予約済み slot へ装備個体を確定追加します。
     * <p>
     * 通常の容量不足は API 個体生成前に予約で拒否されるため、ここでの失敗は state 入れ替わりなど
     * 非容量系の既存 world-drop fallback として扱います。
     *
     * @param recipient 受取プレイヤー
     * @param dropLocation 非容量系 fallback の位置
     * @param prepared API 生成済み個体
     * @param reservation 事前予約した BAG slot
     * @return インベントリへ追加した場合は 1、それ以外は fallback 数
     */
    private int grantPreparedInstance(
        @NotNull AstPlayer recipient,
        @NotNull Location dropLocation,
        @NotNull PreparedDropGrant prepared,
        @Nullable InventoryService.PreparedInstanceSlotReservation reservation
    ) {
        if (reservation == null) {
            return 0;
        }
        InventoryInstanceType instanceType = prepared.instanceType();
        UUID instanceId = prepared.instanceId();
        if (instanceType == null || instanceId == null) {
            releaseReservation(reservation);
            return dropPreparedItem(dropLocation, prepared);
        }
        InventoryService.PreparedInstanceReservationCompletion completion =
            inventoryService.completePreparedInstanceReservation(
            recipient,
            prepared.item().model(),
            instanceType,
            instanceId,
            reservation
        );
        if (completion.completed()) {
            notifyInventoryCapacityAfterReservedInstance(recipient, completion.remainingBagSlots());
            return 1;
        }
        releaseReservation(reservation);
        return dropPreparedItem(dropLocation, prepared);
    }

    /**
     * 回収演出が取消された場合、予約を解除して従来どおり準備済みアイテムをワールドへ戻します。
     *
     * @param dropLocation world-drop fallback の位置
     * @param future 準備済みドロップ future
     * @param reservation 装備用の事前予約。通常スタック品では {@code null}
     */
    private void handleCancelledPreparedItem(
        @NotNull Location dropLocation,
        @NotNull CompletableFuture<PreparedDropGrant> future,
        @Nullable InventoryService.PreparedInstanceSlotReservation reservation
    ) {
        future.thenAccept(prepared ->
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                releaseReservation(reservation);
                dropPreparedItem(dropLocation, prepared);
            })
        ).exceptionally(ex -> {
            releaseReservation(reservation);
            return null;
        });
    }

    /**
     * 通常スタック品の付与結果から、満杯または残り3枠の容量通知を送ります。
     *
     * @param recipient 受取プレイヤー
     * @param result state lock 内で確定した付与結果
     */
    private void notifyInventoryCapacity(
        @NotNull AstPlayer recipient,
        @NotNull InventoryService.NormalInventoryGrantResult result
    ) {
        if (result.hasShortfall() && result.remainingBagSlots() == 0) {
            notifyInventoryFullIfKnown(recipient, result.remainingBagSlots());
            return;
        }
        if (result.consumedNewBagSlot() && result.remainingBagSlots() == 3) {
            notifyInventoryLow(recipient, result.remainingBagSlots());
        }
    }

    /**
     * 予約済み個体の確定追加後、実際に残り3枠になった場合だけ容量低下を通知します。
     *
     * @param recipient 受取プレイヤー
     * @param remainingBagSlots state lock 内で確定した付与後の BAG 空き slot 数
     */
    private void notifyInventoryCapacityAfterReservedInstance(
        @NotNull AstPlayer recipient,
        int remainingBagSlots
    ) {
        if (remainingBagSlots == 3) {
            notifyInventoryLow(recipient, remainingBagSlots);
        }
    }

    /**
     * BAG が満杯であることを通知します。state 未登録を示す値では通知しません。
     *
     * @param recipient 受取プレイヤー
     * @param remainingBagSlots 判定時の残り BAG slot 数
     */
    private void notifyInventoryFullIfKnown(@NotNull AstPlayer recipient, int remainingBagSlots) {
        if (remainingBagSlots != 0) {
            return;
        }
        Player player = recipient.getBukkit();
        if (!player.isOnline()) {
            return;
        }
        PlayerMessageService.getInstance().send(recipient, PlayerMsgId.P_5241);
        GuiSound.DENY.play(player);
    }

    /**
     * BAG の空き枠が少なくなったことを通知します。
     *
     * @param recipient 受取プレイヤー
     * @param remainingBagSlots 通知する残り BAG slot 数
     */
    private void notifyInventoryLow(@NotNull AstPlayer recipient, int remainingBagSlots) {
        Player player = recipient.getBukkit();
        if (!player.isOnline()) {
            return;
        }
        PlayerMessageService.getInstance().send(recipient, PlayerMsgId.P_5244, remainingBagSlots);
        GuiSound.DENY.play(player);
    }

    /**
     * 個体生成に使用しなかった BAG slot 予約を安全に解除します。
     *
     * @param reservation 解除対象。通常スタック品では {@code null}
     */
    private void releaseReservation(@Nullable InventoryService.PreparedInstanceSlotReservation reservation) {
        if (reservation != null) {
            inventoryService.releasePreparedInstanceReservation(reservation);
        }
    }

    private int dropPreparedItem(@NotNull Location location, @NotNull PreparedDropGrant prepared) {
        return switch (prepared.kind()) {
            case STACKED -> dropStackedItem(location, prepared.item().model(), prepared.item().amount());
            case EQUIPMENT -> dropEquipmentInstance(location, prepared.item().model(), prepared.equipmentInstance());
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
        EQUIPMENT
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
        @Nullable EquipmentInstance equipmentInstance
    ) {
        private static @NotNull PreparedDropGrant stacked(@NotNull ResolvedDropItem item) {
            return new PreparedDropGrant(PreparedDropKind.STACKED, item, null, null, null);
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
                instance
            );
        }
    }
}
