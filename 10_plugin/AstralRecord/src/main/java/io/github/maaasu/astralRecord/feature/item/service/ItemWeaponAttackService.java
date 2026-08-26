package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.PlayerSkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastTrigger;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import io.github.maaasu.astralRecord.shared.masterdata.tag.MasterTagIds;
import org.bukkit.Location;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * weapon equipment の左クリック攻撃を処理します。
 */
public final class ItemWeaponAttackService {

    private final InventoryService inventoryService;
    private final SkillService skillService;
    private EquipmentDurabilityService equipmentDurabilityService;
    private Consumer<AstPlayer> attackAttemptListener = player -> { };

    public ItemWeaponAttackService(
            @NotNull InventoryService inventoryService,
            @NotNull SkillService skillService
    ) {
        this.inventoryService = inventoryService;
        this.skillService = skillService;
    }

    public void setEquipmentDurabilityService(@Nullable EquipmentDurabilityService equipmentDurabilityService) {
        this.equipmentDurabilityService = equipmentDurabilityService;
    }

    /**
     * 通常攻撃executorの試行直後に呼び出す通知先を設定します。
     * executorの成否にかかわらず通知するため、受付枠などの試行回数を管理できます。
     *
     * @param attackAttemptListener 試行通知先
     */
    public void setAttackAttemptListener(@NotNull Consumer<AstPlayer> attackAttemptListener) {
        this.attackAttemptListener = attackAttemptListener;
    }

    public void handleLeftClick(
            @NotNull AstPlayer player,
            @NotNull Location castLocation
    ) {
        handleAttack(player, castLocation);
    }

    /**
     * メインハンド装備が左クリック武器アクションを定義しているか判定します。
     * クールダウンや耐久値は実行時に再検証し、候補探索中にはゲーム状態を変更しません。
     *
     * @param player 判定対象プレイヤー
     * @return 左クリック武器アクションを持つ場合は true
     */
    public boolean hasLeftClickAction(@NotNull AstPlayer player) {
        return currentLeftClickSkillId(player) != null;
    }

    /**
     * 現在主手にスキル発動へ使用できる武器があるか判定します。
     * 装備条件（プレイヤーレベル・クラス・クラスレベル）と耐久値も確認します。
     *
     * @param player 判定対象プレイヤー
     * @return 主手が武器であり、装備条件を満たし、耐久値切れでない場合は {@code true}
     */
    public boolean hasUsableMainHandWeapon(@NotNull AstPlayer player) {
        ItemModel itemModel = inventoryService.getItemModelInHand(player, EquipmentSlot.HAND);
        if (itemModel == null || itemModel.getEquipment() == null) {
            return false;
        }
        if (itemModel.getEquipment().getSlot() != ItemEquipmentSlot.WEAPON) {
            return false;
        }
        if (!EquipmentRequirementService.check(player, itemModel.getEquipment()).allowed()) {
            return false;
        }
        return equipmentDurabilityService == null || equipmentDurabilityService.canUseMainHandWeapon(player);
    }

    /**
     * 現在主手に装備している武器のタグから8種類の通常攻撃スキル ID を自動解決します。
     *
     * @param player 対象プレイヤー
     * @return 武器通常攻撃のスキル ID。武器アクションがない場合は {@code null}
     */
    public @Nullable String currentLeftClickSkillId(@NotNull AstPlayer player) {
        ItemModel itemModel = inventoryService.getItemModelInHand(player, EquipmentSlot.HAND);
        if (itemModel == null || itemModel.getEquipment() == null) {
            return null;
        }
        ItemEquipment equipment = itemModel.getEquipment();
        if (equipment.getSlot() != ItemEquipmentSlot.WEAPON) {
            return null;
        }
        WeaponAttackDefinition attack = resolveAttack(equipment.getTag());
        return attack == null ? null : attack.skillId();
    }

    /**
     * 現在装備中の通常攻撃スキルの残りクールダウン tick を返します。
     *
     * @param player 対象プレイヤー
     * @return 残りクールダウン（tick）。スキルが未設定、または非クールダウン時は {@code 0}
     */
    public long getRemainingAttackCooldownTicks(@NotNull AstPlayer player) {
        if (currentLeftClickSkillId(player) == null) {
            return 0L;
        }
        SkillCaster caster = new PlayerSkillCaster(player);
        return skillService.getRemainingCooldownTicks(caster, SkillService.WEAPON_NORMAL_ATTACK_COOLDOWN_ID);
    }

    private void handleAttack(
            @NotNull AstPlayer player,
            @NotNull Location castLocation
    ) {
        ItemModel itemModel = inventoryService.getItemModelInHand(player, EquipmentSlot.HAND);
        if (itemModel == null || itemModel.getEquipment() == null) {
            return;
        }

        ItemEquipment equipment = itemModel.getEquipment();
        if (equipment.getSlot() != ItemEquipmentSlot.WEAPON) {
            return;
        }
        if (!EquipmentRequirementService.checkAndNotify(player, equipment)) {
            return;
        }
        if (equipmentDurabilityService != null && !equipmentDurabilityService.canUseMainHandWeapon(player)) {
            return;
        }

        WeaponAttackDefinition attack = resolveAttack(equipment.getTag());
        if (attack == null) return;
        String skillId = attack.skillId();
        long cooldownTicks = attack.cooldownTicks();
        var caster = new PlayerSkillCaster(player);
        if (cooldownTicks > 0 && skillService.isOnCooldown(caster, SkillService.WEAPON_NORMAL_ATTACK_COOLDOWN_ID)) {
            return;
        }

        var result = skillService.castSkill(
                caster,
                skillId,
                SkillCastTrigger.AUTO_ATTACK,
                castLocation,
                null,
                List.of()
        );
        if (result.success() && cooldownTicks > 0) {
            skillService.startAttackCooldown(caster, skillId, cooldownTicks);
        }
        attackAttemptListener.accept(player);
    }

    private @Nullable WeaponAttackDefinition resolveAttack(@Nullable String rawTag) {
        if (rawTag == null || rawTag.isBlank()) return null;
        return switch (rawTag.trim().toUpperCase(Locale.ROOT)) {
            case MasterTagIds.Equipment.SWORD -> new WeaponAttackDefinition(BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_MELEE, 10L);
            case MasterTagIds.Equipment.HAMMER -> new WeaponAttackDefinition(BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_HAMMER, 22L);
            case MasterTagIds.Equipment.SPEAR -> new WeaponAttackDefinition(BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_SPEAR, 14L);
            case MasterTagIds.Equipment.BOW -> new WeaponAttackDefinition(BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_BOW, 12L);
            case MasterTagIds.Equipment.SHORTBOW -> new WeaponAttackDefinition(BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_SHORTBOW, 7L);
            case MasterTagIds.Equipment.LONGBOW -> new WeaponAttackDefinition(BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_LONGBOW, 17L);
            case MasterTagIds.Equipment.WAND -> new WeaponAttackDefinition(BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_WAND, 9L);
            case MasterTagIds.Equipment.STAFF -> new WeaponAttackDefinition(BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_MAGIC, 14L);
            default -> null;
        };
    }

    private record WeaponAttackDefinition(@NotNull String skillId, long cooldownTicks) {
    }
}
