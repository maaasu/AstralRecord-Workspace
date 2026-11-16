package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.item.service.ItemWeaponAttackService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.model.ActionRingCooldownEntry;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance;
import io.github.maaasu.astralRecord.feature.skill.model.PlayerSkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.ResolvedLearnedSkill;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindPreset;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class ActionRingCooldownSnapshot {
    private ActionRingCooldownSnapshot() {
    }

    static List<ActionRingCooldownEntry> read(AstPlayer player, List<String> bindings, SkillService skillService,
            SkillOwnershipService ownershipService, SkillPermissionService permissionService,
            @Nullable ItemWeaponAttackService itemWeaponAttackService) {
        if (bindings.isEmpty()) return List.of();
        PlayerSkillCaster caster = new PlayerSkillCaster(player);
        int count = Math.min(bindings.size(), SkillBindPreset.ACTION_RING_SLOT_COUNT);
        List<ActionRingCooldownEntry> result = new ArrayList<>(count);
        Set<String> permitted = null;
        for (int index = 0; index < count; index++) {
            String skillId = bindings.get(index);
            if (skillId == null || skillId.isBlank()) continue;
            if (SkillBindPreset.WEAPON_NORMAL_ATTACK_BINDING_ID.equals(skillId)) {
                String weaponSkill = itemWeaponAttackService == null ? null
                        : itemWeaponAttackService.currentLeftClickSkillId(player);
                boolean available = weaponSkill != null && skillService.registry().getDefinition(weaponSkill) != null;
                long remaining = available ? itemWeaponAttackService.getRemainingAttackCooldownTicks(player) : 0L;
                long duration = available
                        ? skillService.getCooldownDurationTicks(caster, SkillService.WEAPON_NORMAL_ATTACK_COOLDOWN_ID)
                        : 0L;
                result.add(new ActionRingCooldownEntry(index, skillId, remaining, Math.max(remaining, duration), available));
                continue;
            }
            LearnedSkillInstance learned = ownershipService.findInstance(player, skillId);
            if (learned != null && permitted == null) permitted = permissionService.permittedSkillIds(player);
            ResolvedLearnedSkill resolved = learned == null || !permissionService.isPermittedIn(permitted, learned.getSkillId())
                    ? null : skillService.resolveLearnedSkill(learned);
            if (resolved == null || resolved.definition().getKind() != SkillKind.ACTIVE) {
                result.add(new ActionRingCooldownEntry(index, skillId, 0L, 0L, false));
                continue;
            }
            String cooldownId = skillService.resolveCooldownId(resolved.definition().getId());
            long remaining = skillService.getRemainingCooldownTicks(caster, cooldownId);
            long duration = skillService.getCooldownDurationTicks(caster, cooldownId);
            result.add(new ActionRingCooldownEntry(index, skillId, remaining, Math.max(remaining, duration), true));
        }
        return List.copyOf(result);
    }
}
