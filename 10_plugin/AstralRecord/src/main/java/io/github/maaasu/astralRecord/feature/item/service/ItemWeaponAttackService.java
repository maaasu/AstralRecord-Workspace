package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentOnUse;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.model.PlayerSkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastTrigger;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * weapon equipment の左クリック攻撃と右クリック攻撃を処理します。
 */
public final class ItemWeaponAttackService {

    private final SkillService skillService;
    private final ItemReferenceResolver itemReferenceResolver;

    public ItemWeaponAttackService(
            @NotNull ItemService itemService,
            @NotNull SkillService skillService
    ) {
        this.skillService = skillService;
        this.itemReferenceResolver = new ItemReferenceResolver(itemService);
    }

    public void handleLeftClick(
            @NotNull AstPlayer player,
            @Nullable ItemStack itemStack,
            @NotNull Location castLocation
    ) {
        handleAttack(player, itemStack, castLocation, true);
    }

    public void handleRightClick(
            @NotNull AstPlayer player,
            @Nullable ItemStack itemStack,
            @NotNull Location castLocation
    ) {
        handleAttack(player, itemStack, castLocation, false);
    }

    private void handleAttack(
            @NotNull AstPlayer player,
            @Nullable ItemStack itemStack,
            @NotNull Location castLocation,
            boolean leftClick
    ) {
        ItemModel itemModel = resolveItemModel(itemStack);
        if (itemModel == null || itemModel.getEquipment() == null) {
            return;
        }

        ItemEquipment equipment = itemModel.getEquipment();
        if (equipment.getSlot() != ItemEquipmentSlot.WEAPON) {
            return;
        }

        ItemEquipmentOnUse onUse = equipment.getOnUse();
        if (onUse == null) {
            return;
        }

        var rawSkillId = leftClick ? onUse.getLeftClickSkillId() : onUse.getRightClickSkillId();
        if (rawSkillId == null || rawSkillId.isBlank()) {
            return;
        }

        var skillId = rawSkillId.trim();
        var cooldownTicks = leftClick ? onUse.getLeftClickCooldownTicks() : onUse.getRightClickCooldownTicks();
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

    private @Nullable ItemModel resolveItemModel(@Nullable ItemStack itemStack) {
        return itemReferenceResolver.resolveItemModel(itemStack);
    }
}
