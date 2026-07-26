package io.github.maaasu.astralRecord.feature.skill.active.service;

import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ActiveSkillLifecycleServiceTest {

    @Test
    void worldChangeClearsTransientStateAndPreservesCooldowns() {
        SkillService skillService = mock(SkillService.class);
        SkillTaskService taskService = mock(SkillTaskService.class);
        TemporarySkillEffectService temporaryEffectService = mock(TemporarySkillEffectService.class);
        ActiveSkillLifecycleService lifecycleService = new ActiveSkillLifecycleService(
                skillService,
                taskService,
                temporaryEffectService
        );
        UUID playerId = UUID.randomUUID();

        lifecycleService.clearTransient(playerId);

        verify(skillService).cancelCasting(playerId);
        verify(skillService, never()).clearCasterState(playerId);
        verify(taskService).clearCaster(playerId);
        verify(temporaryEffectService).clear(playerId);
    }

    @Test
    void deathOrQuitClearsCooldownsAndAllTransientState() {
        SkillService skillService = mock(SkillService.class);
        SkillTaskService taskService = mock(SkillTaskService.class);
        TemporarySkillEffectService temporaryEffectService = mock(TemporarySkillEffectService.class);
        ActiveSkillLifecycleService lifecycleService = new ActiveSkillLifecycleService(
                skillService,
                taskService,
                temporaryEffectService
        );
        UUID playerId = UUID.randomUUID();

        lifecycleService.clearAll(playerId);

        verify(skillService).clearCasterState(playerId);
        verify(skillService, never()).cancelCasting(playerId);
        verify(taskService).clearCaster(playerId);
        verify(temporaryEffectService).clear(playerId);
    }
}
