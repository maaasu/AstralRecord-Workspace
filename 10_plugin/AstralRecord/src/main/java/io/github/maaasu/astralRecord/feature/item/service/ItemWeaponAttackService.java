package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentOnUse;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.model.PlayerSkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastTrigger;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * weapon equipment の左右クリック攻撃を処理します。
 */
public final class ItemWeaponAttackService {

    private final ItemService itemService;
    private final SkillService skillService;

    public ItemWeaponAttackService(
            @NotNull ItemService itemService,
            @NotNull SkillService skillService
    ) {
        this.itemService = itemService;
        this.skillService = skillService;
    }

    /**
     * 左クリック通常攻撃を処理します。
     *
     * @param player 発動プレイヤー
     * @param itemStack メインハンド武器
     * @param castLocation 発動位置
     */
    public void handleLeftClick(
            @NotNull AstPlayer player,
            @Nullable ItemStack itemStack,
            @NotNull Location castLocation
    ) {
        handleAttack(player, itemStack, castLocation, true);
    }

    /**
     * 右クリック特殊攻撃を処理します。
     *
     * @param player 発動プレイヤー
     * @param itemStack メインハンド武器
     * @param castLocation 発動位置
     */
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

        String rawSkillId = leftClick ? onUse.getLeftClickSkillId() : onUse.getRightClickSkillId();
        if (rawSkillId == null || rawSkillId.isBlank()) {
            return;
        }

        String skillId = rawSkillId.trim();
        Integer cooldownTicks = leftClick ? onUse.getLeftClickCooldownTicks() : onUse.getRightClickCooldownTicks();
        PlayerSkillCaster caster = new PlayerSkillCaster(player);
        if (cooldownTicks != null && cooldownTicks > 0 && skillService.isOnCooldown(caster, skillId)) {
            caster.notify(PlayerMsgId.P_5802);
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
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            return null;
        }

        String itemId = ItemStackFactory.getAstralItemId(itemStack);
        if (itemId == null || itemId.isBlank()) {
            return null;
        }

        ItemModel loaded = itemService.findLoadedById(itemId);
        return loaded != null ? loaded : itemService.loadItem(itemId);
    }
}
