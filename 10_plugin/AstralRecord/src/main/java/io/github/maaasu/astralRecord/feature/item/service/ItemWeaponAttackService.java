package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.model.PlayerSkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastTrigger;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import org.bukkit.Location;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * weapon equipment の左クリック攻撃を処理します。
 */
public final class ItemWeaponAttackService {

    private final InventoryService inventoryService;
    private final SkillService skillService;
    private EquipmentDurabilityService equipmentDurabilityService;

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
     *
     * @param player 判定対象プレイヤー
     * @return 主手が武器であり、耐久値切れでない場合は {@code true}
     */
    public boolean hasUsableMainHandWeapon(@NotNull AstPlayer player) {
        ItemModel itemModel = inventoryService.getItemModelInHand(player, EquipmentSlot.HAND);
        if (itemModel == null || itemModel.getEquipment() == null) {
            return false;
        }
        if (itemModel.getEquipment().getSlot() != ItemEquipmentSlot.WEAPON) {
            return false;
        }
        return equipmentDurabilityService == null || equipmentDurabilityService.canUseMainHandWeapon(player);
    }

    /**
     * 現在主手に装備している武器のタグから通常攻撃スキル ID を自動解決します。
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
        if (cooldownTicks > 0 && skillService.isOnCooldown(caster, skillId)) {
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
    }

    private @Nullable WeaponAttackDefinition resolveAttack(@Nullable String rawTag) {
        if (rawTag == null || rawTag.isBlank()) return null;
        return switch (rawTag.trim().toUpperCase(Locale.ROOT)) {
            case "SWORD" -> new WeaponAttackDefinition(BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_MELEE, 10L);
            case "BOW" -> new WeaponAttackDefinition(BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_BOW, 12L);
            case "STAFF" -> new WeaponAttackDefinition(BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_MAGIC, 10L);
            default -> null;
        };
    }

    private record WeaponAttackDefinition(@NotNull String skillId, long cooldownTicks) {
    }
}
