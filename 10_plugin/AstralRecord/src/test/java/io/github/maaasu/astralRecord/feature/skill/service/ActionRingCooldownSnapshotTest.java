package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.item.service.ItemWeaponAttackService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.model.ActionRingCooldownEntry;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance;
import io.github.maaasu.astralRecord.feature.skill.model.ResolvedLearnedSkill;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindPreset;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.registry.SkillRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActionRingCooldownSnapshotTest {
    // Skill 13-skill: the HUD follows the first eight actual bindings of the selected preset.
    @Test
    void selectedRingPreservesSparseSlotsAndReadsCanonicalCooldown() {
        var skills = mock(SkillService.class);
        var ownership = mock(SkillOwnershipService.class);
        var permission = mock(SkillPermissionService.class);
        var player = mock(AstPlayer.class);

        List<String> bindings = new ArrayList<>(Collections.nCopies(SkillBindPreset.ACTIVE_SLOT_COUNT, null));
        bindings.set(1, "learned");
        bindings.set(5, "removed");
        bindings.set(7, SkillBindPreset.WEAPON_NORMAL_ATTACK_BINDING_ID);
        bindings.set(8, "equipped-only");

        var learned = mock(LearnedSkillInstance.class);
        when(learned.getSkillId()).thenReturn("learned");
        when(ownership.findInstance(player, "learned")).thenReturn(learned);
        when(permission.permittedSkillIds(player)).thenReturn(Set.of("learned"));
        when(permission.isPermittedIn(Set.of("learned"), "learned")).thenReturn(true);
        var definition = mock(SkillDefinition.class);
        when(definition.getId()).thenReturn("variant");
        when(definition.getKind()).thenReturn(SkillKind.ACTIVE);
        when(skills.resolveLearnedSkill(learned)).thenReturn(new ResolvedLearnedSkill(learned, definition, Map.of(), Set.of()));
        when(skills.resolveCooldownId("variant")).thenReturn("canonical");
        when(skills.getRemainingCooldownTicks(any(), eq("canonical"))).thenReturn(33L, 0L);
        when(skills.getCooldownDurationTicks(any(), eq("canonical"))).thenReturn(80L);

        assertEquals(List.of(new ActionRingCooldownEntry(1, "learned", 33, 80, true),
                new ActionRingCooldownEntry(5, "removed", 0, 0, false),
                new ActionRingCooldownEntry(7, SkillBindPreset.WEAPON_NORMAL_ATTACK_BINDING_ID, 0, 0, false)),
                ActionRingCooldownSnapshot.read(player, bindings, skills, ownership, permission, null));
        assertEquals(new ActionRingCooldownEntry(1, "learned", 0, 80, true), ActionRingCooldownSnapshot.read(player, bindings, skills, ownership, permission, null).getFirst());
    }
    @Test
    void weaponSlotUsesSharedTimerAndBecomesUnavailableAfterUnequip() {
        var skills = mock(SkillService.class);
        var registry = mock(SkillRegistry.class);
        var player = mock(AstPlayer.class);
        var weapon = mock(ItemWeaponAttackService.class);
        var ownership = mock(SkillOwnershipService.class);
        var permission = mock(SkillPermissionService.class);
        String binding = SkillBindPreset.WEAPON_NORMAL_ATTACK_BINDING_ID;
        when(weapon.currentLeftClickSkillId(player)).thenReturn("sword", "bow", null);
        when(weapon.getRemainingAttackCooldownTicks(player)).thenReturn(7L, 6L);
        when(skills.registry()).thenReturn(registry);
        var definition = mock(SkillDefinition.class);
        when(registry.getDefinition("sword")).thenReturn(definition);
        when(registry.getDefinition("bow")).thenReturn(definition);
        when(skills.getCooldownDurationTicks(any(), eq(SkillService.WEAPON_NORMAL_ATTACK_COOLDOWN_ID))).thenReturn(20L);

        assertEquals(List.of(new ActionRingCooldownEntry(0, binding, 7, 20, true)),
                ActionRingCooldownSnapshot.read(player, List.of(binding), skills, ownership, permission, weapon));
        assertEquals(List.of(new ActionRingCooldownEntry(0, binding, 6, 20, true)),
                ActionRingCooldownSnapshot.read(player, List.of(binding), skills, ownership, permission, weapon));
        assertEquals(List.of(new ActionRingCooldownEntry(0, binding, 0, 0, false)),
                ActionRingCooldownSnapshot.read(player, List.of(binding), skills, ownership, permission, weapon));
    }

}
