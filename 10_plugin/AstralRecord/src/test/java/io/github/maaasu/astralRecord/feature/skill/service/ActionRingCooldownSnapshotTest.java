package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.hud.service.PlayerHudService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.resourcepack.service.ResourcePackService;
import io.github.maaasu.astralRecord.feature.skill.model.ActionRingCooldownEntry;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance;
import io.github.maaasu.astralRecord.feature.skill.model.ResolvedLearnedSkill;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindPreset;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActionRingCooldownSnapshotTest {
    // Skill 13-skill: the HUD follows the first eight actual bindings of the selected preset.
    @Test
    void selectedRingPreservesSparseSlotsAndReadsCanonicalCooldown() {
        var plugin = mock(AstralRecord.class);
        when(plugin.getName()).thenReturn("AstralRecord");
        var presets = mock(SkillBindPresetService.class);
        var skills = mock(SkillService.class);
        var ownership = mock(SkillOwnershipService.class);
        var permission = mock(SkillPermissionService.class);
        var player = mock(AstPlayer.class);
        var account = mock(AccountModel.class);
        UUID accountId = UUID.randomUUID();
        when(player.getAccount()).thenReturn(account);
        when(account.getUuid()).thenReturn(accountId);
        when(presets.selectedPresetIndex(accountId)).thenReturn(2);

        List<String> bindings = new ArrayList<>(Collections.nCopies(SkillBindPreset.ACTIVE_SLOT_COUNT, null));
        bindings.set(1, "learned");
        bindings.set(5, "removed");
        bindings.set(7, SkillBindPreset.WEAPON_NORMAL_ATTACK_BINDING_ID);
        bindings.set(8, "equipped-only");
        var previous = mock(SkillBindPreset.class);
        when(previous.isUnlocked()).thenReturn(true);
        when(previous.getPresetIndex()).thenReturn(1);
        var chosen = mock(SkillBindPreset.class);
        when(chosen.isUnlocked()).thenReturn(true);
        when(chosen.getPresetIndex()).thenReturn(2);
        when(chosen.getActiveSkillSlots()).thenReturn(bindings);
        when(presets.getPresets(accountId)).thenReturn(List.of(previous, chosen));

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

        var service = new SkillActionRingService(plugin, presets, skills, ownership, permission,
                mock(ResourcePackService.class), mock(PlayerHudService.class));
        assertEquals(List.of(new ActionRingCooldownEntry(1, "learned", 33, 80, true),
                new ActionRingCooldownEntry(5, "removed", 0, 0, false),
                new ActionRingCooldownEntry(7, SkillBindPreset.WEAPON_NORMAL_ATTACK_BINDING_ID, 0, 0, false)),
                service.cooldownHudSnapshot(player));
        assertEquals(new ActionRingCooldownEntry(1, "learned", 0, 80, true), service.cooldownHudSnapshot(player).getFirst());
    }
}
