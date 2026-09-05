package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemReference;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import java.util.function.Predicate;

public final class EquipmentDurabilityService {
    private static final double WEAPON_HIT_CONSUME_CHANCE = 0.10D;
    private static final double ARMOR_DAMAGE_TAKEN_CONSUME_CHANCE = 0.20D;
    private static final double ACCESSORY_HIT_CONSUME_CHANCE = 0.06D;
    private static final double ACCESSORY_DAMAGE_TAKEN_CONSUME_CHANCE = 0.10D;
    private static final int LOW_DURABILITY_WARNING_MAX_PERCENT = 5;
    private static final int LOW_DURABILITY_WARNING_MIN_PERCENT = 1;

    private final InventoryService inventoryService;
    private final ItemService itemService;
    private final ItemReferenceResolver itemReferenceResolver;
    private final DoubleSupplier randomValueSupplier;
    private StatusService statusService;

    /**
     * 装備耐久値の判定・減少サービスを初期化します。
     *
     * @param inventoryService 装備中アイテムと表示更新に使うインベントリサービス
     * @param itemService 装備インスタンスの耐久値更新に使うアイテムサービス
     */
    public EquipmentDurabilityService(
        @NotNull InventoryService inventoryService,
        @NotNull ItemService itemService
    ) {
        this(inventoryService, itemService, () -> ThreadLocalRandom.current().nextDouble());
    }

    EquipmentDurabilityService(
        @NotNull InventoryService inventoryService,
        @NotNull ItemService itemService,
        @NotNull DoubleSupplier randomValueSupplier
    ) {
        this.inventoryService = inventoryService;
        this.itemService = itemService;
        this.itemReferenceResolver = new ItemReferenceResolver(itemService);
        this.randomValueSupplier = randomValueSupplier;
    }

    /**
     * 耐久値が 0 になったときにステータス再計算するサービスを設定します。
     *
     * @param statusService ステータス再計算サービス。未設定の場合は再計算を行いません。
     */
    public void setStatusService(@Nullable StatusService statusService) {
        this.statusService = statusService;
    }

    /**
     * メインハンドの武器が使用可能な耐久値を持つか判定します。
     * 装備インスタンスでないアイテム、武器でない装備、耐久値を持たない武器は使用可能として扱います。
     *
     * @param player 判定対象プレイヤー
     * @return 武器攻撃に使用できる場合は {@code true}
     */
    public boolean canUseMainHandWeapon(@NotNull AstPlayer player) {
        return canUseMainHandEquipment(player, ItemEquipmentSlot.WEAPON);
    }

    /**
     * メインハンドの採集ツールが使用可能な耐久値を持つか判定します。
     * 装備インスタンスでないアイテム、ツールでない装備、耐久値を持たないツールは使用可能として扱います。
     *
     * @param player 判定対象プレイヤー
     * @return 採集ツールとして使用できる場合は {@code true}
     */
    public boolean canUseMainHandTool(@NotNull AstPlayer player) {
        return canUseMainHandEquipment(player, ItemEquipmentSlot.TOOL);
    }

    private boolean canUseMainHandEquipment(
        @NotNull AstPlayer player,
        @NotNull ItemEquipmentSlot expectedSlot
    ) {
        ItemReference reference = inventoryService.getItemReferenceInHand(player, EquipmentSlot.HAND);
        if (reference == null || !reference.hasEquipmentInstanceId()) {
            return true;
        }
        ItemModel model = itemReferenceResolver.resolveItemModel(reference);
        if (model == null || model.getEquipment() == null || model.getEquipment().getSlot() != expectedSlot) {
            return true;
        }
        EquipmentInstance instance = itemReferenceResolver.resolveEquipmentInstance(reference);
        return !isBroken(instance);
    }

    /**
     * 攻撃命中時に、武器と攻撃寄与アクセサリーの耐久値を確率で減少させます。
     * 実ダメージまたはシールドダメージが発生していない場合は何もしません。
     *
     * @param attacker 攻撃者。プレイヤー以外の場合は処理しません。
     * @param result ダメージ計算結果
     */
    public void consumeOnAttackHit(@Nullable AstEntity attacker, @NotNull DamageResult result) {
        if (!isEffectiveHit(result) || attacker == null || !attacker.isPlayer() || attacker.player() == null) {
            return;
        }
        AstPlayer player = attacker.player();
        Set<String> consumed = new HashSet<>();
        consumeReference(
            player,
            inventoryService.getItemReferenceInHand(player, EquipmentSlot.HAND),
            equipment -> equipment.getSlot() == ItemEquipmentSlot.WEAPON,
            WEAPON_HIT_CONSUME_CHANCE,
            consumed
        );
        consumeAccessories(player, ACCESSORY_HIT_CONSUME_CHANCE, consumed);
    }

    /**
     * 被ダメージ時に、防具と被ダメージ反応アクセサリーの耐久値を確率で減少させます。
     * 実ダメージまたはシールドダメージが発生していない場合は何もしません。
     *
     * @param victim 被ダメージ側エンティティ。プレイヤー以外の場合は処理しません。
     * @param result ダメージ計算結果
     */
    public void consumeOnDamageTaken(@NotNull AstEntity victim, @NotNull DamageResult result) {
        if (!isEffectiveHit(result) || !victim.isPlayer() || victim.player() == null) {
            return;
        }
        AstPlayer player = victim.player();
        Set<String> consumed = new HashSet<>();
        PlayerInventory inventory = player.getBukkit().getInventory();
        Predicate<ItemEquipment> armorPredicate = equipment -> switch (equipment.getSlot()) {
            case HEAD, CHEST, LEGS, FEET -> true;
            default -> false;
        };
        consumeStack(player, inventory.getHelmet(), armorPredicate, ARMOR_DAMAGE_TAKEN_CONSUME_CHANCE, consumed);
        consumeStack(player, inventory.getChestplate(), armorPredicate, ARMOR_DAMAGE_TAKEN_CONSUME_CHANCE, consumed);
        consumeStack(player, inventory.getLeggings(), armorPredicate, ARMOR_DAMAGE_TAKEN_CONSUME_CHANCE, consumed);
        consumeStack(player, inventory.getBoots(), armorPredicate, ARMOR_DAMAGE_TAKEN_CONSUME_CHANCE, consumed);
        consumeAccessories(player, ACCESSORY_DAMAGE_TAKEN_CONSUME_CHANCE, consumed);
    }

    /**
     * 採集オブジェクトの破壊完了時に、メインハンドの TOOL の耐久値を1回分減少させます。
     * 耐久値または装備インスタンスが未設定の場合は何もしません。
     *
     * @param player 採集を完了したプレイヤー
     */
    public void consumeOnGathering(@NotNull AstPlayer player) {
        consumeReference(
            player,
            inventoryService.getItemReferenceInHand(player, EquipmentSlot.HAND),
            equipment -> equipment.getSlot() == ItemEquipmentSlot.TOOL,
            0.50D,
            new HashSet<>()
        );
    }

    /**
     * 装備インスタンスが耐久値切れかを判定します。
     *
     * @param instance 判定対象の装備インスタンス
     * @return 最大耐久値を持ち、現在耐久値が 0 以下の場合は {@code true}
     */
    public boolean isBroken(@Nullable EquipmentInstance instance) {
        return instance != null && instance.getDurabilityMax() > 0 && instance.getDurabilityValue() <= 0;
    }

    /**
     * 装備中の防具およびアクセサリのうち、最大耐久値を下回っている装備名を返します。
     * <p>
     * Bukkit の防具スロットと仮想アクセサリスロットを走査し、同じ装備個体は一度だけ扱います。
     * 武器・補助装備・道具、最大耐久値を持たない装備は対象外です。
     *
     * @param player 判定対象プレイヤー
     * @return 破損がある装備の表示名一覧。該当しない場合は空のリスト
     */
    public @NotNull List<String> getDamagedArmorAndAccessoryDisplayNames(@NotNull AstPlayer player) {
        PlayerInventory inventory = player.getBukkit().getInventory();
        List<ItemStack> equippedItems = new ArrayList<>();
        equippedItems.add(inventory.getHelmet());
        equippedItems.add(inventory.getChestplate());
        equippedItems.add(inventory.getLeggings());
        equippedItems.add(inventory.getBoots());
        equippedItems.addAll(inventoryService.getEquippedAccessorySnapshotItems(player));

        Set<String> inspectedInstanceIds = new HashSet<>();
        List<String> damagedNames = new ArrayList<>();
        for (ItemStack itemStack : equippedItems) {
            ItemReference reference = itemReferenceResolver.resolveLoaded(itemStack);
            if (reference == null
                || !reference.hasEquipmentInstanceId()
                || ItemCategory.fromApiValue(reference.category()) != ItemCategory.EQUIPMENT
                || !inspectedInstanceIds.add(reference.equipmentInstanceId())) {
                continue;
            }
            ItemModel model = itemService.findLoadedById(reference.itemId());
            EquipmentInstance instance = itemService.findLoadedEquipmentInstanceById(reference.equipmentInstanceId());
            if (model == null
                || model.getEquipment() == null
                || !isArmorOrAccessory(model.getEquipment().getSlot())
                || !isDamaged(instance)) {
                continue;
            }
            damagedNames.add(ColorCodeUtil.toLegacyText(model.getName(), model.getId()));
        }
        return List.copyOf(damagedNames);
    }

    private void consumeAccessories(
        @NotNull AstPlayer player,
        double chance,
        @NotNull Set<String> consumed
    ) {
        for (ItemStack itemStack : inventoryService.getEquippedAccessorySnapshotItems(player)) {
            consumeStack(player, itemStack, equipment -> equipment.getSlot() == ItemEquipmentSlot.ACCESSORY, chance, consumed);
        }
    }

    private boolean isArmorOrAccessory(@Nullable ItemEquipmentSlot slot) {
        return slot == ItemEquipmentSlot.HEAD
            || slot == ItemEquipmentSlot.CHEST
            || slot == ItemEquipmentSlot.LEGS
            || slot == ItemEquipmentSlot.FEET
            || slot == ItemEquipmentSlot.ACCESSORY;
    }

    private boolean isDamaged(@Nullable EquipmentInstance instance) {
        return instance != null
            && instance.getDurabilityMax() > 0
            && instance.getDurabilityValue() < instance.getDurabilityMax();
    }

    private void consumeStack(
        @NotNull AstPlayer player,
        @Nullable ItemStack itemStack,
        @NotNull Predicate<ItemEquipment> equipmentPredicate,
        double chance,
        @NotNull Set<String> consumed
    ) {
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            return;
        }
        consumeReference(player, itemReferenceResolver.resolve(itemStack), equipmentPredicate, chance, consumed);
    }

    private void consumeReference(
        @NotNull AstPlayer player,
        @Nullable ItemReference reference,
        @NotNull Predicate<ItemEquipment> equipmentPredicate,
        double chance,
        @NotNull Set<String> consumed
    ) {
        if (reference == null
            || !reference.hasEquipmentInstanceId()
            || ItemCategory.fromApiValue(reference.category()) != ItemCategory.EQUIPMENT) {
            return;
        }
        if (!consumed.add(reference.equipmentInstanceId())) {
            return;
        }
        ItemModel model = itemReferenceResolver.resolveItemModel(reference);
        EquipmentInstance instance = itemReferenceResolver.resolveEquipmentInstance(reference);
        if (model == null || model.getEquipment() == null || instance == null) {
            return;
        }
        if (!equipmentPredicate.test(model.getEquipment())) {
            return;
        }
        consumeDurability(player, model, instance, chance);
    }

    private void consumeDurability(
        @NotNull AstPlayer player,
        @NotNull ItemModel model,
        @NotNull EquipmentInstance instance,
        double chance
    ) {
        if (instance.getDurabilityMax() <= 0 || instance.getDurabilityValue() <= 0) {
            return;
        }
        if (chance < 1.0D && randomValueSupplier.getAsDouble() >= chance) {
            return;
        }
        int consumeAmount = model.getEquipment() == null || model.getEquipment().getDurability() == null
            ? 1
            : Math.max(1, model.getEquipment().getDurability().getConsume());
        int nextValue = Math.max(0, instance.getDurabilityValue() - consumeAmount);
        if (nextValue == instance.getDurabilityValue()) {
            return;
        }
        EquipmentInstance updated = itemService.updateEquipmentDurability(
            instance.getEquipmentInstanceId(),
            nextValue,
            player.getAccount().getUuid().toString()
        );
        if (updated == null) {
            return;
        }
        inventoryService.refreshEquipmentInstanceDisplay(player, updated);
        int updatedValue = updated.getDurabilityValue();
        if (updatedValue <= 0) {
            notifyEquipmentBroken(player, model);
            if (statusService != null) {
                statusService.refreshStatus(player);
            }
        } else {
            notifyLowDurabilityWarnings(
                player,
                model,
                instance.getDurabilityValue(),
                updatedValue,
                updated.getDurabilityMax()
            );
        }
    }

    /**
     * 武器の耐久値が低耐久警告の閾値を跨いだ場合に、閾値ごとの警告を送信します。
     * 1回の消費で複数の閾値を跨いだ場合は、5%から1%の順に該当する警告を送信します。
     *
     * @param player 警告の送信先プレイヤー
     * @param model 消費対象のアイテムマスタ
     * @param previousValue 消費前の耐久値
     * @param updatedValue 消費後の耐久値
     * @param durabilityMax 最大耐久値
     */
    private void notifyLowDurabilityWarnings(
        @NotNull AstPlayer player,
        @NotNull ItemModel model,
        int previousValue,
        int updatedValue,
        int durabilityMax
    ) {
        ItemEquipment equipment = model.getEquipment();
        if (equipment == null
            || equipment.getSlot() != ItemEquipmentSlot.WEAPON
            || durabilityMax <= 0
            || updatedValue <= 0
            || previousValue <= updatedValue) {
            return;
        }

        String displayName = ColorCodeUtil.toLegacyText(model.getName(), model.getId());
        for (int percent = LOW_DURABILITY_WARNING_MAX_PERCENT;
             percent >= LOW_DURABILITY_WARNING_MIN_PERCENT;
             percent--) {
            double thresholdValue = (double) durabilityMax * percent / 100.0D;
            if ((double) previousValue > thresholdValue && (double) updatedValue <= thresholdValue) {
                PlayerMessageService.getInstance().send(
                    player,
                    PlayerMsgId.P_5282,
                    displayName,
                    percent
                );
            }
        }
    }

    private void notifyEquipmentBroken(@NotNull AstPlayer player, @NotNull ItemModel model) {
        String displayName = ColorCodeUtil.toLegacyText(model.getName(), model.getId());
        PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5279, displayName);
        player.getBukkit().playSound(
            player.getBukkit().getLocation(),
            Sound.ENTITY_ITEM_BREAK,
            SoundCategory.PLAYERS,
            0.9F,
            0.75F
        );
    }

    private boolean isEffectiveHit(@NotNull DamageResult result) {
        return result.finalDamage() > 0.0D || result.shieldDamage() > 0.0D;
    }
}
