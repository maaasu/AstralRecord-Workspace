package io.github.maaasu.astralRecord.feature.status.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.buff.model.ActiveBuff;
import io.github.maaasu.astralRecord.feature.buff.service.BuffService;
import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.inventory.model.AccessorySlotType;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentEnchant;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentStatRoll;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnhanceStatIncrease;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentStat;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentStatType;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemReference;
import io.github.maaasu.astralRecord.feature.item.model.SetEffect;
import io.github.maaasu.astralRecord.feature.item.model.SetEffectPiece;
import io.github.maaasu.astralRecord.feature.item.model.SetEffectStat;
import io.github.maaasu.astralRecord.feature.item.service.ItemReferenceResolver;
import io.github.maaasu.astralRecord.feature.item.service.EquipmentRequirementService;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import io.github.maaasu.astralRecord.feature.skill.service.PassiveSkillService;
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService;
import io.github.maaasu.astralRecord.feature.status.model.StatusDefaults;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.model.StatusValue;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * ステータス機能のビジネスロジックを担うサービスクラスです。
 * <p>
 * 現時点ではプレイヤーの {@code account mode} とレベルから簡易的なステータスを計算します。
 * 将来的にレベル・装備・バフ補正を追加する際も、このサービスへ集約する想定です。
 */
public class StatusService {

    private static final double VANILLA_PLAYER_MOVEMENT_SPEED = 0.1D;
    private static final double INTELLIGENCE_MAX_MANA_PER_POINT = 1.0D;
    private static final double INTELLIGENCE_MP_REGEN_PER_POINT = 0.1D;
    private static final double VITALITY_MAX_HEALTH_PER_POINT = 2.0D;
    private static final double VITALITY_DEFENSE_PER_POINT = 1.0D;
    private static final double VITALITY_MAGIC_DEFENSE_PER_POINT = 1.0D;
    private static final double VITALITY_HP_REGEN_PER_POINT = 0.1D;
    private static final double AGILITY_ATTACK_SPEED_PER_POINT = 1.0D;
    private static final double AGILITY_MOVEMENT_SPEED_PER_POINT = 1.0D;
    private static final double AGILITY_EVASION_PER_POINT = 0.1D;
    private static final double LUCK_CRITICAL_RATE_PER_POINT = 0.1D;

    private final BuffService buffService;
    private final ItemService itemService;
    private final InventoryService inventoryService;
    private final ItemReferenceResolver itemReferenceResolver;
    private SkillTreeService skillTreeService;
    private PassiveSkillService passiveSkillService;
    private PlayerClassService playerClassService;
    private ConditionService conditionService;

    public StatusService() {
        this(null, null);
    }

    public StatusService(@Nullable ItemService itemService) {
        this(itemService, null);
    }

    public StatusService(
        @Nullable ItemService itemService,
        @Nullable InventoryService inventoryService
    ) {
        this.buffService = new BuffService();
        this.itemService = itemService;
        this.inventoryService = inventoryService;
        this.itemReferenceResolver = itemService == null ? null : new ItemReferenceResolver(itemService);
    }

    public void setSkillTreeService(@Nullable SkillTreeService skillTreeService) {
        this.skillTreeService = skillTreeService;
    }

    public void setPassiveSkillService(@Nullable PassiveSkillService passiveSkillService) {
        this.passiveSkillService = passiveSkillService;
    }

    public void setPlayerClassService(@Nullable PlayerClassService playerClassService) {
        this.playerClassService = playerClassService;
    }

    public void setConditionService(@Nullable ConditionService conditionService) {
        this.conditionService = conditionService;
    }

    /**
     * プレイヤーの現在ステータスを取得します。
     * 未計算の場合は初回計算を行い、その結果を返します。
     *
     * @param player 対象プレイヤー
     * @return 現在のステータススナップショット
     */
    public @NotNull StatusSnapshot getStatus(@NotNull AstPlayer player) {
        if (player.getStatusSnapshot().getValues().isEmpty() || buffService.purgeExpired(player) > 0) {
            return refreshStatus(player);
        }
        return player.getStatusSnapshot();
    }

    /**
     * プレイヤーのステータスを再計算し、{@link AstPlayer} に反映します。
     *
     * @param player 対象プレイヤー
     * @return 再計算後のステータススナップショット
     */
    public @NotNull StatusSnapshot refreshStatus(@NotNull AstPlayer player) {
        buffService.purgeExpired(player);

        StatusSnapshot previous = player.getStatusSnapshot();
        StatusSnapshot refreshed = createSnapshot(player);

        StatusSnapshot merged;
        if (previous.getValues().isEmpty()) {
            // 初回は全快状態で開始
            merged = restoreAllInternal(refreshed);
        } else {
            // 再計算時は現在値を維持しつつ、新しい最大値へクランプ
            merged = refreshed.withCurrentValues(
                previous.getCurrentHp(),
                previous.getCurrentMp(),
                previous.getCurrentEnergy(),
                previous.getCurrentShield()
            );
        }

        player.setStatusSnapshot(merged);
        if (inventoryService != null) {
            inventoryService.applyBagSlotCapacity(player, merged.getMaxValue(StatusType.INVENTORY_SLOTS));
        }
        applyMovementSpeed(player, merged);
        return merged;
    }

    private void applyMovementSpeed(
        @NotNull AstPlayer player,
        @NotNull StatusSnapshot snapshot
    ) {
        AttributeInstance attribute = player.getBukkit().getAttribute(Attribute.MOVEMENT_SPEED);
        if (attribute == null) {
            return;
        }

        double conditionMultiplier = conditionService == null
            ? 1.0D
            : conditionService.movementSpeedMultiplier(AstEntity.player(player));
        double speedPercent = Math.max(0.0D, snapshot.getMaxValue(StatusType.MOVEMENT_SPEED))
            * conditionMultiplier;
        attribute.setBaseValue(VANILLA_PLAYER_MOVEMENT_SPEED * speedPercent / 100.0D);
    }

    /**
     * バフを付与し、ステータスを再計算します。
     *
     * @param player   対象プレイヤー
     * @param buffId   付与するバフID
     * @return 再計算後のステータススナップショット
     */
    public @NotNull StatusSnapshot applyBuff(@NotNull AstPlayer player, @NotNull String buffId) {
        if (!buffService.apply(player, buffId)) {
            return getStatus(player);
        }
        return refreshStatus(player);
    }

    /**
     * 指定ステータスを固定値で上昇させる一時バフを付与し、ステータスを再計算します。
     *
     * @param player          対象プレイヤー
     * @param statusType      上昇させるステータス種別
     * @param value           上昇値（正の有限値）
     * @param durationSeconds 持続秒数
     * @return 付与したアクティブバフ
     * @throws IllegalArgumentException 値または持続秒数が有効範囲外の場合
     */
    public @NotNull ActiveBuff applyTemporaryFlatBuff(
        @NotNull AstPlayer player,
        @NotNull StatusType statusType,
        double value,
        long durationSeconds
    ) {
        ActiveBuff activeBuff = buffService.applyTemporaryFlat(player, statusType, value, durationSeconds);
        refreshStatus(player);
        return activeBuff;
    }

    /**
     * バフを解除し、ステータスを再計算します。
     *
     * @param player   対象プレイヤー
     * @param buffId   解除するバフID
     * @return 再計算後のステータススナップショット
     */
    public @NotNull StatusSnapshot removeBuff(@NotNull AstPlayer player, @NotNull String buffId) {
        buffService.remove(player, buffId);
        return refreshStatus(player);
    }

    /**
     * 現在有効なバフ一覧を返します。
     *
     * @param player 対象プレイヤー
     * @return 有効バフ一覧
     */
    public @NotNull List<ActiveBuff> getActiveBuffs(@NotNull AstPlayer player) {
        return buffService.getActiveBuffs(player);
    }

    /**
     * 現在HPを減少させます。
     *
     * @param player 対象プレイヤー
     * @param amount 減少量（0以下は無視）
     * @return 更新後のステータススナップショット
     */
    public @NotNull StatusSnapshot consumeHp(@NotNull AstPlayer player, double amount) {
        StatusSnapshot snapshot = getStatus(player);
        if (amount <= 0.0D) {
            return snapshot;
        }

        StatusSnapshot updated = snapshot.withCurrentValues(snapshot.getCurrentHp() - amount, snapshot.getCurrentMp());
        player.setStatusSnapshot(updated);
        return updated;
    }

    /**
     * 現在シールド値を減少させます。
     *
     * @param player 対象プレイヤー
     * @param amount 減少量
     * @return 更新後のステータススナップショット
     */
    public @NotNull StatusSnapshot consumeShield(@NotNull AstPlayer player, double amount) {
        StatusSnapshot snapshot = getStatus(player);
        if (amount <= 0.0D) {
            return snapshot;
        }

        StatusSnapshot updated = snapshot.withCurrentShield(snapshot.getCurrentShield() - amount);
        player.setStatusSnapshot(updated);
        return updated;
    }

    /**
     * 現在MPを減少させます。
     *
     * @param player 対象プレイヤー
     * @param amount 減少量（0以下は無視）
     * @return 更新後のステータススナップショット
     */
    public @NotNull StatusSnapshot consumeMp(@NotNull AstPlayer player, double amount) {
        StatusSnapshot snapshot = getStatus(player);
        if (amount <= 0.0D) {
            return snapshot;
        }

        StatusSnapshot updated = snapshot.withCurrentValues(snapshot.getCurrentHp(), snapshot.getCurrentMp() - amount);
        player.setStatusSnapshot(updated);
        return updated;
    }

    /**
     * 現在HPを回復します。
     *
     * @param player 対象プレイヤー
     * @param amount 回復量（0以下は無視）
     * @return 更新後のステータススナップショット
     */
    public @NotNull StatusSnapshot recoverHp(@NotNull AstPlayer player, double amount) {
        StatusSnapshot snapshot = getStatus(player);
        if (amount <= 0.0D || isHealingBlocked(player)) {
            return snapshot;
        }

        double healingIncrease = Math.max(0.0D, snapshot.getMaxValue(StatusType.HEALING_INCREASE));
        StatusSnapshot updated = snapshot.withCurrentValues(
            snapshot.getCurrentHp() + amount * (1.0D + healingIncrease / 100.0D),
            snapshot.getCurrentMp()
        );
        player.setStatusSnapshot(updated);
        return updated;
    }

    /**
     * 現在シールド値を回復します。
     *
     * @param player 対象プレイヤー
     * @param amount 回復量
     * @return 更新後のステータススナップショット
     */
    public @NotNull StatusSnapshot recoverShield(@NotNull AstPlayer player, double amount) {
        StatusSnapshot snapshot = getStatus(player);
        if (amount <= 0.0D || snapshot.getMaxValue(StatusType.MAX_SHIELD) <= 0.0D || isHealingBlocked(player)) {
            return snapshot;
        }

        StatusSnapshot updated = snapshot.withCurrentShield(snapshot.getCurrentShield() + amount);
        player.setStatusSnapshot(updated);
        return updated;
    }

    /**
     * 現在MPを回復します。
     *
     * @param player 対象プレイヤー
     * @param amount 回復量（0以下は無視）
     * @return 更新後のステータススナップショット
     */
    public @NotNull StatusSnapshot recoverMp(@NotNull AstPlayer player, double amount) {
        StatusSnapshot snapshot = getStatus(player);
        if (amount <= 0.0D || isHealingBlocked(player)) {
            return snapshot;
        }

        StatusSnapshot updated = snapshot.withCurrentValues(snapshot.getCurrentHp(), snapshot.getCurrentMp() + amount);
        player.setStatusSnapshot(updated);
        return updated;
    }

    /**
     * 現在エネルギーを減少させます。
     *
     * @param player 対象プレイヤー
     * @param amount 減少量（0以下は無視）
     * @return 更新後のステータススナップショット
     */
    public @NotNull StatusSnapshot consumeEnergy(@NotNull AstPlayer player, double amount) {
        StatusSnapshot snapshot = getStatus(player);
        if (amount <= 0.0D) {
            return snapshot;
        }

        StatusSnapshot updated = snapshot.withCurrentValues(
            snapshot.getCurrentHp(), snapshot.getCurrentMp(), snapshot.getCurrentEnergy() - amount
        );
        player.setStatusSnapshot(updated);
        return updated;
    }

    /**
     * 現在エネルギーを回復します。
     *
     * @param player 対象プレイヤー
     * @param amount 回復量（0以下は無視）
     * @return 更新後のステータススナップショット
     */
    public @NotNull StatusSnapshot recoverEnergy(@NotNull AstPlayer player, double amount) {
        StatusSnapshot snapshot = getStatus(player);
        if (amount <= 0.0D || isHealingBlocked(player)) {
            return snapshot;
        }

        StatusSnapshot updated = snapshot.withCurrentValues(
            snapshot.getCurrentHp(), snapshot.getCurrentMp(), snapshot.getCurrentEnergy() + amount
        );
        player.setStatusSnapshot(updated);
        return updated;
    }

    /**
     * 現在HP/MP/エネルギーを最大値まで回復します。
     *
     * @param player 対象プレイヤー
     * @return 更新後のステータススナップショット
     */
    public @NotNull StatusSnapshot restoreAll(@NotNull AstPlayer player) {
        StatusSnapshot snapshot = restoreAllInternal(getStatus(player));
        player.setStatusSnapshot(snapshot);
        return snapshot;
    }

    /**
     * 現在装備から有効化されているセット効果一覧を返します。
     *
     * @param player プレイヤー
     * @return 有効セット効果一覧
     */
    public @NotNull List<ActiveSetEffect> getActiveSetEffects(@NotNull AstPlayer player) {
        if (itemService == null) {
            return List.of();
        }
        Map<String, Integer> setCounts = collectEquippedSetCounts(player);
        if (setCounts.isEmpty()) {
            return List.of();
        }

        List<ActiveSetEffect> effects = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : setCounts.entrySet()) {
            SetEffect setEffect = itemService.findSetEffectById(entry.getKey());
            if (setEffect == null) {
                continue;
            }
            int equippedCount = entry.getValue();
            Set<Integer> activeCounts = new TreeSet<>();
            for (SetEffectPiece piece : setEffect.getPieces()) {
                if (piece.getCount() > 0 && equippedCount >= piece.getCount()) {
                    activeCounts.add(piece.getCount());
                }
            }
            if (activeCounts.isEmpty()) {
                continue;
            }
            effects.add(new ActiveSetEffect(setEffect.getId(), setEffect.getName(), equippedCount, List.copyOf(activeCounts)));
        }
        return effects;
    }

    private @NotNull StatusSnapshot createSnapshot(@NotNull AstPlayer player) {
        Map<StatusType, StatusValue> values = new EnumMap<>(StatusType.class);
        EquipmentBonus equipmentBonus = itemService == null ? EquipmentBonus.empty() : collectEquipmentBonus(player);

        for (StatusType type : StatusType.values()) {
            double baseValue = getBaseValue(type);
            StatusRange bonusRange = getBonusRange(player, type, baseValue, equipmentBonus);
            values.put(type, new StatusValue(
                baseValue,
                baseValue,
                bonusRange.min(),
                bonusRange.max()
            ));
        }
        applyPrimaryAttributeEffects(values);

        return new StatusSnapshot(values, 0.0D, 0.0D, 0.0D, 0.0D, System.currentTimeMillis(), LocalDateTime.now());
    }

    /**
     * 基本能力値から派生ステータスへの補正を反映します。
     * <p>
     * 基本能力値自体に装備・バフなどで範囲がある場合は、派生ステータスにも対応する範囲を維持します。
     * 範囲を保持しない最大リソースには、基本能力値の範囲平均を適用します。
     *
     * @param values 基本ステータスまで計算済みのステータス値
     */
    private void applyPrimaryAttributeEffects(@NotNull Map<StatusType, StatusValue> values) {
        addPrimaryDerivedBonus(values, StatusType.INTELLIGENCE, StatusType.MAX_MANA, INTELLIGENCE_MAX_MANA_PER_POINT);
        addPrimaryDerivedBonus(values, StatusType.INTELLIGENCE, StatusType.MP_REGEN, INTELLIGENCE_MP_REGEN_PER_POINT);

        addPrimaryDerivedBonus(values, StatusType.VITALITY, StatusType.MAX_HEALTH, VITALITY_MAX_HEALTH_PER_POINT);
        addPrimaryDerivedBonus(values, StatusType.VITALITY, StatusType.DEFENSE, VITALITY_DEFENSE_PER_POINT);
        addPrimaryDerivedBonus(values, StatusType.VITALITY, StatusType.MAGIC_DEFENSE, VITALITY_MAGIC_DEFENSE_PER_POINT);
        addPrimaryDerivedBonus(values, StatusType.VITALITY, StatusType.HP_REGEN, VITALITY_HP_REGEN_PER_POINT);

        addPrimaryDerivedBonus(values, StatusType.AGILITY, StatusType.ATTACK_SPEED, AGILITY_ATTACK_SPEED_PER_POINT);
        addPrimaryDerivedBonus(values, StatusType.AGILITY, StatusType.MOVEMENT_SPEED, AGILITY_MOVEMENT_SPEED_PER_POINT);
        addPrimaryDerivedBonus(values, StatusType.AGILITY, StatusType.EVASION, AGILITY_EVASION_PER_POINT);

        addPrimaryDerivedBonus(values, StatusType.LUCK, StatusType.CRITICAL_RATE, LUCK_CRITICAL_RATE_PER_POINT);
    }

    /**
     * 基本能力値の最終値を指定した派生ステータスへ加算します。
     *
     * @param values ステータス値一覧
     * @param primaryType 基本能力値の種別
     * @param targetType 派生先ステータスの種別
     * @param multiplier 基本能力値1あたりの派生量
     */
    private void addPrimaryDerivedBonus(
        @NotNull Map<StatusType, StatusValue> values,
        @NotNull StatusType primaryType,
        @NotNull StatusType targetType,
        double multiplier
    ) {
        StatusValue primary = values.get(primaryType);
        StatusValue target = values.get(targetType);
        if (primary == null || target == null || multiplier == 0.0D) {
            return;
        }

        double primaryMin = primary.getMinValue();
        double primaryMax = primary.getMaxValue();
        if (!targetType.getSupportsRange()) {
            double average = (primaryMin + primaryMax) / 2.0D * multiplier;
            values.put(targetType, new StatusValue(
                target.getBaseMinValue(),
                target.getBaseMaxValue(),
                target.getBonusMinValue() + average,
                target.getBonusMaxValue() + average
            ));
            return;
        }

        values.put(targetType, new StatusValue(
            target.getBaseMinValue(),
            target.getBaseMaxValue(),
            target.getBonusMinValue() + primaryMin * multiplier,
            target.getBonusMaxValue() + primaryMax * multiplier
        ));
    }

    private @NotNull StatusSnapshot restoreAllInternal(@NotNull StatusSnapshot snapshot) {
        double maxHp = snapshot.getMaxValue(StatusType.MAX_HEALTH);
        double maxMp = snapshot.getMaxValue(StatusType.MAX_MANA);
        double maxEnergy = snapshot.getMaxValue(StatusType.MAX_ENERGY);
        double maxShield = snapshot.getMaxValue(StatusType.MAX_SHIELD);
        return snapshot.withCurrentValues(maxHp, maxMp, maxEnergy, maxShield);
    }

    private double getBaseValue(@NotNull StatusType type) {
        return switch (type) {
            // リソース系
            case MAX_HEALTH -> 20.0D;
            case MAX_MANA -> 10.0D;
            case MAX_ENERGY -> 100.0D;
            case MAX_SHIELD -> 0.0D;
            // 基本能力値
            case STRENGTH -> 5.0D;
            case DEXTERITY -> 5.0D;
            case INTELLIGENCE -> 5.0D;
            case VITALITY -> 5.0D;
            case AGILITY -> 5.0D;
            case LUCK -> 5.0D;
            // 攻撃系
            case ATTACK -> 8.0D;
            case MELEE_ATTACK -> 0.0D;     // ATTACK × STR から派生（将来の戦闘システムで算出）
            case RANGED_ATTACK -> 0.0D;    // ATTACK × DEX から派生
            case MAGIC_ATTACK -> 0.0D;     // ATTACK × INT から派生
            case CRITICAL_RATE -> 5.0D;
            case CRITICAL_DAMAGE -> 150.0D;
            case SUPER_CRITICAL_RATE -> 0.0D;
            case SUPER_CRITICAL_DAMAGE -> 30.0D;
            case FINAL_DAMAGE_MULTIPLIER -> 130.0D;
            case ACCURACY -> 95.0D;
            case ATTACK_SPEED -> 100.0D;
            case SHIELD_BREAK -> 0.0D;
            // 属性系
            case FIRE_RESISTANCE_CAP, ICE_RESISTANCE_CAP, LIGHTNING_RESISTANCE_CAP -> 75.0D;
            // 防御系
            case DEFENSE -> 5.0D;
            case MAGIC_DEFENSE -> 3.0D;
            case EVASION -> 3.0D;
            case KNOCKBACK_RESISTANCE -> 0.0D;
            // 回復・ユーティリティ系
            case HP_REGEN -> 1.0D;
            case MP_REGEN -> 0.5D;
            case ENERGY_REGEN -> 5.0D;
            case MOVEMENT_SPEED -> 100.0D;
            case COOLDOWN_REDUCTION -> 0.0D;
            case SHIELD_RECHARGE_REDUCTION -> 0.0D;
            case SHIELD_RECHARGE_RATE -> 0.0D;
            // 採集速度は装備値をそのまま1回分の破壊力として扱う。装備なしは GatheringService 側で1に補正する。
            case MINING_SPEED -> 0.0D;
            case INVENTORY_SLOTS -> StatusDefaults.INVENTORY_SLOTS;
            case QUEST_LIMIT -> 3.0D;
            default -> 0.0D;
        };
    }

    private @NotNull StatusRange getBonusRange(
        @NotNull AstPlayer player,
        @NotNull StatusType type,
        double baseValue,
        @NotNull EquipmentBonus equipmentBonus
    ) {
        if (!type.getSupportsRange()) {
            double value = getBonusValue(player, type, baseValue, equipmentBonus, RangeEndpoint.AVERAGE);
            return new StatusRange(value, value);
        }
        double lower = getBonusValue(player, type, baseValue, equipmentBonus, RangeEndpoint.MIN);
        double upper = getBonusValue(player, type, baseValue, equipmentBonus, RangeEndpoint.MAX);
        return new StatusRange(Math.min(lower, upper), Math.max(lower, upper));
    }

    private double getBonusValue(
        @NotNull AstPlayer player,
        @NotNull StatusType type,
        double baseValue,
        @NotNull EquipmentBonus equipmentBonus,
        @NotNull RangeEndpoint endpoint
    ) {
        double nonBuffBonus = getAccountModeBonus(player.getAccount().getMode(), type);
        nonBuffBonus += getLevelBonus(player, type);
        nonBuffBonus += getClassShieldBonus(player, type);
        nonBuffBonus += getEquipmentBonus(equipmentBonus, type, baseValue + nonBuffBonus, endpoint);
        nonBuffBonus += getSkillTreeBonus(player, type, baseValue + nonBuffBonus);
        nonBuffBonus += getPassiveSkillBonus(player, type, baseValue + nonBuffBonus);

        double preBuffTotal = baseValue + nonBuffBonus;
        double buffBonus = buffService.getTotalBonus(player, type, preBuffTotal);
        return nonBuffBonus + buffBonus;
    }

    /**
     * プレイヤーレベルに応じたステータス加算値を返します。
     * BAG 容量はレベル5ごとに1スロット増加します。
     */
    private double getLevelBonus(@NotNull AstPlayer player, @NotNull StatusType type) {
        if (type != StatusType.INVENTORY_SLOTS) {
            return 0.0D;
        }
        return Math.max(0, player.getAccount().getLevel()) / 5;
    }

    private double getSkillTreeBonus(@NotNull AstPlayer player, @NotNull StatusType type, double baseValue) {
        return skillTreeService == null ? 0.0D : skillTreeService.getStatusBonus(player, type, baseValue);
    }

    private double getPassiveSkillBonus(@NotNull AstPlayer player, @NotNull StatusType type, double baseValue) {
        return passiveSkillService == null ? 0.0D : passiveSkillService.getStatusBonus(player, type, baseValue);
    }

    private double getClassShieldBonus(@NotNull AstPlayer player, @NotNull StatusType type) {
        if (playerClassService == null || !isShieldStatus(type)) {
            return 0.0D;
        }
        return playerClassService.getStatusBonus(player, type);
    }

    private boolean isShieldStatus(@NotNull StatusType type) {
        return type == StatusType.MAX_SHIELD
            || type == StatusType.SHIELD_BREAK
            || type == StatusType.SHIELD_RECHARGE_REDUCTION
            || type == StatusType.SHIELD_RECHARGE_RATE;
    }

    private double getEquipmentBonus(
        @NotNull EquipmentBonus bonus,
        @NotNull StatusType type,
        double baseValue,
        @NotNull RangeEndpoint endpoint
    ) {
        double flat = endpoint.select(bonus.flatValues.get(type));
        double scalar = endpoint.select(bonus.scalarValues.get(type));
        return flat + (baseValue * scalar);
    }

    private @NotNull EquipmentBonus collectEquipmentBonus(@NotNull AstPlayer player) {
        EquipmentBonus bonus = new EquipmentBonus();
        Map<String, Integer> setCounts = new HashMap<>();
        for (ItemReference reference : collectEquippedReferences(player)) {
            applyEquipmentItemBonus(player, reference, bonus, setCounts);
        }
        applySetEffectBonus(setCounts, bonus);
        return bonus;
    }

    private @NotNull Map<String, Integer> collectEquippedSetCounts(@NotNull AstPlayer player) {
        Map<String, Integer> setCounts = new HashMap<>();
        if (itemService == null) {
            return setCounts;
        }
        for (ItemReference reference : collectEquippedReferences(player)) {
            countSetId(player, reference, setCounts);
        }
        return setCounts;
    }

    private @NotNull List<ItemReference> collectEquippedReferences(@NotNull AstPlayer player) {
        if (inventoryService != null) {
            return inventoryService.getEquippedItemReferences(player);
        }

        List<ItemReference> references = new ArrayList<>();
        if (itemReferenceResolver == null) {
            return references;
        }
        for (ItemStack itemStack : collectLegacyEquippedItems(player)) {
            ItemReference reference = itemReferenceResolver.resolve(itemStack);
            if (reference != null) {
                references.add(reference);
            }
        }
        return references;
    }

    private void applyEquipmentItemBonus(
        @NotNull AstPlayer player,
        @Nullable ItemReference reference,
        @NotNull EquipmentBonus bonus,
        @NotNull Map<String, Integer> setCounts
    ) {
        if (reference == null || itemReferenceResolver == null || !reference.hasEquipmentInstanceId()) {
            return;
        }

        EquipmentInstance instance = itemReferenceResolver.resolveEquipmentInstance(reference);
        if (instance == null) {
            return;
        }
        if (instance.getDurabilityMax() > 0 && instance.getDurabilityValue() <= 0) {
            return;
        }
        ItemModel model = resolveItemModel(instance.getItemId());
        if (model == null || model.getEquipment() == null) {
            return;
        }

        ItemEquipment equipment = model.getEquipment();
        if (!EquipmentRequirementService.check(player, equipment).allowed()) {
            return;
        }
        String setId = equipment.getSetId();
        if (setId != null && !setId.isBlank()) {
            setCounts.merge(setId.trim(), 1, Integer::sum);
        }
        Map<String, ItemEquipmentStatType> statTypes = new HashMap<>();
        for (ItemEquipmentStat stat : equipment.getStats()) {
            statTypes.put(normalizeStatusKey(stat.getStatus()), stat.getType());
        }

        EquipmentItemBonus itemBonus = new EquipmentItemBonus();

        for (EquipmentStatRoll roll : instance.getStatRolls()) {
            StatusType statusType = resolveStatusTypeOrNull(roll.getStatus());
            if (statusType == null) {
                continue;
            }
            ItemEquipmentStatType statType = statTypes.getOrDefault(
                normalizeStatusKey(roll.getStatus()),
                ItemEquipmentStatType.FLAT
            );
            addItemBonus(
                itemBonus,
                statusType,
                statType,
                parseStatDouble(roll.getMin()),
                parseStatDouble(roll.getMax())
            );
        }

        for (Map.Entry<String, EquipmentStatAmount> entry : calculateEnhanceStats(equipment, instance.getEnhanceLevel()).entrySet()) {
            EquipmentStatAmount amount = entry.getValue();
            addItemBonus(itemBonus, amount.statusType, amount.type, amount.min, amount.max);
        }

        for (EquipmentEnchant enchant : instance.getEnchants()) {
            StatusType statusType = resolveStatusTypeOrNull(enchant.getStatus());
            if (statusType == null) {
                continue;
            }
            addItemBonus(
                itemBonus,
                statusType,
                ItemEquipmentStatType.fromApiValue(enchant.getType()),
                enchant.getValue(),
                enchant.getValue()
            );
        }

        for (StatusType statusType : StatusType.values()) {
            StatusRange contribution = itemBonus.resolve(statusType);
            if (contribution != null) {
                addBonus(bonus, statusType, ItemEquipmentStatType.FLAT, contribution.min(), contribution.max());
            }
        }
    }

    private void countSetId(
        @NotNull AstPlayer player,
        @Nullable ItemReference reference,
        @NotNull Map<String, Integer> setCounts
    ) {
        if (reference == null || itemService == null || itemReferenceResolver == null || !reference.hasEquipmentInstanceId()) {
            return;
        }
        EquipmentInstance instance = itemReferenceResolver.resolveEquipmentInstance(reference);
        if (instance == null) {
            return;
        }
        ItemModel model = resolveItemModel(instance.getItemId());
        if (model == null || model.getEquipment() == null) {
            return;
        }
        ItemEquipment equipment = model.getEquipment();
        if (!EquipmentRequirementService.check(player, equipment).allowed()) {
            return;
        }
        String setId = equipment.getSetId();
        if (setId == null || setId.isBlank()) {
            return;
        }
        setCounts.merge(setId.trim(), 1, Integer::sum);
    }

    private @NotNull List<ItemStack> collectLegacyEquippedItems(@NotNull AstPlayer player) {
        var inventory = player.getBukkit().getInventory();
        List<ItemStack> items = new ArrayList<>();
        items.add(inventory.getHelmet());
        items.add(inventory.getChestplate());
        items.add(inventory.getLeggings());
        items.add(inventory.getBoots());
        items.add(inventory.getItemInOffHand());
        items.add(inventory.getItemInMainHand());
        if (inventoryService != null) {
            for (int slotIndex = AccessorySlotType.AMULET.getSlotIndex();
                 slotIndex <= AccessorySlotType.RELIC_2.getSlotIndex();
                 slotIndex++) {
                items.add(inventoryService.getAccessorySnapshotItem(player, slotIndex));
            }
        }
        return items;
    }

    private void applySetEffectBonus(@NotNull Map<String, Integer> setCounts, @NotNull EquipmentBonus bonus) {
        if (itemService == null || setCounts.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Integer> entry : setCounts.entrySet()) {
            SetEffect setEffect = itemService.findSetEffectById(entry.getKey());
            if (setEffect == null) {
                continue;
            }
            int equippedCount = entry.getValue();
            for (SetEffectPiece piece : setEffect.getPieces()) {
                if (piece.getCount() <= 0 || equippedCount < piece.getCount()) {
                    continue;
                }
                for (SetEffectStat stat : piece.getStats()) {
                    StatusType statusType = resolveStatusTypeOrNull(stat.getStatus());
                    if (statusType == null) {
                        continue;
                    }
                    double value = parseStatDouble(stat.getValue());
                    addBonus(bonus, statusType, stat.getType(), value, value);
                }
            }
        }
    }

    private @Nullable ItemModel resolveItemModel(@NotNull String itemId) {
        ItemModel loaded = itemService.findLoadedById(itemId);
        if (loaded != null) {
            return loaded;
        }
        return itemService.loadItem(itemId);
    }

    private void addBonus(
        @NotNull EquipmentBonus bonus,
        @NotNull StatusType statusType,
        @NotNull ItemEquipmentStatType statType,
        double min,
        double max
    ) {
        Map<StatusType, StatusRangeAccumulator> target = statType == ItemEquipmentStatType.SCALAR
            ? bonus.scalarValues
            : bonus.flatValues;
        target.computeIfAbsent(statusType, ignored -> new StatusRangeAccumulator()).add(min, max);
    }

    private void addItemBonus(
        @NotNull EquipmentItemBonus bonus,
        @NotNull StatusType statusType,
        @NotNull ItemEquipmentStatType statType,
        double min,
        double max
    ) {
        Map<StatusType, StatusRangeAccumulator> target = statType == ItemEquipmentStatType.SCALAR
            ? bonus.scalarValues
            : bonus.flatValues;
        StatusRangeAccumulator accumulator = target.computeIfAbsent(
            statusType,
            ignored -> new StatusRangeAccumulator()
        );
        if (statType == ItemEquipmentStatType.SCALAR) {
            accumulator.set(min, max);
        } else {
            accumulator.add(min, max);
        }
    }

    private @NotNull Map<String, EquipmentStatAmount> calculateEnhanceStats(
        @NotNull ItemEquipment equipment,
        int enhanceLevel
    ) {
        Map<String, EquipmentStatAmount> amounts = new LinkedHashMap<>();
        if (equipment.getEnhance() == null || enhanceLevel <= 0) {
            return amounts;
        }
        for (var level : equipment.getEnhance().getLevels()) {
            if (level.getLevel() > enhanceLevel) {
                continue;
            }
            for (ItemEquipmentEnhanceStatIncrease increase : level.getStatIncrease()) {
                StatusType statusType = resolveStatusTypeOrNull(increase.getStatus());
                if (statusType == null) {
                    continue;
                }
                String key = statusType.name() + "#" + increase.getType().name();
                EquipmentStatAmount current = amounts.computeIfAbsent(
                    key,
                    ignored -> new EquipmentStatAmount(statusType, increase.getType())
                );
                current.add(increase.getMin(), increase.getMax());
            }
        }
        return amounts;
    }

    private double parseStatDouble(@NotNull String value) {
        try {
            return Double.parseDouble(value.trim().split("~")[0].trim());
        } catch (NumberFormatException ignored) {
            return 0.0D;
        }
    }

    private @NotNull String normalizeStatusKey(@NotNull String status) {
        return status.trim().replace(' ', '_').replace('-', '_').toUpperCase(Locale.ROOT);
    }

    private @Nullable StatusType resolveStatusTypeOrNull(@NotNull String rawStatus) {
        String normalized = normalizeStatusKey(rawStatus);
        if (normalized.isEmpty()) {
            return null;
        }
        try {
            return StatusType.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static final class EquipmentBonus {
        private final Map<StatusType, StatusRangeAccumulator> flatValues = new EnumMap<>(StatusType.class);
        private final Map<StatusType, StatusRangeAccumulator> scalarValues = new EnumMap<>(StatusType.class);

        private static @NotNull EquipmentBonus empty() {
            return new EquipmentBonus();
        }
    }

    private static final class EquipmentItemBonus {
        private final Map<StatusType, StatusRangeAccumulator> flatValues = new EnumMap<>(StatusType.class);
        private final Map<StatusType, StatusRangeAccumulator> scalarValues = new EnumMap<>(StatusType.class);

        private @Nullable StatusRange resolve(@NotNull StatusType statusType) {
            StatusRangeAccumulator flat = flatValues.get(statusType);
            if (flat == null) {
                return null;
            }
            StatusRangeAccumulator scalar = scalarValues.get(statusType);
            if (scalar == null) {
                return new StatusRange(flat.min, flat.max);
            }
            double[] values = {
                flat.min * scalar.min,
                flat.min * scalar.max,
                flat.max * scalar.min,
                flat.max * scalar.max
            };
            double min = values[0];
            double max = values[0];
            for (double value : values) {
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
            return new StatusRange(min, max);
        }
    }

    private static final class StatusRangeAccumulator {
        private double min;
        private double max;

        private void add(double first, double second) {
            min += Math.min(first, second);
            max += Math.max(first, second);
        }

        private void set(double first, double second) {
            min = Math.min(first, second);
            max = Math.max(first, second);
        }

        private double average() {
            return (min + max) / 2.0D;
        }
    }

    private enum RangeEndpoint {
        MIN,
        MAX,
        AVERAGE;

        private double select(@Nullable StatusRangeAccumulator value) {
            if (value == null) {
                return 0.0D;
            }
            return switch (this) {
                case MIN -> value.min;
                case MAX -> value.max;
                case AVERAGE -> value.average();
            };
        }
    }

    private record StatusRange(double min, double max) {
    }

    private static final class EquipmentStatAmount {
        private final StatusType statusType;
        private final ItemEquipmentStatType type;
        private double min;
        private double max;

        private EquipmentStatAmount(
            @NotNull StatusType statusType,
            @NotNull ItemEquipmentStatType type
        ) {
            this.statusType = statusType;
            this.type = type;
        }

        private void add(double min, double max) {
            this.min += min;
            this.max += max;
        }

        private double average() {
            return (min + max) / 2.0D;
        }
    }

    public record ActiveSetEffect(
        @NotNull String setId,
        @NotNull String setName,
        int equippedCount,
        @NotNull List<Integer> activePieceCounts
    ) {
    }

    private double getAccountModeBonus(@NotNull AccountMode mode, @NotNull StatusType type) {
        return switch (mode) {
            case PLAYER -> 0.0D;
            case BUILDER -> switch (type) {
                case MAX_HEALTH -> 0.0D;
                case MAX_MANA -> 4.0D;
                case MAX_ENERGY -> 0.0D;
                case MAX_SHIELD -> 0.0D;
                case STRENGTH -> 0.0D;
                case DEXTERITY -> 0.0D;
                case INTELLIGENCE -> 0.0D;
                case VITALITY -> 0.0D;
                case AGILITY -> 0.0D;
                case LUCK -> 0.0D;
                case ATTACK -> 2.0D;
                case MELEE_ATTACK -> 0.0D;
                case RANGED_ATTACK -> 0.0D;
                case MAGIC_ATTACK -> 0.0D;
                case CRITICAL_RATE -> 0.0D;
                case CRITICAL_DAMAGE -> 0.0D;
                case SUPER_CRITICAL_RATE -> 0.0D;
                case SUPER_CRITICAL_DAMAGE -> 0.0D;
                case FINAL_DAMAGE_MULTIPLIER -> 0.0D;
                case ACCURACY -> 0.0D;
                case ATTACK_SPEED -> 0.0D;
                case SHIELD_BREAK -> 0.0D;
                case DEFENSE -> 3.0D;
                case MAGIC_DEFENSE -> 2.0D;
                case EVASION -> 0.0D;
                case KNOCKBACK_RESISTANCE -> 0.0D;
                case HP_REGEN -> 0.0D;
                case MP_REGEN -> 0.0D;
                case ENERGY_REGEN -> 0.0D;
                case MOVEMENT_SPEED -> 5.0D;
                case COOLDOWN_REDUCTION -> 0.0D;
                case SHIELD_RECHARGE_REDUCTION -> 0.0D;
                case SHIELD_RECHARGE_RATE -> 0.0D;
                case MINING_SPEED -> 0.0D;
                case QUEST_LIMIT -> 0.0D;
                default -> 0.0D;
            };
            case ADMIN -> switch (type) {
                case MAX_HEALTH -> 10.0D;
                case MAX_MANA -> 10.0D;
                case MAX_ENERGY -> 50.0D;
                case MAX_SHIELD -> 0.0D;
                case STRENGTH -> 5.0D;
                case DEXTERITY -> 5.0D;
                case INTELLIGENCE -> 5.0D;
                case VITALITY -> 5.0D;
                case AGILITY -> 5.0D;
                case LUCK -> 5.0D;
                case ATTACK -> 6.0D;
                case MELEE_ATTACK -> 0.0D;
                case RANGED_ATTACK -> 0.0D;
                case MAGIC_ATTACK -> 0.0D;
                case CRITICAL_RATE -> 5.0D;
                case CRITICAL_DAMAGE -> 25.0D;
                case SUPER_CRITICAL_RATE -> 5.0D;
                case SUPER_CRITICAL_DAMAGE -> 20.0D;
                case FINAL_DAMAGE_MULTIPLIER -> 10.0D;
                case ACCURACY -> 5.0D;
                case ATTACK_SPEED -> 10.0D;
                case SHIELD_BREAK -> 0.0D;
                case DEFENSE -> 6.0D;
                case MAGIC_DEFENSE -> 6.0D;
                case EVASION -> 5.0D;
                case KNOCKBACK_RESISTANCE -> 0.0D;
                case HP_REGEN -> 2.0D;
                case MP_REGEN -> 2.0D;
                case ENERGY_REGEN -> 3.0D;
                case MOVEMENT_SPEED -> 10.0D;
                case COOLDOWN_REDUCTION -> 5.0D;
                case SHIELD_RECHARGE_REDUCTION -> 0.0D;
                case SHIELD_RECHARGE_RATE -> 0.0D;
                case MINING_SPEED -> 0.0D;
                case QUEST_LIMIT -> 0.0D;
                default -> 0.0D;
            };
        };
    }

    private boolean isHealingBlocked(@NotNull AstPlayer player) {
        return conditionService != null && conditionService.isHealingBlocked(AstEntity.player(player));
    }

}
