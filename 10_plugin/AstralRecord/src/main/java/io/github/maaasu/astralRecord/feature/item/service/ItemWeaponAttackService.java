package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentOnUse;
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
        if (equipmentDurabilityService != null && !equipmentDurabilityService.canUseMainHandWeapon(player)) {
            return;
        }

        ItemEquipmentOnUse onUse = equipment.getOnUse();
        if (onUse == null) {
            return;
        }

        var rawSkillId = onUse.getLeftClickSkillId();
        if (rawSkillId == null || rawSkillId.isBlank()) {
            return;
        }

        var skillId = rawSkillId.trim();
        var cooldownTicks = onUse.getLeftClickCooldownTicks();
        var caster = new PlayerSkillCaster(player);
        if (cooldownTicks != null && cooldownTicks > 0 && skillService.isOnCooldown(caster, skillId)) {
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
        if (result.success() && cooldownTicks != null && cooldownTicks > 0) {
            skillService.startCooldown(caster, skillId, cooldownTicks.longValue());
        }
    }
}
