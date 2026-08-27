package io.github.maaasu.astralRecord.feature.skill.active.service;

import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ActiveSkillLifecycleServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-イベント.md
     * 章・見出し: # 13_3-イベント > ## 4. active skill lifecycle
     * 検証契約: world変更でcast/task/temporary effectを消しcooldownは保持する。
     */
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-イベント.md
     * 章・見出し: # 13_3-イベント > ## 4. active skill lifecycle
     * 検証契約: death/quitでcooldownを含む全一時skill stateを消す。
     */
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
